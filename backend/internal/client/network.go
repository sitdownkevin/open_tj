package client

import (
	"context"
	"fmt"
	"net/netip"
	"os/exec"
	"reflect"
	"regexp"
	"strings"
	"time"
)

type Runner func(...string) (string, error)

func SystemRun(args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	p, e := exec.CommandContext(ctx, args[0], args[1:]...).CombinedOutput()
	if e != nil {
		return "", fmt.Errorf("%s: %w: %s", strings.Join(args[:min(3, len(args))], " "), e, strings.TrimSpace(string(p)))
	}
	return string(p), nil
}

type Journal struct {
	Run  Runner
	Undo [][]string
}

func (j *Journal) Apply(add, undo []string) error {
	if _, e := j.Run(add...); e != nil {
		return e
	}
	j.Undo = append(j.Undo, undo)
	return nil
}
func (j *Journal) Rollback() []string {
	var failed [][]string
	var errors []string
	for i := len(j.Undo) - 1; i >= 0; i-- {
		if _, e := j.Run(j.Undo[i]...); e != nil {
			failed = append([][]string{j.Undo[i]}, failed...)
			errors = append(errors, e.Error())
		}
	}
	j.Undo = failed
	return errors
}

const setup = "/usr/sbin/networksetup"

var proxyOptions = [][2]string{{"-getwebproxy", "-setwebproxystate"}, {"-getsecurewebproxy", "-setsecurewebproxystate"}, {"-getsocksfirewallproxy", "-setsocksfirewallproxystate"}, {"-getautoproxyurl", "-setautoproxystate"}, {"-getproxyautodiscovery", "-setproxyautodiscovery"}}

type Snapshot struct {
	Service string
	DNS     []string
	IPv6    string
	Proxies []bool
}

func ipv6Mode(text string) (string, error) {
	re := regexp.MustCompile(`(?m)^IPv6:\s*(.+)$`)
	m := re.FindStringSubmatch(text)
	if len(m) < 2 {
		return "", fmt.Errorf("无法识别 IPv6 状态")
	}
	mode := strings.ToLower(strings.TrimSpace(m[1]))
	if mode != "automatic" && mode != "off" && mode != "link-local only" {
		return "", fmt.Errorf("不支持恢复手动 IPv6 设置")
	}
	return mode, nil
}
func TakeSnapshot(run Runner, service string) (Snapshot, error) {
	s := Snapshot{Service: service}
	p, e := run(setup, "-getdnsservers", service)
	if e != nil {
		return s, e
	}
	if !strings.Contains(p, "aren't any DNS") {
		for _, v := range strings.Fields(p) {
			if _, e := netip.ParseAddr(v); e != nil {
				return s, e
			}
			s.DNS = append(s.DNS, v)
		}
	}
	p, e = run(setup, "-getinfo", service)
	if e != nil {
		return s, e
	}
	s.IPv6, e = ipv6Mode(p)
	if e != nil {
		return s, e
	}
	re := regexp.MustCompile(`(?mi)^(?:Enabled|Auto Proxy Discovery):\s*(Yes|No|On|Off)\s*$`)
	for _, o := range proxyOptions {
		p, e = run(setup, o[0], service)
		if e != nil {
			return s, e
		}
		m := re.FindStringSubmatch(p)
		if len(m) < 2 {
			return s, fmt.Errorf("无法识别代理状态")
		}
		s.Proxies = append(s.Proxies, strings.EqualFold(m[1], "yes") || strings.EqualFold(m[1], "on"))
	}
	return s, nil
}
func RouteFields(p string) map[string]string {
	m := map[string]string{}
	for _, l := range strings.Split(p, "\n") {
		k, v, ok := strings.Cut(l, ":")
		if ok {
			m[strings.TrimSpace(k)] = strings.TrimSpace(v)
		}
	}
	return m
}
func ActiveService(run Runner, device string) (string, error) {
	p, e := run(setup, "-listnetworkserviceorder")
	if e != nil {
		return "", e
	}
	re := regexp.MustCompile(`^\(\d+\)\s+(.+)$`)
	service := ""
	for _, l := range strings.Split(p, "\n") {
		if strings.HasPrefix(strings.TrimSpace(l), "(*)") {
			service = ""
		} else if m := re.FindStringSubmatch(strings.TrimSpace(l)); len(m) > 1 {
			service = m[1]
		}
		if strings.Contains(l, "Device: "+device+")") && service != "" {
			return service, nil
		}
	}
	return "", fmt.Errorf("找不到出口网络服务 %s", device)
}
func IPv6Conflicts(table string) []string {
	var out []string
	private := netip.MustParsePrefix("fc00::/7")
	for _, l := range strings.Split(table, "\n") {
		f := strings.Fields(l)
		if len(f) < 4 || f[0] == "default" || f[0] == "::/1" || f[0] == "8000::/1" {
			continue
		}
		dest := f[0]
		if i := strings.Index(dest, "%"); i >= 0 {
			tail := ""
			if j := strings.Index(dest[i:], "/"); j >= 0 {
				tail = dest[i+j:]
			}
			dest = dest[:i] + tail
		}
		if !strings.Contains(dest, "/") {
			dest += "/128"
		}
		p, e := netip.ParsePrefix(dest)
		if e != nil {
			continue
		}
		a := p.Addr()
		if a.IsLoopback() || a.IsLinkLocalUnicast() || a.IsMulticast() || a.IsUnspecified() {
			continue
		}
		if f[3] == "lo0" && p.Bits() == 128 {
			continue
		}
		if private.Contains(a) && p.Bits() >= 7 && strings.HasPrefix(f[3], "bridge") && strings.HasPrefix(f[1], "link#") && !strings.Contains(f[2], "G") {
			continue
		}
		out = append(out, f[0]+" @ "+f[3])
	}
	return out
}

type NetworkConfig struct {
	IP        string   `json:"ip"`
	DNS       []string `json:"dns"`
	Gateway   string   `json:"gateway"`
	Router    string   `json:"router"`
	Interface string   `json:"interface"`
	Before    Snapshot `json:"before"`
}
type Network struct {
	Run              Runner
	Config           NetworkConfig
	Routes, Settings Journal
	Modes            map[string]string
}

func (n *Network) Configure(name string) error {
	n.Routes.Run = n.Run
	n.Settings.Run = n.Run
	n.Modes = map[string]string{}
	c := n.Config
	if !regexp.MustCompile(`^utun[0-9]+$`).MatchString(name) ||
		!regexp.MustCompile(`^en[0-9]+$`).MatchString(c.Interface) ||
		c.Before.Service == "" || strings.ContainsAny(c.Before.Service, "\x00\r\n") ||
		len(c.Before.Proxies) != len(proxyOptions) {
		return fmt.Errorf("invalid network service configuration")
	}
	for _, ip := range append([]string{c.IP, c.Gateway, c.Router}, c.DNS...) {
		a, e := netip.ParseAddr(ip)
		if e != nil || !a.Is4() {
			return fmt.Errorf("invalid IPv4 configuration")
		}
	}
	if len(c.DNS) == 0 || len(c.DNS) > 4 {
		return fmt.Errorf("invalid DNS list")
	}
	now, e := TakeSnapshot(n.Run, c.Before.Service)
	if e != nil {
		return e
	}
	if !reflect.DeepEqual(now, c.Before) {
		return fmt.Errorf("网络设置已变化，请重新连接")
	}
	route, e := n.Run("/sbin/route", "-n", "get", c.Gateway)
	if e != nil {
		return e
	}
	fields := RouteFields(route)
	if fields["gateway"] != c.Router || fields["interface"] != c.Interface {
		return fmt.Errorf("网关出口已变化")
	}
	if _, e = n.Run("/sbin/ifconfig", name, "inet", c.IP, c.IP, "netmask", "255.255.255.255", "mtu", "1280", "up"); e != nil {
		return e
	}
	for i, o := range proxyOptions {
		if c.Before.Proxies[i] {
			if e = n.Settings.Apply([]string{setup, o[1], c.Before.Service, "off"}, []string{setup, o[1], c.Before.Service, "on"}); e != nil {
				return e
			}
		}
	}
	old := c.Before.DNS
	if len(old) == 0 {
		old = []string{"Empty"}
	}
	if e = n.Settings.Apply(append([]string{setup, "-setdnsservers", c.Before.Service}, c.DNS...), append([]string{setup, "-setdnsservers", c.Before.Service}, old...)); e != nil {
		return e
	}
	services, e := n.Run(setup, "-listallnetworkservices")
	if e != nil {
		return e
	}
	lines := strings.Split(strings.TrimSpace(services), "\n")
	for _, service := range lines[1:] {
		if service == "" || strings.HasPrefix(service, "*") {
			continue
		}
		p, e := n.Run(setup, "-getinfo", service)
		if e != nil {
			return e
		}
		mode, e := ipv6Mode(p)
		if e != nil {
			return e
		}
		n.Modes[service] = mode
		if mode != "off" {
			undo := "-setv6automatic"
			if mode == "link-local only" {
				undo = "-setv6linklocal"
			}
			if e = n.Settings.Apply([]string{setup, "-setv6off", service}, []string{setup, undo, service}); e != nil {
				return e
			}
		}
	}
	for _, prefix := range []string{"::/1", "8000::/1"} {
		if e = n.Routes.Apply([]string{"/sbin/route", "-n", "add", "-inet6", "-net", prefix, "::1", "-reject"}, []string{"/sbin/route", "-n", "delete", "-inet6", "-net", prefix, "::1"}); e != nil {
			return e
		}
	}
	if e = n.Routes.Apply([]string{"/sbin/route", "-n", "add", "-host", c.Gateway, c.Router}, []string{"/sbin/route", "-n", "delete", "-host", c.Gateway, c.Router}); e != nil {
		return e
	}
	for _, prefix := range []string{"0.0.0.0/1", "128.0.0.0/1"} {
		if e = n.Routes.Apply([]string{"/sbin/route", "-n", "add", "-net", prefix, "-interface", name}, []string{"/sbin/route", "-n", "delete", "-net", prefix, "-interface", name}); e != nil {
			return e
		}
	}
	return n.Verify()
}
func (n *Network) Verify() error {
	p, e := n.Run("/usr/sbin/netstat", "-rn", "-f", "inet6")
	if e != nil {
		return e
	}
	if c := IPv6Conflicts(p); len(c) > 0 {
		return fmt.Errorf("IPv6 冲突路由：%s", c[0])
	}
	for _, sample := range []string{"2001:4860:4860::8888", "9000::1"} {
		p, e = n.Run("/sbin/route", "-n", "get", "-inet6", sample)
		if e != nil {
			return e
		}
		if !strings.Contains(p, "REJECT") {
			return fmt.Errorf("IPv6 保护路由失效")
		}
	}
	return nil
}
func (n *Network) Cleanup() []string {
	n.Routes.Rollback()
	r := n.Routes.Rollback()
	n.Settings.Rollback()
	r = append(r, n.Settings.Rollback()...)
	s, e := TakeSnapshot(n.Run, n.Config.Before.Service)
	if e != nil || !reflect.DeepEqual(s, n.Config.Before) {
		r = append(r, "网络设置恢复核对失败")
	}
	for service, mode := range n.Modes {
		p, e := n.Run(setup, "-getinfo", service)
		m, me := ipv6Mode(p)
		if e != nil || me != nil || m != mode {
			r = append(r, "IPv6 恢复核对失败: "+service)
		}
	}
	return r
}
