package client

import (
	"testing"
	"time"
)

func TestTrafficMeterActualElapsedTime(t *testing.T) {
	now := time.Unix(0, 0)
	m := NewTrafficMeter(now)
	s := &BridgeStats{}
	s.UploadBytes.Store(4096)
	s.DownloadBytes.Store(8192)
	got := m.Sample(now.Add(2*time.Second), s)
	if got.UploadBPS != 2048 || got.DownloadBPS != 4096 || got.DownloadBytes != 8192 {
		t.Fatalf("%+v", got)
	}
	got = m.Sample(now.Add(3*time.Second), s)
	if got.UploadBPS != 0 || got.DownloadBPS != 0 {
		t.Fatalf("idle rate %+v", got)
	}
	s.UploadBytes.Store(0)
	got = m.Sample(now.Add(4*time.Second), s)
	if got.UploadBPS < 0 {
		t.Fatal("negative speed")
	}
}
