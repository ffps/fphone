package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadConfigDefaultsVideoSettings(t *testing.T) {
	path := filepath.Join(t.TempDir(), "fphone.yaml")
	if err := os.WriteFile(path, []byte("turn:\n  enabled: false\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := LoadConfig(path)
	if err != nil {
		t.Fatal(err)
	}
	got := cfg.Media.Video
	if got.Width != 640 || got.Height != 360 || got.FPS != 20 || got.MaxBitrate != 450_000 {
		t.Fatalf("unexpected default video settings: %+v", got)
	}
}

func TestConfigRejectsInvalidVideoSettings(t *testing.T) {
	cfg := defaultConfig()
	cfg.Turn.Enabled = false
	cfg.Media.Video.FPS = 0
	if err := cfg.validate(); err == nil {
		t.Fatal("expected invalid media.video.fps to be rejected")
	}
}
