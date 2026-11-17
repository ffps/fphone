package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"encoding/base64"
	"fmt"
	"log/slog"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/pion/turn/v4"
)

const turnRealm = "fphone"

// TurnServer is the TURN relay built into the binary.
//
// Credentials follow the usual "TURN REST" scheme:
//   username   = "<expiry unix time>:<call id>:<user id>"
//   credential = base64(HMAC-SHA1(secret, username))
// The secret is random and lives only in memory (it changes at every start; credentials are
// short-lived and are handed out per call anyway). The auth handler also checks that the call
// is still active, so credentials stop working when the call ends.
type TurnServer struct {
	srv    *turn.Server
	cfg    *Config
	secret []byte
	allow  func(callID string, userID int64) bool
	ttl    time.Duration
}

func StartTurn(cfg *Config, relay *RelayIP, allow func(callID string, userID int64) bool) (*TurnServer, error) {
	hairpinIP := resolveHairpin(cfg, relay.Get()) // runs before the relay ports are used by the TURN server

	t := &TurnServer{
		cfg:    cfg,
		allow:  allow,
		ttl:    time.Duration(cfg.Turn.CredentialTTLMinutes) * time.Minute,
		secret: make([]byte, 32),
	}
	if _, err := rand.Read(t.secret); err != nil {
		return nil, err
	}

	addr := fmt.Sprintf("0.0.0.0:%d", cfg.Turn.ListenPort)
	udpL, err := net.ListenPacket("udp4", addr)
	if err != nil {
		return nil, fmt.Errorf("UDP %d: %w", cfg.Turn.ListenPort, err)
	}
	tcpL, err := net.Listen("tcp4", addr)
	if err != nil {
		udpL.Close()
		return nil, fmt.Errorf("TCP %d: %w", cfg.Turn.ListenPort, err)
	}

	genUDP, err := newDynGen(relay, cfg.Turn.RelayPortMin, cfg.Turn.RelayPortMax, hairpinIP)
	if err != nil {
		udpL.Close()
		tcpL.Close()
		return nil, err
	}
	genTCP, err := newDynGen(relay, cfg.Turn.RelayPortMin, cfg.Turn.RelayPortMax, hairpinIP)
	if err != nil {
		udpL.Close()
		tcpL.Close()
		return nil, err
	}

	srv, err := turn.NewServer(turn.ServerConfig{
		Realm:       turnRealm,
		AuthHandler: t.auth,
		PacketConnConfigs: []turn.PacketConnConfig{
			{PacketConn: udpL, RelayAddressGenerator: genUDP},
		},
		ListenerConfigs: []turn.ListenerConfig{
			{Listener: tcpL, RelayAddressGenerator: genTCP},
		},
	})
	if err != nil {
		udpL.Close()
		tcpL.Close()
		return nil, err
	}
	t.srv = srv
	return t, nil
}

func (t *TurnServer) Close() error {
	return t.srv.Close()
}

func (t *TurnServer) password(username string) string {
	m := hmac.New(sha1.New, t.secret)
	m.Write([]byte(username))
	return base64.StdEncoding.EncodeToString(m.Sum(nil))
}

func parseTurnUser(username string) (exp int64, callID string, userID int64, ok bool) {
	parts := strings.Split(username, ":")
	if len(parts) != 3 || len(parts[1]) != 32 {
		return 0, "", 0, false
	}
	exp, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil {
		return 0, "", 0, false
	}
	userID, err = strconv.ParseInt(parts[2], 10, 64)
	if err != nil {
		return 0, "", 0, false
	}
	return exp, parts[1], userID, true
}

// auth is called by the TURN server for every authenticated request (allocate, refresh, ...).
func (t *TurnServer) auth(username, realm string, src net.Addr) ([]byte, bool) {
	exp, callID, userID, ok := parseTurnUser(username)
	if !ok {
		slog.Warn("TURN: malformed username", "src", src.String())
		return nil, false
	}
	if time.Now().Unix() > exp {
		slog.Debug("TURN: credentials expired", "src", src.String())
		return nil, false
	}
	if !t.allow(callID, userID) {
		slog.Debug("TURN: call is not active", "call", callID, "user", userID, "src", src.String())
		return nil, false
	}
	return turn.GenerateAuthKey(username, realm, t.password(username)), true
}

// ICE returns the ICE configuration for one participant of one call.
func (t *TurnServer) ICE(callID string, userID int64) map[string]any {
	user := fmt.Sprintf("%d:%s:%d", time.Now().Add(t.ttl).Unix(), callID, userID)
	host := t.cfg.PublicHost
	port := t.cfg.Turn.ListenPort
	urls := []string{
		fmt.Sprintf("turn:%s:%d?transport=udp", host, port),
		fmt.Sprintf("turn:%s:%d?transport=tcp", host, port),
	}
	if t.cfg.Turn.ICEPolicy == "all" {
		urls = append(urls, fmt.Sprintf("stun:%s:%d", host, port))
	}
	return map[string]any{
		"servers": []map[string]any{
			{"urls": urls, "username": user, "credential": t.password(user)},
		},
		"policy": t.cfg.Turn.ICEPolicy,
		"ttl":    int(t.ttl.Seconds()),
	}
}

// ---------- relay address generator that follows the public IP ----------
//
// The relay address given to a client is the public IP of the router. With a dynamic public IP
// that address can change while the server runs, so a new inner generator is built whenever the
// current IP (RelayIP) differs from the one used so far. Existing allocations keep their old address.
//
// The wrapper also applies the hairpin workaround: when both phones use the relay of this same
// server, packets between the two relay ports go through the public IP of the router and come
// back. Some routers (Keenetic) return them with their own LAN address as the source, not the
// public IP. The TURN server looks up permissions by the source IP, so it would drop them.
// If hairpinFrom is set, that source address is rewritten to the public address of the allocation.

type dynGen struct {
	// The first inner generator. Only used for methods of the interface that this wrapper
	// does not override.
	turn.RelayAddressGenerator

	src         *RelayIP
	minPort     uint16
	maxPort     uint16
	hairpinFrom net.IP // nil = no rewrite

	mu    sync.Mutex
	cur   net.IP
	inner turn.RelayAddressGenerator
}

func newDynGen(src *RelayIP, minPort, maxPort int, hairpinFrom net.IP) (*dynGen, error) {
	g := &dynGen{src: src, minPort: uint16(minPort), maxPort: uint16(maxPort), hairpinFrom: hairpinFrom}
	ip := src.Get()
	inner, err := g.build(ip)
	if err != nil {
		return nil, err
	}
	g.RelayAddressGenerator = inner
	g.inner = inner
	g.cur = ip
	return g, nil
}

func (g *dynGen) build(ip net.IP) (turn.RelayAddressGenerator, error) {
	r := &turn.RelayAddressGeneratorPortRange{
		RelayAddress: ip,
		Address:      "0.0.0.0",
		MinPort:      g.minPort,
		MaxPort:      g.maxPort,
	}
	if err := r.Validate(); err != nil {
		return nil, err
	}
	return r, nil
}

// Validate is called by the TURN server at start; the inner generator is already validated.
func (g *dynGen) Validate() error { return nil }

func (g *dynGen) current() (turn.RelayAddressGenerator, net.IP) {
	ip := g.src.Get()
	g.mu.Lock()
	defer g.mu.Unlock()
	if !ip.Equal(g.cur) {
		if inner, err := g.build(ip); err != nil {
			slog.Error("TURN: cannot switch the relay address", "ip", ip.String(), "err", err)
		} else {
			g.inner, g.cur = inner, ip
			slog.Info("TURN: relay address switched", "ip", ip.String())
		}
	}
	return g.inner, g.cur
}

func (g *dynGen) AllocatePacketConn(network string, requestedPort int) (net.PacketConn, net.Addr, error) {
	inner, ip := g.current()
	c, a, err := inner.AllocatePacketConn(network, requestedPort)
	if err != nil {
		return nil, nil, err
	}
	if g.hairpinFrom != nil {
		c = &fixSrcConn{PacketConn: c, from: g.hairpinFrom, to: ip}
	}
	return c, a, nil
}

func (g *dynGen) AllocateConn(network string, requestedPort int) (net.Conn, net.Addr, error) {
	inner, _ := g.current()
	return inner.AllocateConn(network, requestedPort)
}

type fixSrcConn struct {
	net.PacketConn
	from, to net.IP
}

func (c *fixSrcConn) ReadFrom(p []byte) (int, net.Addr, error) {
	n, a, err := c.PacketConn.ReadFrom(p)
	if ua, ok := a.(*net.UDPAddr); ok && ua.IP.Equal(c.from) {
		a = &net.UDPAddr{IP: c.to, Port: ua.Port, Zone: ua.Zone}
	}
	return n, a, err
}
