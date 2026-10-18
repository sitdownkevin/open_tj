package client

import "time"

type TrafficSample struct {
	UploadBPS     float64 `json:"upload_bps"`
	DownloadBPS   float64 `json:"download_bps"`
	UploadBytes   int64   `json:"upload_bytes"`
	DownloadBytes int64   `json:"download_bytes"`
}
type TrafficMeter struct {
	previous         time.Time
	upload, download int64
}

func NewTrafficMeter(now time.Time) *TrafficMeter { return &TrafficMeter{previous: now} }
func (m *TrafficMeter) Sample(now time.Time, s *BridgeStats) TrafficSample {
	u, d := s.UploadBytes.Load(), s.DownloadBytes.Load()
	sample := TrafficSample{UploadBytes: u, DownloadBytes: d}
	seconds := now.Sub(m.previous).Seconds()
	if seconds > 0 {
		sample.UploadBPS = float64(max(0, u-m.upload)) / seconds
		sample.DownloadBPS = float64(max(0, d-m.download)) / seconds
	}
	m.previous = now
	m.upload = u
	m.download = d
	return sample
}
