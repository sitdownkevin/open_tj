package main

import "testing"

func TestRetryPolicy(t *testing.T) {
	clean := map[string]any{"host_network_modified": true, "cleanup_confirmed": true, "parent_state_restored": true, "outer_route_restored": true}
	result := map[string]any{"site_test": clean}
	if !retryPolicy(result, false, false) {
		t.Fatal("restored disconnect should retry")
	}
	if retryPolicy(result, false, true) {
		t.Fatal("manual stop must not retry")
	}
	clean["startup_failed"] = true
	if retryPolicy(result, false, false) {
		t.Fatal("startup failure must stop")
	}
	if !retryPolicy(result, true, false) {
		t.Fatal("sleep cancellation should resume after cleanup")
	}
	for _, key := range []string{"cleanup_confirmed", "parent_state_restored", "outer_route_restored"} {
		clean[key] = false
		if retryPolicy(result, true, false) {
			t.Fatal("sleep must not bypass failed recovery", key)
		}
		clean[key] = true
	}
	if !retryPolicy(map[string]any{"error": "context canceled"}, true, false) {
		t.Fatal("sleep during authentication must not end supervisor")
	}
	if retryPolicy(map[string]any{"error": "authentication failed"}, false, false) {
		t.Fatal("authentication must not retry")
	}
}
