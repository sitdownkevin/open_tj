package main

import (
	"os/exec"
	"path/filepath"
	"testing"
)

func TestAuthorizationScriptCompiles(t *testing.T) {
	command := quote("/tmp/中文 path/motionvpn") + " --helper " + quote("/tmp/sock 'quoted'") + " " + quote("token")
	script := "do shell script " + apple(command) + " with administrator privileges"
	cmd := exec.Command("/usr/bin/osacompile", "-o", filepath.Join(t.TempDir(), "helper.scpt"), "-e", script)
	if p, e := cmd.CombinedOutput(); e != nil {
		t.Fatalf("%v: %s", e, p)
	}
}
