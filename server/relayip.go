package main

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"sync"
	"time"
)

// RelayIP is the public IPv4 address that the TURN server puts into relay addresses.
//
//   turn.relay_ip set          -> fixed address
//   public_host is an IPv4     -> fixed address
//   public_host is a DNS name  -> resolved at start and re-resolved periodically, so a
//                                 dynamic public IP (with dynamic DNS) is followed automatically
type RelayIP struct {
	mu   sync.RWMutex
	ip   net.IP
	host string // DNS name to resolve; empty when the address is fixed
}

func NewRelayIP(cfg *Config) (*RelayIP, error) {
	r := &RelayIP{}
	if cfg.Turn.RelayIP != "" {
		r.ip = net.ParseIP(cfg.Turn.RelayIP).To4()
		return r, nil
	}
	if ip := net.ParseIP(cfg.PublicHost); ip != nil && ip.To4() != nil {
		r.ip = ip.To4()
		return r, nil
	}
	r.host = cfg.PublicHost
	ip, err := lookupPublicIPv4(r.host)
	if err != nil {
		return nil, err
	}
	r.ip = ip
	return r, nil
}

func (r *RelayIP) Get() net.IP {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.ip
}

// Dynamic reports whether the address follows a DNS name.
func (r *RelayIP) Dynamic() bool { return r.host != "" }

// Run re-resolves the DNS name until ctx is cancelled. It does nothing for a fixed address.
func (r *RelayIP) Run(ctx context.Context, every time.Duration) {
	if r.host == "" {
		return
	}
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			ip, err := lookupPublicIPv4(r.host)
			if err != nil {
				slog.Warn("cannot refresh the public IP, keeping the old address", "err", err)
				continue
			}
			r.mu.Lock()
			old := r.ip
			changed := !ip.Equal(old)
			if changed {
				r.ip = ip
			}
			r.mu.Unlock()
			if changed {
				slog.Warn("public IP changed", "old", old.String(), "new", ip.String(),
					"note", "calls in progress need an ICE restart to move to the new address")
			}
		}
	}
}

func lookupPublicIPv4(host string) (net.IP, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	addrs, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return nil, fmt.Errorf("cannot resolve %q: %w", host, err)
	}
	var seen []string
	for _, a := range addrs {
		ip4 := a.IP.To4()
		if ip4 == nil {
			continue
		}
		seen = append(seen, ip4.String())
		if isPublicIPv4(ip4) {
			return ip4, nil
		}
	}
	return nil, fmt.Errorf("%q does not resolve to a public IPv4 address (got %v); "+
		"if your router answers with a LAN address for its own name, set turn.relay_ip explicitly", host, seen)
}

func isPublicIPv4(ip net.IP) bool {
	if ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsUnspecified() || ip.IsMulticast() {
		return false
	}
	return !(ip[0] == 100 && ip[1]&0xC0 == 64) // 100.64.0.0/10 (carrier-grade NAT)
}
