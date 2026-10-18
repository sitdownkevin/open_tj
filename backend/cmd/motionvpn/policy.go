package main

// RetryPolicy separates session cancellation from recovery safety. Sleep can
// interrupt authentication before any network change; it must not end the GUI
// supervisor, while uncertain network restoration must always stop it.
func retryPolicy(result map[string]any, sleeping, stopped bool) bool {
	if stopped {
		return false
	}
	site, hasSite := result["site_test"].(map[string]any)
	if !hasSite {
		return sleeping
	}
	if site["host_network_modified"] == true {
		restored := site["cleanup_confirmed"] == true && site["parent_state_restored"] == true && site["outer_route_restored"] == true
		return restored && (sleeping || site["startup_failed"] != true)
	}
	return sleeping
}
