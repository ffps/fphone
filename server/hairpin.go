package main

import (
	"fmt"
	"log/slog"
	"net"
	"time"
)

// Hairpin check.
//
// When two phones both use the relay of this server, packets go from one relay port to the other
// through the PUBLIC address of the router and must come back to this machine (hairpin NAT).
// Routers differ: some return them with the public IP as the source (fine), some with their own
// LAN address (the TURN server then drops them), some do not return them at all.
// The check below sends one UDP packet to relay_ip:port and looks at where it comes from.

// probeHairpin returns the source IP of the packet that came back, or nil if nothing came back.
func probeHairpin(relayIP net.IP, port int) (net.IP, error) {
	rx, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: port})
	if err != nil {
		return nil, fmt.Errorf("cannot listen on UDP port %d: %w", port, err)
	}
	defer rx.Close()
	tx, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		return nil, err
	}
	defer tx.Close()

	payload := []byte("fphone-hairpin-probe")
	buf := make([]byte, 64)
	// A few tries: the first packet may be lost while the router creates its mapping.
	for i := 0; i < 3; i++ {
		if _, err := tx.WriteToUDP(payload, &net.UDPAddr{IP: relayIP, Port: port}); err != nil {
			return nil, err
		}
		rx.SetReadDeadline(time.Now().Add(time.Second))
		n, from, err := rx.ReadFromUDP(buf)
		if err == nil && string(buf[:n]) == string(payload) {
			return from.IP, nil
		}
	}
	return nil, nil
}

// resolveHairpin runs the check, logs a clear result and returns the source address that the
// TURN server must treat as relayIP (nil = no rewrite needed or wanted).
//
// turn.hairpin_src:  ""     only report
//
//	"auto" report and apply the workaround automatically
//	"<ip>" apply this address (and report if it does not match what was seen)
func resolveHairpin(cfg *Config, relayIP net.IP) net.IP {
	mode := cfg.Turn.HairpinSrc
	port := cfg.Turn.RelayPortMax // any port of the forwarded relay range that is still free

	seen, err := probeHairpin(relayIP, port)
	if err != nil {
		slog.Warn("hairpin check skipped", "err", err)
		if ip := net.ParseIP(mode); ip != nil {
			return ip
		}
		return nil
	}

	switch {
	case seen == nil:
		slog.Warn("hairpin check: no packet came back from the public address",
			"relay_ip", relayIP.String(), "udp_port", port,
			"meaning", "either the router does not loop packets back to its own public address (no hairpin), "+
				"or this UDP port is not forwarded to this machine. If port forwarding is correct, "+
				"two phones will NOT be able to talk through this relay")
		if ip := net.ParseIP(mode); ip != nil {
			return ip
		}
		return nil

	case seen.Equal(relayIP):
		slog.Info("hairpin check: OK, packets come back with the public IP, no workaround needed")
		return nil
	}

	// Packets come back, but with another source address.
	switch ip := net.ParseIP(mode); {
	case mode == "auto":
		slog.Info("hairpin check: packets come back from another address, workaround applied automatically",
			"seen_from", seen.String(), "treated_as", relayIP.String())
		return seen
	case ip != nil && ip.Equal(seen):
		slog.Info("hairpin check: packets come back from the configured hairpin_src, workaround applied",
			"seen_from", seen.String(), "treated_as", relayIP.String())
		return ip
	case ip != nil:
		slog.Warn("hairpin check: configured hairpin_src does not match what was seen",
			"configured", ip.String(), "seen_from", seen.String(),
			"hint", "set turn.hairpin_src: auto")
		return ip
	}
	slog.Warn("hairpin check: packets come back with a different source address, calls between two phones will fail",
		"seen_from", seen.String(), "relay_ip", relayIP.String(),
		"hint", "set turn.hairpin_src: auto in the config")
	return nil
}
