package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"maps"
	"strconv"
	"sync"
	"time"
)

// Call states: calling -> ringing -> accepted -> active -> (ended).
// An ended call is removed from memory at once; its summary goes to the log.
const (
	stCalling  = "calling"  // created, the callee has not confirmed ringing yet
	stRinging  = "ringing"  // the callee device shows the incoming call
	stAccepted = "accepted" // the callee answered, WebRTC negotiation is running
	stActive   = "active"   // media is connected (a client sent call.connected)
)

const (
	maxSDPBytes       = 64 << 10
	maxCandidateBytes = 8 << 10
)

// ICEProvider hands out the ICE configuration (TURN credentials) for one participant of one call.
type ICEProvider interface {
	ICE(callID string, userID int64) map[string]any
}

// noICE is used when TURN is disabled: clients get no servers and may use direct paths only.
type noICE struct{}

func (noICE) ICE(string, int64) map[string]any {
	return map[string]any{"servers": []any{}, "policy": "all", "ttl": 0}
}

type Call struct {
	ID           string
	CallerID     int64
	CalleeID     int64
	CallerLogin  string
	CalleeLogin  string
	CallerName   string
	CalleeName   string
	CallerDevice int64 // device that started the call
	CalleeDevice int64 // device that answered (0 until accepted)
	Media        string
	State        string
	CreatedAt    time.Time
	AcceptedAt   time.Time
	ConnectedAt  time.Time

	pushed       map[int64]pushedTarget // devices that received a wake-up push
	pushAckToken string

	ringTimer   *time.Timer
	callerGrace *time.Timer // runs while the caller device has no WebSocket
	calleeGrace *time.Timer // runs while the callee device has no WebSocket
}

type pushedTarget struct {
	fcmToken string
	webPush  *WebPushTarget
}

type Calls struct {
	cfg     *Config
	store   *Store
	hub     *Hub
	ice     ICEProvider
	push    *FCM
	webPush *WebPush

	mu     sync.Mutex
	byID   map[string]*Call
	byUser map[int64]string // user ID -> ID of the call this user takes part in (one call per user)
}

func NewCalls(cfg *Config, store *Store, hub *Hub) *Calls {
	return &Calls{
		cfg:    cfg,
		store:  store,
		hub:    hub,
		ice:    noICE{},
		byID:   map[string]*Call{},
		byUser: map[int64]string{},
	}
}

// SetICE must be called before the server starts accepting connections.
func (m *Calls) SetICE(p ICEProvider) { m.ice = p }

// SetPush configures the available push transports before the server starts.
func (m *Calls) SetPush(fcm *FCM, web *WebPush) {
	m.push = fcm
	m.webPush = web
}

// CanPush reports whether at least one push transport is configured.
func (m *Calls) CanPush() bool { return m.push != nil || m.webPush != nil }

// AllowTurn tells the TURN server whether a user may use the relay for a call right now.
func (m *Calls) AllowTurn(callID string, userID int64) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	c := m.byID[callID]
	return c != nil && (c.CallerID == userID || c.CalleeID == userID)
}

// ---------- helpers ----------

func newCallID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

func stopTimer(t **time.Timer) {
	if *t != nil {
		(*t).Stop()
		*t = nil
	}
}

func (c *Call) role(userID int64) string {
	switch userID {
	case c.CallerID:
		return "caller"
	case c.CalleeID:
		return "callee"
	}
	return ""
}

// info describes the call from the point of view of one side ("caller" or "callee").
func (c *Call) info(role string) map[string]any {
	peerID, peerName := c.CalleeID, c.CalleeName
	if role == "callee" {
		peerID, peerName = c.CallerID, c.CallerName
	}
	return map[string]any{
		"id":    c.ID,
		"role":  role,
		"media": c.Media,
		"state": c.State,
		"peer":  map[string]any{"id": peerID, "name": peerName},
	}
}

func (m *Calls) incomingMsg(call *Call) map[string]any {
	return map[string]any{
		"t":    "call.incoming",
		"call": call.info("callee"),
		"ice":  m.ice.ICE(call.ID, call.CalleeID),
	}
}

// wake sends a wake-up push to the devices of the callee that have no open WebSocket.
// It returns true if at least one push is on its way (the pushes are sent in the background).
// A woken app connects its WebSocket and then receives call.incoming from onConnect.
// m.mu must be held.
func (m *Calls) wake(call *Call) bool {
	if !m.CanPush() {
		return false
	}
	targets := map[int64]pushedTarget{}
	if m.push != nil {
		fcmTargets, err := m.store.PushTargets(call.CalleeID)
		if err != nil {
			slog.Error("push: cannot read FCM tokens", "err", err)
		} else {
			for _, t := range fcmTargets {
				target := targets[t.DeviceID]
				target.fcmToken = t.Token
				targets[t.DeviceID] = target
			}
		}
	}
	if m.webPush != nil {
		webTargets, err := m.store.WebPushTargets(call.CalleeID)
		if err != nil {
			slog.Error("web push: cannot read subscriptions", "err", err)
		} else {
			for _, t := range webTargets {
				target := targets[t.DeviceID]
				copy := t
				target.webPush = &copy
				targets[t.DeviceID] = target
			}
		}
	}
	online := map[int64]bool{}
	for _, d := range m.hub.UserDevices(call.CalleeID) {
		online[d] = true
	}
	call.pushed = map[int64]pushedTarget{}
	for id, target := range targets {
		if !online[id] && (target.fcmToken != "" || target.webPush != nil) {
			call.pushed[id] = target
		}
	}
	if len(call.pushed) == 0 {
		return false
	}
	ttl := time.Duration(m.cfg.Calls.RingTimeoutSeconds) * time.Second
	data := map[string]string{
		"type":    "call.incoming",
		"call":    call.ID,
		"from_id": strconv.FormatInt(call.CallerID, 10),
		"media":   call.Media,
		"exp":     strconv.FormatInt(time.Now().Add(ttl).Unix(), 10),
	}
	if m.cfg.FCM.SendCallerName {
		data["from_name"] = call.CallerName
	}
	call.pushAckToken = newCallID()
	data["ack_token"] = call.pushAckToken
	payloadData := map[string]any{
		"type": "call.incoming", "call": call.ID, "ack_token": call.pushAckToken, "from_id": call.CallerID, "to_id": call.CalleeID,
		"media": call.Media, "started_at": call.CreatedAt.Unix(), "exp": time.Now().Add(ttl).Unix(),
		"title": "Входящий вызов", "body": "Откройте Кукуху, чтобы ответить",
	}
	if m.cfg.FCM.SendCallerName {
		payloadData["from_name"] = call.CallerName
		payloadData["body"] = "Вам звонит " + call.CallerName
	}
	payload, _ := json.Marshal(payloadData)
	go m.sendPushes(call.ID, call.CalleeLogin, maps.Clone(call.pushed), data, payload, ttl)
	return true
}

func (m *Calls) sendPushes(callID, login string, targets map[int64]pushedTarget, data map[string]string, payload []byte, ttl time.Duration) {
	ok := 0
	for deviceID, target := range targets {
		delivered := false
		if target.fcmToken != "" && m.push != nil {
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			messageID, err := m.push.SendWithResult(ctx, target.fcmToken, data, "call-"+callID, ttl)
			cancel()
			if err == nil {
				delivered = true
				slog.Debug("push delivery accepted by FCM", "call", callID, "recipient", login, "device_id", deviceID, "message_id", messageID, "result", "accepted")
			} else if errors.Is(err, ErrTokenInvalid) {
				slog.Warn("FCM token is not registered, removing it", "call", callID, "recipient", login, "device_id", deviceID, "error", err)
				m.store.ClearFCM(deviceID, target.fcmToken)
			} else {
				slog.Error("FCM push delivery failed", "call", callID, "recipient", login, "device_id", deviceID, "err", err)
			}
		}
		if target.webPush != nil && m.webPush != nil {
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			err := m.webPush.Send(ctx, *target.webPush, payload, ttl)
			cancel()
			if err == nil {
				delivered = true
				slog.Debug("push delivery accepted by Web Push", "call", callID, "recipient", login, "device_id", deviceID, "result", "accepted")
			} else if errors.Is(err, ErrWebPushGone) {
				slog.Warn("Web Push subscription is gone, removing it", "call", callID, "recipient", login, "device_id", deviceID)
				m.store.ClearWebPush(deviceID, target.webPush.Endpoint)
			} else {
				slog.Error("Web Push delivery failed", "call", callID, "recipient", login, "device_id", deviceID, "err", err)
			}
		}
		if delivered {
			ok++
		}
	}
	slog.Debug("push batch finished", "call", callID, "recipient", login, "ok", ok, "devices", len(targets))
	if ok == 0 {
		m.onPushFailed(callID)
	}
}


// ConfirmPushAck acknowledges that a recipient app received and processed the call push.
// The random token is scoped to the in-memory call and expires when the call ends.
func (m *Calls) ConfirmPushAck(callID, token string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	call := m.byID[callID]
	if call == nil || call.pushAckToken == "" || subtle.ConstantTimeCompare([]byte(call.pushAckToken), []byte(token)) != 1 {
		return false
	}
	if call.State == stCalling {
		call.State = stRinging
		m.hub.SendToDevice(call.CallerDevice, map[string]any{"t": "call.ringing", "call": call.ID})
	}
	return true
}

// onPushFailed ends a call that nobody can be reached for: no push was delivered
// and no device of the callee is connected.
func (m *Calls) onPushFailed(callID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	call := m.byID[callID]
	if call == nil || (call.State != stCalling && call.State != stRinging) {
		return
	}
	if len(m.hub.UserDevices(call.CalleeID)) == 0 {
		m.endLocked(call, "offline")
	}
}

// cancelPushes tells the devices that got a wake-up push (and are still not connected)
// that the call is over or was answered elsewhere, so they can remove the incoming call
// screen. m.mu must be held.
func (m *Calls) cancelPushes(call *Call, exceptDevice int64) {
	if len(call.pushed) == 0 {
		return
	}
	targets := map[int64]pushedTarget{}
	for d, target := range call.pushed {
		if d != exceptDevice && !m.hub.DeviceOnline(d) {
			targets[d] = target
		}
	}
	call.pushed = nil
	if len(targets) == 0 {
		return
	}
	id := call.ID
	data := map[string]string{"type": "call.cancelled", "call": id}
	payload, _ := json.Marshal(map[string]any{"type": "call.cancelled", "call": id})
	go func() {
		for deviceID, target := range targets {
			if target.fcmToken != "" && m.push != nil {
				ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
				_, err := m.push.SendWithResult(ctx, target.fcmToken, data, "call-"+id, time.Minute)
				cancel()
				if errors.Is(err, ErrTokenInvalid) {
					m.store.ClearFCM(deviceID, target.fcmToken)
				} else if err != nil {
					slog.Warn("FCM push cancel failed", "call", id, "device_id", deviceID, "err", err)
				}
			}
			if target.webPush != nil && m.webPush != nil {
				ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
				err := m.webPush.Send(ctx, *target.webPush, payload, time.Minute)
				cancel()
				if errors.Is(err, ErrWebPushGone) {
					m.store.ClearWebPush(deviceID, target.webPush.Endpoint)
				} else if err != nil {
					slog.Warn("Web Push cancel failed", "call", id, "device_id", deviceID, "err", err)
				}
			}
		}
	}()
}

// ---------- message dispatch ----------

// Handle processes one call-related message from a client.
func (m *Calls) Handle(c *Client, typ string, data []byte) {
	var req struct {
		Call       string          `json:"call"`
		To         int64           `json:"to"`
		Media      string          `json:"media"`
		Kind       string          `json:"kind"`
		SDP        string          `json:"sdp"`
		Candidate  json.RawMessage `json:"candidate"`
		ICERestart bool            `json:"ice_restart"`
		Enabled    bool            `json:"enabled"`
		Reason     string          `json:"reason"`
	}
	if err := json.Unmarshal(data, &req); err != nil {
		c.sendErr("bad_json", typ)
		return
	}
	switch typ {
	case "call.invite":
		m.invite(c, req.To, req.Media)
	case "call.signal":
		m.signal(c, req.Call, req.Kind, req.SDP, req.Candidate, req.ICERestart, req.Enabled)
	default:
		m.simple(c, typ, req.Call, req.Reason)
	}
}

func (m *Calls) invite(c *Client, to int64, media string) {
	const ref = "call.invite"
	if (media != "audio" && media != "video") || to <= 0 || to == c.sess.User.ID {
		c.sendErr("bad_request", ref)
		return
	}
	callee, err := m.store.UserByID(to)
	if err != nil || callee.Disabled {
		c.sendErr("unknown_user", ref)
		return
	}
	caller, err := m.store.UserByID(c.sess.User.ID)
	if err != nil {
		c.sendErr("internal", ref)
		return
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if _, inCall := m.byUser[c.sess.User.ID]; inCall {
		c.sendErr("already_in_call", ref)
		return
	}
	if len(m.byID) >= m.cfg.Calls.MaxActive {
		c.sendErr("server_busy", ref)
		return
	}

	c.hub.store.MarkActivity(c.sess.DeviceID)
	call := &Call{
		ID:           newCallID(),
		CallerID:     c.sess.User.ID,
		CalleeID:     to,
		CallerLogin:  c.sess.User.Login,
		CalleeLogin:  callee.Login,
		CallerName:   caller.Name,
		CalleeName:   callee.Name,
		CallerDevice: c.sess.DeviceID,
		Media:        media,
		State:        stCalling,
		CreatedAt:    time.Now(),
	}
	m.byID[call.ID] = call
	m.byUser[call.CallerID] = call.ID

	c.sendJSON(map[string]any{
		"t":    "call.created",
		"call": call.info("caller"),
		"ice":  m.ice.ICE(call.ID, call.CallerID),
	})

	if _, busy := m.byUser[to]; busy {
		m.endLocked(call, "busy")
		return
	}
	m.byUser[to] = call.ID

	devices := m.hub.UserDevices(to)
	pushed := m.wake(call) // also wakes the devices that are offline while others are online
	if len(devices) == 0 && !pushed {
		m.endLocked(call, "offline")
		return
	}
	msg := m.incomingMsg(call)
	for _, d := range devices {
		m.hub.SendToDevice(d, msg)
	}
	id := call.ID
	call.ringTimer = time.AfterFunc(time.Duration(m.cfg.Calls.RingTimeoutSeconds)*time.Second, func() {
		m.onRingTimeout(id)
	})
}

// simple handles messages that carry only a call ID.
func (m *Calls) simple(c *Client, typ, callID, rejectReason string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	call := m.byID[callID]
	if call == nil {
		c.sendErr("unknown_call", typ)
		return
	}
	uid := c.sess.User.ID
	role := call.role(uid)
	if role == "" {
		c.sendErr("forbidden", typ)
		return
	}
	pending := call.State == stCalling || call.State == stRinging

	switch typ {
	case "call.ringing":
		if role != "callee" || !pending {
			c.sendErr("bad_state", typ)
			return
		}
		if call.State == stCalling {
			call.State = stRinging
			m.hub.SendToDevice(call.CallerDevice, map[string]any{"t": "call.ringing", "call": call.ID})
		}

	case "call.accept":
		if role != "callee" || !pending {
			c.sendErr("bad_state", typ)
			return
		}
		m.store.MarkActivity(c.sess.DeviceID)
		call.State = stAccepted
		call.CalleeDevice = c.sess.DeviceID
		call.AcceptedAt = time.Now()
		stopTimer(&call.ringTimer)
		accepted := map[string]any{"t": "call.accepted", "call": call.ID}
		m.hub.SendToDevice(call.CallerDevice, accepted)
		m.hub.SendToDevice(call.CalleeDevice, accepted)
		// The other devices of the callee stop ringing.
		for _, d := range m.hub.UserDevices(call.CalleeID) {
			if d != call.CalleeDevice {
				m.hub.SendToDevice(d, map[string]any{"t": "call.ended", "call": call.ID, "reason": "answered_elsewhere"})
			}
		}
		m.cancelPushes(call, call.CalleeDevice)

	case "call.reject":
		if role != "callee" || !pending {
			c.sendErr("bad_state", typ)
			return
		}
		reason := "rejected"
		if rejectReason == "busy" {
			reason = "busy"
		}
		m.endLocked(call, reason)

	case "call.cancel":
		if role != "caller" || !pending {
			c.sendErr("bad_state", typ)
			return
		}
		m.endLocked(call, "cancelled")

	case "call.connected":
		switch call.State {
		case stAccepted:
			call.State = stActive
			call.ConnectedAt = time.Now()
		case stActive:
			// already known
		default:
			c.sendErr("bad_state", typ)
		}

	case "call.hangup":
		reason := "hangup"
		if pending {
			reason = "cancelled"
			if role == "callee" {
				reason = "rejected"
			}
		}
		m.endLocked(call, reason)

	case "ice.get":
		c.sendJSON(map[string]any{"t": "ice", "call": call.ID, "ice": m.ice.ICE(call.ID, uid)})

	default:
		c.sendErr("unknown_type", typ)
	}
}

// signal forwards WebRTC signaling and camera on/off state to the other side of the call.
// The server does not look inside the SDP.
func (m *Calls) signal(c *Client, callID, kind, sdp string, cand json.RawMessage, restart, enabled bool) {
	const ref = "call.signal"
	if kind != "offer" && kind != "answer" && kind != "candidate" && kind != "camera" {
		c.sendErr("bad_request", ref)
		return
	}
	if len(sdp) > maxSDPBytes || len(cand) > maxCandidateBytes {
		c.sendErr("too_large", ref)
		return
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	call := m.byID[callID]
	if call == nil {
		c.sendErr("unknown_call", ref)
		return
	}
	var target int64
	switch {
	case c.sess.User.ID == call.CallerID && c.sess.DeviceID == call.CallerDevice:
		target = call.CalleeDevice
	case c.sess.User.ID == call.CalleeID && c.sess.DeviceID == call.CalleeDevice:
		target = call.CallerDevice
	default:
		c.sendErr("forbidden", ref)
		return
	}
	if kind == "camera" && (call.State == stCalling || call.State == stRinging) && c.sess.User.ID == call.CallerID && c.sess.DeviceID == call.CallerDevice {
		if enabled {
			call.Media = "video"
		} else {
			call.Media = "audio"
		}
		m.hub.SendToUser(call.CalleeID, map[string]any{"t": "call.media", "call": call.ID, "media": call.Media})
		return
	}
	if (call.State != stAccepted && call.State != stActive) || target == 0 {
		c.sendErr("bad_state", ref)
		return
	}
	msg := map[string]any{"t": "call.signal", "call": call.ID, "kind": kind}
	if sdp != "" {
		msg["sdp"] = sdp
	}
	if len(cand) > 0 {
		msg["candidate"] = cand
	}
	if restart {
		msg["ice_restart"] = true
	}
	if kind == "camera" {
		msg["enabled"] = enabled
	}
	m.hub.SendToDevice(target, msg)
}

// ---------- ending a call ----------

// endLocked ends a call. m.mu must be held.
func (m *Calls) endLocked(call *Call, reason string) {
	stopTimer(&call.ringTimer)
	stopTimer(&call.callerGrace)
	stopTimer(&call.calleeGrace)
	if call.State == stCalling || call.State == stRinging {
		m.cancelPushes(call, 0)
	}
	delete(m.byID, call.ID)
	if m.byUser[call.CallerID] == call.ID {
		delete(m.byUser, call.CallerID)
	}
	// If the callee was busy with another call, it never knew about this one: do not notify it.
	calleeKnows := m.byUser[call.CalleeID] == call.ID
	if calleeKnows {
		delete(m.byUser, call.CalleeID)
	}

	msg := map[string]any{"t": "call.ended", "call": call.ID, "reason": reason}
	m.hub.SendToDevice(call.CallerDevice, msg)
	if call.CalleeDevice != 0 {
		m.hub.SendToDevice(call.CalleeDevice, msg)
	} else if calleeKnows {
		m.hub.SendToUser(call.CalleeID, msg)
	}

	// Keep call details in the server log only; call history belongs to each client.
	now := time.Now()
	ring := now.Sub(call.CreatedAt)
	if !call.AcceptedAt.IsZero() {
		ring = call.AcceptedAt.Sub(call.CreatedAt)
	}
	var talk time.Duration
	if !call.ConnectedAt.IsZero() {
		talk = now.Sub(call.ConnectedAt)
	}
	slog.Info("call", "id", call.ID, "from", call.CallerLogin, "to", call.CalleeLogin,
		"media", call.Media, "reason", reason, "ring_s", int(ring.Seconds()), "talk_s", int(talk.Seconds()))
}

func (m *Calls) onRingTimeout(id string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	call := m.byID[id]
	if call == nil || (call.State != stCalling && call.State != stRinging) {
		return
	}
	m.endLocked(call, "no_answer")
}

// ---------- WebSocket connect / disconnect ----------

// callsFor lists the calls this device is part of, for the "calls" field of auth.ok.
func (m *Calls) callsFor(c *Client) []any {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []any{}
	uid := c.sess.User.ID
	call := m.byID[m.byUser[uid]]
	if call == nil {
		return out
	}
	role := call.role(uid)
	mine := (role == "caller" && c.sess.DeviceID == call.CallerDevice) ||
		(role == "callee" && c.sess.DeviceID == call.CalleeDevice)
	if !mine {
		return out
	}
	info := call.info(role)
	info["ice"] = m.ice.ICE(call.ID, uid)
	return append(out, info)
}

// onConnect is called after auth.ok was queued for a new WebSocket client.
//
// wakeCall is the call ID from the push message that woke the app (empty if the app was not
// woken by a push). If that call is already over or was answered on another device, the app
// is told, so it can remove the incoming call screen.
func (m *Calls) onConnect(c *Client, wakeCall string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	uid := c.sess.User.ID
	call := m.byID[m.byUser[uid]]
	if wakeCall != "" {
		relevant := call != nil && call.ID == wakeCall && uid == call.CalleeID &&
			(call.State == stCalling || call.State == stRinging || c.sess.DeviceID == call.CalleeDevice)
		if !relevant {
			c.sendJSON(map[string]any{"t": "call.ended", "call": wakeCall, "reason": "gone"})
		}
	}
	if call == nil {
		return
	}
	if uid == call.CallerID && c.sess.DeviceID == call.CallerDevice {
		stopTimer(&call.callerGrace)
	}
	if uid == call.CalleeID {
		if c.sess.DeviceID == call.CalleeDevice {
			stopTimer(&call.calleeGrace)
		}
		// A device that connects while the call is still ringing (for example, woken up by a push)
		// gets the incoming call now.
		if call.State == stCalling || call.State == stRinging {
			c.sendJSON(m.incomingMsg(call))
		}
	}
}

// onDisconnect is called after a WebSocket client was removed from the hub.
// A lost WebSocket does not end a call at once: media does not depend on it. The call
// ends only if that device does not come back within calls.reconnect_grace_seconds.
func (m *Calls) onDisconnect(c *Client) {
	deviceID := c.sess.DeviceID
	if m.hub.DeviceOnline(deviceID) {
		return // the same device already has another connection
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	uid := c.sess.User.ID
	call := m.byID[m.byUser[uid]]
	if call == nil {
		return
	}
	grace := time.Duration(m.cfg.Calls.ReconnectGraceSeconds) * time.Second
	id := call.ID
	switch {
	case uid == call.CallerID && deviceID == call.CallerDevice:
		stopTimer(&call.callerGrace)
		call.callerGrace = time.AfterFunc(grace, func() { m.onGraceExpired(id, deviceID) })
	case uid == call.CalleeID && deviceID == call.CalleeDevice:
		stopTimer(&call.calleeGrace)
		call.calleeGrace = time.AfterFunc(grace, func() { m.onGraceExpired(id, deviceID) })
	}
}

func (m *Calls) onGraceExpired(id string, deviceID int64) {
	if m.hub.DeviceOnline(deviceID) {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	call := m.byID[id]
	if call == nil || (deviceID != call.CallerDevice && deviceID != call.CalleeDevice) {
		return
	}
	reason := "timeout"
	if call.State == stCalling || call.State == stRinging {
		reason = "cancelled"
	}
	m.endLocked(call, reason)
}
