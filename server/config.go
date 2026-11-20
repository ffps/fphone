package main

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"time"

	"gopkg.in/yaml.v3"
)

type Config struct {
	Listen             string      `yaml:"listen"`
	ControlPort        int         `yaml:"control_port"`
	DataDir            string      `yaml:"data_dir"`
	PublicHost         string      `yaml:"public_host"`
	AllowedOrigins     []string    `yaml:"allowed_origins"`
	StaticCacheSeconds int         `yaml:"static_cache_seconds"`
	LegacyTestPage     bool        `yaml:"test_page"` // accepted for old configs; /test is no longer served
	TLS                TLSConfig   `yaml:"tls"`
	Log                LogConfig   `yaml:"log"`
	Auth               AuthConfig  `yaml:"auth"`
	Turn               TurnConfig  `yaml:"turn"`
	Calls              CallsConfig `yaml:"calls"`
	FCM                FCMConfig   `yaml:"fcm"`
}

type TLSConfig struct {
	Mode string `yaml:"mode"` // off | self-signed | files
	Cert string `yaml:"cert"`
	Key  string `yaml:"key"`
}

type LogConfig struct {
	File      string `yaml:"file"`
	MaxSizeMB int    `yaml:"max_size_mb"`
	Keep      int    `yaml:"keep"`
	Level     string `yaml:"level"`
}

type AuthConfig struct {
	TokenTTLDays int `yaml:"token_ttl_days"`
}

type TurnConfig struct {
	Enabled               bool   `yaml:"enabled"`
	ListenPort            int    `yaml:"listen_port"`              // UDP and TCP
	RelayIP               string `yaml:"relay_ip"`                 // fixed public IPv4; empty = follow public_host
	RelayIPRefreshSeconds int    `yaml:"relay_ip_refresh_seconds"` // how often a DNS name is re-resolved
	RelayPortMin          int    `yaml:"relay_port_min"`           // UDP relay port range
	RelayPortMax          int    `yaml:"relay_port_max"`
	HairpinSrc            string `yaml:"hairpin_src"` // "", "auto" or an IP address (see the example config)
	ICEPolicy             string `yaml:"ice_policy"`  // relay | all
	CredentialTTLMinutes  int    `yaml:"credential_ttl_minutes"`
}

type CallsConfig struct {
	RingTimeoutSeconds    int `yaml:"ring_timeout_seconds"`
	ReconnectGraceSeconds int `yaml:"reconnect_grace_seconds"`
	MaxActive             int `yaml:"max_active"`
}

type FCMConfig struct {
	CredentialsFile string `yaml:"credentials_file"` // service account JSON; empty = push disabled
	ProjectID       string `yaml:"project_id"`       // optional, normally read from the file
	SendCallerName  bool   `yaml:"send_caller_name"` // put the caller name into the push message
}

func defaultConfig() *Config {
	return &Config{
		Listen:      ":8099",
		ControlPort: 8098,
		DataDir:     "data",
		TLS:         TLSConfig{Mode: "off"},
		Log:         LogConfig{MaxSizeMB: 5, Keep: 3, Level: "debug"},
		Auth:        AuthConfig{TokenTTLDays: 90},
		Turn: TurnConfig{
			Enabled:               true,
			ListenPort:            3478,
			RelayIPRefreshSeconds: 60,
			RelayPortMin:          49160,
			RelayPortMax:          49200,
			ICEPolicy:             "relay",
			CredentialTTLMinutes:  120,
		},
		Calls: CallsConfig{RingTimeoutSeconds: 60, ReconnectGraceSeconds: 30, MaxActive: 5},
		FCM:   FCMConfig{SendCallerName: true},
	}
}

// LoadConfig reads the YAML file. Relative paths are resolved from the folder
// of the config file, so the current directory at start (systemd, etc.) does not matter.
func LoadConfig(path string) (*Config, error) {
	abs, err := filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	b, err := os.ReadFile(abs)
	if err != nil {
		return nil, fmt.Errorf("cannot read config: %w\n(to create an example: fphone example-config > %s)", err, path)
	}
	cfg := defaultConfig()
	dec := yaml.NewDecoder(bytes.NewReader(b))
	dec.KnownFields(true) // a typo in a key is an error, not silently ignored
	if err := dec.Decode(cfg); err != nil && !errors.Is(err, io.EOF) {
		return nil, fmt.Errorf("config %s: %w", abs, err)
	}
	base := filepath.Dir(abs)
	cfg.DataDir = resolvePath(base, cfg.DataDir)
	cfg.TLS.Cert = resolvePath(base, cfg.TLS.Cert)
	cfg.TLS.Key = resolvePath(base, cfg.TLS.Key)
	cfg.Log.File = resolvePath(base, cfg.Log.File)
	cfg.FCM.CredentialsFile = resolvePath(base, cfg.FCM.CredentialsFile)
	if err := cfg.validate(); err != nil {
		return nil, fmt.Errorf("config %s: %w", abs, err)
	}
	return cfg, nil
}

func resolvePath(base, p string) string {
	if p == "" || filepath.IsAbs(p) {
		return p
	}
	return filepath.Join(base, p)
}

func (c *Config) validate() error {
	if c.DataDir == "" {
		return errors.New("data_dir must not be empty")
	}
	if c.StaticCacheSeconds < 0 {
		return errors.New("static_cache_seconds must be >= 0")
	}
	if c.ControlPort < 1 || c.ControlPort > 65535 {
		return errors.New("control_port must be between 1 and 65535")
	}
	switch c.TLS.Mode {
	case "off", "self-signed":
	case "files":
		if c.TLS.Cert == "" || c.TLS.Key == "" {
			return errors.New("tls.mode=files requires tls.cert and tls.key")
		}
	default:
		return fmt.Errorf("tls.mode: unknown value %q (use off, self-signed or files)", c.TLS.Mode)
	}
	if c.Auth.TokenTTLDays < 1 {
		return errors.New("auth.token_ttl_days must be >= 1")
	}
	if c.Log.MaxSizeMB < 1 {
		return errors.New("log.max_size_mb must be >= 1")
	}
	if c.Log.Keep < 0 {
		return errors.New("log.keep must not be negative")
	}
	switch c.Log.Level {
	case "debug", "info", "warn", "error":
	default:
		return fmt.Errorf("log.level: unknown value %q (use debug, info, warn or error)", c.Log.Level)
	}
	if c.Calls.RingTimeoutSeconds < 5 || c.Calls.ReconnectGraceSeconds < 5 || c.Calls.MaxActive < 1 {
		return errors.New("calls: ring_timeout_seconds and reconnect_grace_seconds must be >= 5, max_active must be >= 1")
	}
	if c.Turn.Enabled {
		if c.PublicHost == "" {
			return errors.New("turn.enabled requires public_host (clients use it in the TURN URLs)")
		}
		if c.Turn.RelayIP != "" {
			if ip := net.ParseIP(c.Turn.RelayIP); ip == nil || ip.To4() == nil {
				return errors.New("turn.relay_ip must be an IPv4 address (or empty to follow public_host)")
			}
		}
		if c.Turn.RelayIPRefreshSeconds < 10 {
			return errors.New("turn.relay_ip_refresh_seconds must be >= 10")
		}
		if c.Turn.ListenPort < 1 || c.Turn.ListenPort > 65535 {
			return errors.New("turn.listen_port is out of range")
		}
		if c.Turn.RelayPortMin < 1024 || c.Turn.RelayPortMax > 65535 || c.Turn.RelayPortMin > c.Turn.RelayPortMax {
			return errors.New("turn.relay_port_min/relay_port_max: need 1024 <= min <= max <= 65535")
		}
		if c.Turn.HairpinSrc != "" && c.Turn.HairpinSrc != "auto" && net.ParseIP(c.Turn.HairpinSrc) == nil {
			return fmt.Errorf("turn.hairpin_src: use an IP address, \"auto\" or leave it empty (got %q)", c.Turn.HairpinSrc)
		}
		switch c.Turn.ICEPolicy {
		case "relay", "all":
		default:
			return fmt.Errorf("turn.ice_policy: unknown value %q (use relay or all)", c.Turn.ICEPolicy)
		}
		if c.Turn.CredentialTTLMinutes < 10 {
			return errors.New("turn.credential_ttl_minutes must be >= 10")
		}
	}
	return nil
}

func (c *Config) tokenTTL() time.Duration {
	return time.Duration(c.Auth.TokenTTLDays) * 24 * time.Hour
}
