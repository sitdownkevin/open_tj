package client

import (
	"fmt"
	"strings"
	"testing"
)

func TestConfigureFailureRestoresSettingsAndOwnedRoutes(t *testing.T) {
	dns := "automatic"
	mode := "automatic"
	routes := map[string]bool{}
	run := func(a ...string) (string, error) {
		joined := strings.Join(a, " ")
		switch {
		case strings.Contains(joined, "-getdnsservers"):
			if dns == "automatic" {
				return "There aren't any DNS Servers set", nil
			}
			return dns, nil
		case strings.Contains(joined, "-getinfo"):
			return "IPv6: " + mode, nil
		case strings.Contains(joined, "-getwebproxy"), strings.Contains(joined, "-getsecurewebproxy"), strings.Contains(joined, "-getsocksfirewallproxy"), strings.Contains(joined, "-getautoproxyurl"), strings.Contains(joined, "-getproxyautodiscovery"):
			return "Enabled: No", nil
		case strings.Contains(joined, "-listallnetworkservices"):
			return "header\nWi-Fi", nil
		case strings.Contains(joined, "-setdnsservers"):
			dns = a[len(a)-1]
			if dns == "Empty" {
				dns = "automatic"
			}
			return "", nil
		case strings.Contains(joined, "-setv6off"):
			mode = "off"
			return "", nil
		case strings.Contains(joined, "-setv6automatic"):
			mode = "automatic"
			return "", nil
		case strings.Contains(joined, "route -n get"):
			return "gateway: 192.0.2.1\ninterface: en0", nil
		case strings.Contains(joined, "128.0.0.0/1") && a[2] == "add":
			return "", fmt.Errorf("injected second split route failure")
		case len(a) > 2 && a[0] == "/sbin/route" && a[2] == "add":
			routes[strings.Join(a[3:], " ")] = true
			return "", nil
		case len(a) > 2 && a[0] == "/sbin/route" && a[2] == "delete":
			key := strings.Join(a[3:], " ")
			key = strings.TrimSuffix(key, " -reject")
			found := false
			for k := range routes {
				if strings.TrimSuffix(k, " -reject") == key {
					delete(routes, k)
					found = true
					break
				}
			}
			if !found {
				return "", fmt.Errorf("unowned deletion %s", key)
			}
			return "", nil
		}
		return "", nil
	}
	before, e := TakeSnapshot(run, "Wi-Fi")
	if e != nil {
		t.Fatal(e)
	}
	n := Network{Run: run, Config: NetworkConfig{IP: "10.0.0.2", DNS: []string{"10.0.0.1"}, Gateway: "198.51.100.1", Router: "192.0.2.1", Interface: "en0", Before: before}}
	if e = n.Configure("utun99"); e == nil {
		t.Fatal("injection did not fail")
	}
	if failures := n.Cleanup(); len(failures) > 0 {
		t.Fatal(failures)
	}
	if len(routes) != 0 || dns != "automatic" || mode != "automatic" {
		t.Fatal(routes, dns, mode)
	}
}
