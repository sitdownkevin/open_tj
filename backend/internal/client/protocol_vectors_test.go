package client

import (
	"encoding/hex"
	"encoding/json"
	"os"
	"testing"
)

func TestSharedAndroidProtocolVectors(t *testing.T) {
	data, err := os.ReadFile("../../../testdata/vpn-protocol.json")
	if err != nil {
		t.Fatal(err)
	}
	var vectors struct {
		Packets []struct {
			Command   uint16 `json:"command"`
			Parameter uint32 `json:"parameter"`
			Hex       string `json:"hex"`
		} `json:"control_packets"`
		Addresses []struct {
			Value   json.RawMessage `json:"value"`
			Address string          `json:"address"`
		} `json:"ipv4_values"`
	}
	if err := json.Unmarshal(data, &vectors); err != nil {
		t.Fatal(err)
	}
	for _, vector := range vectors.Packets {
		if got := hex.EncodeToString(ATP(vector.Command, vector.Parameter)); got != vector.Hex {
			t.Fatalf("command %d: %s != %s", vector.Command, got, vector.Hex)
		}
	}
	for _, vector := range vectors.Addresses {
		got, err := IPv4(vector.Value)
		if err != nil || got != vector.Address {
			t.Fatalf("address: %s %v", got, err)
		}
	}
}
