package main

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	protoVersion = 1
	authWait     = 5 * time.Second  // the first client message must be "auth"
	readWait     = 70 * time.Second // silence longer than this is treated as a broken connection
	pingEvery    = 25 * time.Second // server WS pings keep the idle timer of the Keenetic proxy (60 s) alive
	revalEvery   = 60 * time.Second // how fast a token revoke or user disable from the CLI takes effect
)

type Hub struct {
	store    *Store
	cfg      *Config
	upgrader websocket.Upgrader
	calls    *Calls

	mu      sync.Mutex
	clients map[*Client]struct{}
}

type Client struct {
	hub  *Hub
	conn *websocket.Conn
	sess Session
	send chan []byte
	done chan struct{}
	once sync.Once
}

// SetCalls connects the call manager. It must be called before the server starts.
func (h *Hub) SetCalls(c *Calls) { h.calls = c }

func NewHub(store *Store, cfg *Config) *Hub {
	h := &Hub{store: store, cfg: cfg, clients: map[*Client]struct{}{}}
	h.upgrader = websocket.Upgrader{
		ReadBufferSize:  4096,
		WriteBufferSize: 4096,
		CheckOrigin:     h.checkOrigin,
	}
	return h
}

// Native apps do not send an Origin header, so they pass. Browsers need an allowed Origin.
// The Host header is not compared: the Keenetic proxy replaces it with the address of the RPi.
func (h *Hub) checkOrigin(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}
	for _, o := range h.cfg.AllowedOrigins {
		if o == "*" || strings.EqualFold(o, origin) {
			return true
		}
	}
	slog.Warn("WS: Origin is not allowed (add it to allowed_origins)", "origin", origin)
	return false
}

func rejectWS(conn *websocket.Conn, code int, reason string) {
	deadline := time.Now().Add(2 * time.Second)
	b, _ := json.Marshal(map[string]any{"t": "error", "code": reason})
	conn.SetWriteDeadline(deadline)
	conn.WriteMessage(websocket.TextMessage, b)
	conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(code, reason), deadline)
	conn.Close()
}

func (h *Hub) ServeWS(w http.ResponseWriter, r *http.Request) {
	conn, err := h.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return // Upgrade has already replied to the client
	}
	conn.SetReadLimit(128 << 10)

	// 1. The first message must be "auth". The token is not put in the URL,
	//    so it does not end up in proxy logs.
	conn.SetReadDeadline(time.Now().Add(authWait))
	_, data, err := conn.ReadMessage()
	if err != nil {
		conn.Close()
		return
	}
	var a struct {
		T     string `json:"t"`
		Token string `json:"token"`
		Proto int    `json:"proto"`
		Call  string `json:"call"` // optional: the call the app was woken up for by a push message
	}
	if json.Unmarshal(data, &a) != nil || a.T != "auth" {
		rejectWS(conn, 4400, "bad_request")
		return
	}
	if a.Proto != protoVersion {
		rejectWS(conn, 4426, "unsupported_proto")
		return
	}
	sess, err := h.store.Authenticate(a.Token)
	if err != nil {
		rejectWS(conn, 4401, "unauthorized")
		return
	}

	c := &Client{
		hub:  h,
		conn: conn,
		sess: *sess,
		send: make(chan []byte, 32),
		done: make(chan struct{}),
	}
	conn.SetPongHandler(func(string) error {
		conn.SetReadDeadline(time.Now().Add(readWait))
		return nil
	})
	go c.writeLoop()

	first := h.add(c)
	slog.Info("WS connected", "user", sess.User.Login, "device", sess.DeviceUID)
	calls := []any{}
	if h.calls != nil {
		calls = h.calls.callsFor(c)
	}
	c.sendJSON(map[string]any{
		"t":      "auth.ok",
		"proto":  protoVersion,
		"user":   sess.User,
		"online": h.onlineIDs(),
		"calls":  calls, // calls this device takes part in (for resuming after a reconnect)
	})
	if h.calls != nil {
		if len(a.Call) > 64 {
			a.Call = ""
		}
		h.calls.onConnect(c, a.Call)
	}
	if first {
		h.broadcastPresence(sess.User.ID, true)
	}

	c.readLoop()

	c.close(websocket.CloseNormalClosure, "")
	last := h.remove(c)
	h.store.MarkSeen(sess.DeviceID)
	if last {
		h.broadcastPresence(sess.User.ID, false)
	}
	if h.calls != nil {
		h.calls.onDisconnect(c)
	}
	slog.Info("WS disconnected", "user", sess.User.Login, "device", sess.DeviceUID)
}

func (c *Client) readLoop() {
	for {
		c.conn.SetReadDeadline(time.Now().Add(readWait))
		_, data, err := c.conn.ReadMessage()
		if err != nil {
			return
		}
		var m struct {
			T string `json:"t"`
		}
		if err := json.Unmarshal(data, &m); err != nil {
			c.sendJSON(map[string]any{"t": "error", "code": "bad_json"})
			continue
		}
		switch m.T {
		case "ping":
			c.sendJSON(map[string]any{"t": "pong"})
		case "call.invite", "call.ringing", "call.accept", "call.reject", "call.cancel",
			"call.signal", "call.connected", "call.hangup", "ice.get":
			if c.hub.calls == nil {
				c.sendErr("calls_disabled", m.T)
			} else {
				c.hub.calls.Handle(c, m.T, data)
			}
		default:
			c.sendJSON(map[string]any{"t": "error", "code": "unknown_type", "type": truncRunes(m.T, 32)})
		}
	}
}

func (c *Client) writeLoop() {
	t := time.NewTicker(pingEvery)
	defer t.Stop()
	for {
		select {
		case b := <-c.send:
			c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.conn.WriteMessage(websocket.TextMessage, b); err != nil {
				c.close(0, "")
				return
			}
		case <-t.C:
			if err := c.conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second)); err != nil {
				c.close(0, "")
				return
			}
		case <-c.done:
			return
		}
	}
}

func (c *Client) sendJSON(v any) {
	b, err := json.Marshal(v)
	if err != nil {
		return
	}
	select {
	case c.send <- b:
	case <-c.done:
	default:
		slog.Warn("WS: client is too slow, disconnecting", "user", c.sess.User.Login)
		c.close(websocket.ClosePolicyViolation, "slow_consumer")
	}
}

// sendErr sends an error message. "ref" is the type of the message that caused it.
func (c *Client) sendErr(code, ref string) {
	c.sendJSON(map[string]any{"t": "error", "code": code, "ref": ref})
}

// close can be called from any goroutine, any number of times.
func (c *Client) close(code int, reason string) {
	c.once.Do(func() {
		close(c.done)
		if code != 0 {
			c.conn.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(code, reason), time.Now().Add(time.Second))
		}
		c.conn.Close()
	})
}

// ---------- client registry and presence ----------

func (h *Hub) add(c *Client) (firstOfUser bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	firstOfUser = true
	for o := range h.clients {
		if o.sess.User.ID == c.sess.User.ID {
			firstOfUser = false
			break
		}
	}
	h.clients[c] = struct{}{}
	return firstOfUser
}

func (h *Hub) remove(c *Client) (lastOfUser bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(h.clients, c)
	for o := range h.clients {
		if o.sess.User.ID == c.sess.User.ID {
			return false
		}
	}
	return true
}

func (h *Hub) snapshot() []*Client {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := make([]*Client, 0, len(h.clients))
	for c := range h.clients {
		out = append(out, c)
	}
	return out
}

func (h *Hub) OnlineSet() map[int64]bool {
	set := map[int64]bool{}
	for _, c := range h.snapshot() {
		set[c.sess.User.ID] = true
	}
	return set
}

func (h *Hub) onlineIDs() []int64 {
	set := h.OnlineSet()
	out := make([]int64, 0, len(set))
	for id := range set {
		out = append(out, id)
	}
	return out
}

func (h *Hub) broadcastPresence(userID int64, online bool) {
	msg := map[string]any{"t": "presence", "user": userID, "online": online}
	for _, c := range h.snapshot() {
		if c.sess.User.ID != userID {
			c.sendJSON(msg)
		}
	}
}

// SendToDevice sends a message to every open connection of a device.
func (h *Hub) SendToDevice(deviceID int64, v any) {
	for _, c := range h.snapshot() {
		if c.sess.DeviceID == deviceID {
			c.sendJSON(v)
		}
	}
}

// SendToUser sends a message to every open connection of a user.
func (h *Hub) SendToUser(userID int64, v any) {
	for _, c := range h.snapshot() {
		if c.sess.User.ID == userID {
			c.sendJSON(v)
		}
	}
}

// UserDevices returns the IDs of the devices of a user that have an open connection.
func (h *Hub) UserDevices(userID int64) []int64 {
	seen := map[int64]bool{}
	var out []int64
	for _, c := range h.snapshot() {
		if c.sess.User.ID == userID && !seen[c.sess.DeviceID] {
			seen[c.sess.DeviceID] = true
			out = append(out, c.sess.DeviceID)
		}
	}
	return out
}

func (h *Hub) DeviceOnline(deviceID int64) bool {
	for _, c := range h.snapshot() {
		if c.sess.DeviceID == deviceID {
			return true
		}
	}
	return false
}

// KickDevice closes all connections of a device (logout or token revoke).
func (h *Hub) KickDevice(deviceID int64) {
	for _, c := range h.snapshot() {
		if c.sess.DeviceID == deviceID {
			c.close(4401, "unauthorized")
		}
	}
}

func (h *Hub) CloseAll() {
	for _, c := range h.snapshot() {
		c.close(websocket.CloseGoingAway, "server_shutdown")
	}
}

// Run checks open connections once a minute: a token revoke, a user disable or an expired
// token done from the CLI takes effect without restarting the server.
func (h *Hub) Run(ctx context.Context) {
	t := time.NewTicker(revalEvery)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			for _, c := range h.snapshot() {
				if !h.store.Revalidate(c.sess.DeviceID) {
					slog.Info("WS: session is no longer valid, disconnecting",
						"user", c.sess.User.Login, "device", c.sess.DeviceUID)
					c.close(4401, "unauthorized")
				}
			}
		}
	}
}
