package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"image/jpeg"
	"io"
	"io/fs"
	"log/slog"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type Server struct {
	cfg       *Config
	store     *Store
	hub       *Hub
	limiter   *Limiter
	dummyHash string
	hashSem   chan struct{} // at most 2 argon2 runs at the same time (about 32 MiB of memory each)
}

func NewServer(cfg *Config, store *Store, hub *Hub) (*Server, error) {
	dh, err := HashPassword("fphone-dummy-password")
	if err != nil {
		return nil, err
	}
	return &Server{
		cfg:       cfg,
		store:     store,
		hub:       hub,
		limiter:   NewLimiter(),
		dummyHash: dh,
		hashSem:   make(chan struct{}, 2),
	}, nil
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/ping", s.rest(s.handlePing))
	mux.HandleFunc("POST /api/v1/login", s.rest(s.handleLogin))
	mux.HandleFunc("POST /api/v1/logout", s.authed(s.handleLogout))
	mux.HandleFunc("PUT /api/v1/device", s.authed(s.handleDevice))
	mux.HandleFunc("GET /api/v1/push/vapid-public-key", s.authed(s.handleWebPushKey))
	mux.HandleFunc("PUT /api/v1/push/subscription", s.authed(s.handleWebPushSubscription))
	mux.HandleFunc("DELETE /api/v1/push/subscription", s.authed(s.handleWebPushDelete))
	mux.HandleFunc("GET /api/v1/contacts", s.authed(s.handleContacts))
	mux.HandleFunc("POST /api/v1/messages", s.authed(s.handleMessagePush))
	mux.HandleFunc("POST /api/v1/messages/ack", s.rest(s.handleMessageAck))
	mux.HandleFunc("POST /api/v1/calls/ack", s.rest(s.handleCallPushAck))
	mux.HandleFunc("GET /api/v1/users/{id}/avatar", s.authed(s.handleAvatar))
	mux.HandleFunc("GET /api/v1/me", s.authed(s.handleMeGet))
	mux.HandleFunc("PUT /api/v1/me", s.authed(s.handleMe))
	mux.HandleFunc("PUT /api/v1/me/avatar", s.authed(s.handleUploadAvatar))
	mux.HandleFunc("DELETE /api/v1/me/avatar", s.authed(s.handleDeleteAvatar))
	mux.HandleFunc("GET /ws", s.hub.ServeWS)
	mux.HandleFunc("/", s.serveStatic)
	return mux
}

// serveStatic serves public files from data_dir/web. API paths are never
// treated as static files, even when an API route does not exist.
func (s *Server) serveStatic(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path == "/api" || strings.HasPrefix(r.URL.Path, "/api/") {
		http.NotFound(w, r)
		return
	}
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	rel := strings.Trim(r.URL.Path, "/")
	if rel == "" {
		rel = "index.html"
	}
	// Reject traversal and malformed paths before joining with the web root.
	if !fs.ValidPath(rel) || strings.Contains(rel, `\`) {
		http.NotFound(w, r)
		return
	}
	webRoot := filepath.Join(s.cfg.DataDir, "web")
	root, err := os.OpenRoot(webRoot)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer root.Close()

	f, err := root.Open(rel)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	info, err := f.Stat()
	if err != nil {
		f.Close()
		http.NotFound(w, r)
		return
	}
	if info.IsDir() {
		f.Close()
		if !strings.HasSuffix(r.URL.Path, "/") {
			location := r.URL.EscapedPath() + "/"
			if r.URL.RawQuery != "" {
				location += "?" + r.URL.RawQuery
			}
			http.Redirect(w, r, location, http.StatusMovedPermanently)
			return
		}
		rel = path.Join(rel, "index.html")
		f, err = root.Open(rel)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		info, err = f.Stat()
		if err != nil || !info.Mode().IsRegular() {
			f.Close()
			http.NotFound(w, r)
			return
		}
	} else if !info.Mode().IsRegular() {
		f.Close()
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	fullPath := rel

	contentType := staticContentType(fullPath)
	w.Header().Set("Content-Type", contentType)
	if s.cfg.StaticCacheSeconds == 0 {
		w.Header().Set("Cache-Control", "no-cache")
	} else {
		w.Header().Set("Cache-Control", "public, max-age="+strconv.Itoa(s.cfg.StaticCacheSeconds))
	}
	http.ServeContent(w, r, path.Base(fullPath), info.ModTime(), f)
}

func staticContentType(name string) string {
	switch strings.ToLower(filepath.Ext(name)) {
	case ".html":
		return "text/html; charset=utf-8"
	case ".css":
		return "text/css; charset=utf-8"
	case ".js":
		return "application/javascript; charset=utf-8"
	case ".json":
		return "application/json; charset=utf-8"
	case ".txt":
		return "text/plain; charset=utf-8"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	case ".svg":
		return "image/svg+xml"
	case ".ico":
		return "image/x-icon"
	case ".webmanifest":
		return "application/manifest+json"
	default:
		return "application/octet-stream"
	}
}

// ---------- helpers ----------

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

// Error messages are in English for logs and developers.
// The client decides what text (in which language) to show to the user, based on "code".
func writeErr(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, map[string]any{"error": map[string]string{"code": code, "msg": msg}})
}

func serverErr(w http.ResponseWriter, err error) {
	slog.Error("internal error", "err", err)
	writeErr(w, http.StatusInternalServerError, "internal", "internal server error")
}

func decodeJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid JSON")
		return false
	}
	return true
}

// rest limits time and body size for normal (non-WebSocket) requests.
func (s *Server) rest(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		rc := http.NewResponseController(w)
		_ = rc.SetReadDeadline(time.Now().Add(15 * time.Second))
		_ = rc.SetWriteDeadline(time.Now().Add(15 * time.Second))
		limit := int64(64 << 10)
		if r.URL.Path == "/api/v1/me/avatar" {
			limit = maxAvatarBytes
		}
		r.Body = http.MaxBytesReader(w, r.Body, limit)
		w.Header().Set("Cache-Control", "no-store")
		h(w, r)
	}
}

func (s *Server) authed(h func(http.ResponseWriter, *http.Request, *Session)) http.HandlerFunc {
	return s.rest(func(w http.ResponseWriter, r *http.Request) {
		tok, _ := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		sess, err := s.store.Authenticate(tok)
		if err != nil {
			if errors.Is(err, ErrNotFound) {
				writeErr(w, http.StatusUnauthorized, "unauthorized", "login required")
			} else {
				serverErr(w, err)
			}
			return
		}
		h(w, r, sess)
	})
}

func (s *Server) checkPassword(pw, hash string) bool {
	s.hashSem <- struct{}{}
	defer func() { <-s.hashSem }()
	return CheckPassword(pw, hash)
}

func validDeviceID(id string) bool {
	if id == "" || len(id) > 64 {
		return false
	}
	for _, c := range id {
		ok := c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.'
		if !ok {
			return false
		}
	}
	return true
}

func truncRunes(s string, n int) string {
	r := []rune(s)
	if len(r) > n {
		return string(r[:n])
	}
	return s
}

// ---------- handlers ----------

func (s *Server) handlePing(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "proto": protoVersion, "version": version, "time": time.Now().Unix(),
	})
}

type loginReq struct {
	Login    string `json:"login"`
	Password string `json:"password"`
	Device   struct {
		ID       string `json:"id"`
		Name     string `json:"name"`
		FCMToken string `json:"fcm_token"`
	} `json:"device"`
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req loginReq
	if !decodeJSON(w, r, &req) {
		return
	}
	login := strings.TrimSpace(req.Login)
	if !validLogin(login) || req.Password == "" || len(req.Password) > 256 || !validDeviceID(req.Device.ID) {
		writeErr(w, http.StatusBadRequest, "bad_request",
			"login must be an ASCII identifier (1-64 characters); password and device.id are required")
		return
	}
	if len(req.Device.FCMToken) > 4096 {
		writeErr(w, http.StatusBadRequest, "bad_request", "fcm_token is too long")
		return
	}
	name := truncRunes(strings.TrimSpace(req.Device.Name), 64)
	if name == "" {
		name = "device"
	}

	key := strings.ToLower(login)
	if wait, ok := s.limiter.Check(key); !ok {
		w.Header().Set("Retry-After", strconv.Itoa(int(wait.Seconds())+1))
		writeErr(w, http.StatusTooManyRequests, "rate_limited", "too many login attempts, try again later")
		return
	}

	user, hash, err := s.store.UserByLogin(login)
	if err != nil && !errors.Is(err, ErrNotFound) {
		serverErr(w, err)
		return
	}
	found := err == nil && !user.Disabled
	if !found {
		hash = s.dummyHash // same response time for unknown and disabled logins
	}
	passOK := s.checkPassword(req.Password, hash)
	if !found || !passOK {
		s.limiter.Fail(key)
		slog.Warn("login failed", "login", login, "remote", r.RemoteAddr)
		writeErr(w, http.StatusUnauthorized, "invalid_credentials", "invalid login or password")
		return
	}
	s.limiter.Reset(key)

	token, exp, err := s.store.UpsertDevice(user.ID, req.Device.ID, name, req.Device.FCMToken)
	if err != nil {
		serverErr(w, err)
		return
	}
	slog.Info("login", "user", user.Login, "device", name)
	writeJSON(w, http.StatusOK, map[string]any{"token": token, "expires_at": exp, "user": user})
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request, sess *Session) {
	if err := s.store.DeleteDevice(sess.DeviceID); err != nil && !errors.Is(err, ErrNotFound) {
		serverErr(w, err)
		return
	}
	s.hub.KickDevice(sess.DeviceID)
	slog.Info("logout", "user", sess.User.Login, "device", sess.DeviceUID)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) handleDevice(w http.ResponseWriter, r *http.Request, sess *Session) {
	var req struct {
		FCMToken string `json:"fcm_token"`
	}
	if !decodeJSON(w, r, &req) {
		return
	}
	if len(req.FCMToken) > 4096 {
		writeErr(w, http.StatusBadRequest, "bad_request", "fcm_token is too long")
		return
	}
	if err := s.store.SetFCM(sess.DeviceID, req.FCMToken); err != nil {
		serverErr(w, err)
		return
	}
	slog.Debug("push token registered", "recipient", sess.User.Login, "device_id", sess.DeviceID, "token_length", len(req.FCMToken))
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) handleWebPushKey(w http.ResponseWriter, r *http.Request, sess *Session) {
	if s.cfg.WebPush.PublicKey == "" {
		writeJSON(w, http.StatusOK, map[string]any{"enabled": false})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"enabled": true, "public_key": s.cfg.WebPush.PublicKey})
}

func (s *Server) handleWebPushSubscription(w http.ResponseWriter, r *http.Request, sess *Session) {
	if s.cfg.WebPush.PublicKey == "" {
		writeErr(w, http.StatusServiceUnavailable, "web_push_disabled", "Web Push is not configured")
		return
	}
	var sub webPushSubscription
	if !decodeJSON(w, r, &sub) {
		return
	}
	if len(sub.Endpoint) > 4096 || len(sub.Keys.P256dh) > 256 || len(sub.Keys.Auth) > 256 {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid Web Push subscription")
		return
	}
	if err := validateWebPushSubscription(sub); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	if err := s.store.SetWebPush(sess.DeviceID, sub.Endpoint, sub.Keys.P256dh, sub.Keys.Auth); err != nil {
		serverErr(w, err)
		return
	}
	slog.Info("Web Push subscription registered", "user", sess.User.Login, "device_id", sess.DeviceID)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) handleWebPushDelete(w http.ResponseWriter, r *http.Request, sess *Session) {
	if err := s.store.DeleteWebPush(sess.DeviceID); err != nil {
		serverErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) handleContacts(w http.ResponseWriter, r *http.Request, sess *Session) {
	users, err := s.store.Contacts(sess.User.ID)
	if err != nil {
		serverErr(w, err)
		return
	}
	online := s.hub.OnlineSet()
	canPush := s.hub.calls != nil && s.hub.calls.CanPush()
	// "online" means an open WebSocket. "reachable" also counts users who can be woken up by a push
	// message (the app is closed or in the background): a call to them is possible.
	type contact struct {
		ID        int64  `json:"id"`
		Login     string `json:"login"`
		Name      string `json:"name"`
		Online    bool   `json:"online"`
		Reachable bool   `json:"reachable"`
		Avatar    int64  `json:"avatar"`
		LastSeen  int64  `json:"last_seen"`
	}
	out := make([]contact, 0, len(users))
	for _, u := range users {
		on := online[u.ID]
		out = append(out, contact{ID: u.ID, Login: u.Login, Name: u.Name, Online: on,
			Reachable: on || (canPush && u.HasPush), Avatar: s.avatarVersionForLogin(u.Login), LastSeen: u.LastSeen})
	}
	writeJSON(w, http.StatusOK, map[string]any{"contacts": out})
}

// handleMessagePush sends a short text notification to all push-enabled devices of a contact.
func (s *Server) handleMessagePush(w http.ResponseWriter, r *http.Request, sess *Session) {
	var req struct {
		To   int64  `json:"to"`
		Text string `json:"text"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.To <= 0 || req.To == sess.User.ID ||
		strings.TrimSpace(req.Text) == "" || len([]rune(req.Text)) > 1000 || len(req.Text) > 3500 {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid recipient or message")
		return
	}
	recipient, err := s.store.UserByID(req.To)
	if err != nil || recipient.Disabled {
		writeErr(w, http.StatusNotFound, "unknown_user", "contact not found")
		return
	}

	var fcmTargets []PushTarget
	var webTargets []WebPushTarget
	if s.hub.calls != nil && s.hub.calls.push != nil {
		fcmTargets, err = s.store.PushTargets(recipient.ID)
		if err != nil {
			serverErr(w, err)
			return
		}
	}
	if s.hub.calls != nil && s.hub.calls.webPush != nil {
		webTargets, err = s.store.WebPushTargets(recipient.ID)
		if err != nil {
			serverErr(w, err)
			return
		}
	}
	if len(fcmTargets)+len(webTargets) == 0 {
		writeErr(w, http.StatusServiceUnavailable, "no_push_devices", "contact has no devices with push notifications enabled")
		return
	}

	// Prefer FCM if a device happens to have both transports registered.
	fcmDevices := make(map[int64]bool, len(fcmTargets))
	for _, target := range fcmTargets {
		fcmDevices[target.DeviceID] = true
	}
	filteredWeb := webTargets[:0]
	for _, target := range webTargets {
		if !fcmDevices[target.DeviceID] {
			filteredWeb = append(filteredWeb, target)
		}
	}
	webTargets = filteredWeb

	title := sess.User.Name
	if title == "" {
		title = sess.User.Login
	}
	now := time.Now().UnixMilli()
	messageID, _ := NewToken()
	ackTokens := make(map[int64]string, len(fcmTargets)+len(webTargets))
	for _, target := range fcmTargets {
		ackTokens[target.DeviceID], _ = NewToken()
	}
	for _, target := range webTargets {
		ackTokens[target.DeviceID], _ = NewToken()
	}
	if err := s.store.CreateMessageDeliveries(messageID, sess.User.ID, ackTokens); err != nil {
		serverErr(w, err)
		return
	}

	type pushResult struct {
		deviceID int64
		token    string
		endpoint string
		err      error
	}
	total := len(fcmTargets) + len(webTargets)
	ctx, cancel := context.WithTimeout(r.Context(), 12*time.Second)
	defer cancel()
	results := make(chan pushResult, total)
	for _, target := range fcmTargets {
		go func(t PushTarget) {
			data := map[string]string{
				"type": "message", "title": title, "text": req.Text,
				"time": strconv.FormatInt(now, 10), "message_id": messageID,
				"ack_token": ackTokens[t.DeviceID],
			}
			_, sendErr := s.hub.calls.push.SendWithResult(ctx, t.Token, data, "", time.Minute)
			results <- pushResult{deviceID: t.DeviceID, token: t.Token, err: sendErr}
		}(target)
	}
	for _, target := range webTargets {
		go func(t WebPushTarget) {
			payload, marshalErr := json.Marshal(map[string]any{
				"type": "message", "title": title, "body": req.Text, "text": req.Text, "time": now,
				"message_id": messageID, "ack_token": ackTokens[t.DeviceID],
			})
			if marshalErr != nil {
				results <- pushResult{deviceID: t.DeviceID, endpoint: t.Endpoint, err: marshalErr}
				return
			}
			sendErr := s.hub.calls.webPush.Send(ctx, t, payload, time.Minute)
			results <- pushResult{deviceID: t.DeviceID, endpoint: t.Endpoint, err: sendErr}
		}(target)
	}

	accepted, failed := 0, 0
	for i := 0; i < total; i++ {
		result := <-results
		if result.err == nil {
			accepted++
			transport := "Web Push"
			if result.token != "" {
				transport = "FCM"
			}
			slog.Debug("message push accepted", "transport", transport, "sender", sess.User.Login,
				"recipient", recipient.Login, "device_id", result.deviceID)
			continue
		}
		failed++
		_ = s.store.DeleteMessageDelivery(messageID, result.deviceID)
		if result.token != "" {
			if errors.Is(result.err, ErrTokenInvalid) {
				s.store.ClearFCM(result.deviceID, result.token)
			}
			slog.Warn("message FCM push failed", "sender", sess.User.Login, "recipient", recipient.Login,
				"device_id", result.deviceID, "err", result.err)
		} else {
			if errors.Is(result.err, ErrWebPushGone) {
				s.store.ClearWebPush(result.deviceID, result.endpoint)
			}
			slog.Warn("message Web Push failed", "sender", sess.User.Login, "recipient", recipient.Login,
				"device_id", result.deviceID, "err", result.err)
		}
	}
	if accepted == 0 {
		writeErr(w, http.StatusBadGateway, "push_failed", "message could not be sent")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "accepted": accepted, "failed": failed})
}

// handleMessageAck is authorized by a one-time random secret embedded in the push for this device.
func (s *Server) handleMessageAck(w http.ResponseWriter, r *http.Request) {
	var req struct {
		MessageID string `json:"message_id"`
		AckToken  string `json:"ack_token"`
	}
	if !decodeJSON(w, r, &req) || len(req.MessageID) > 64 || len(req.AckToken) > 128 || req.MessageID == "" || req.AckToken == "" {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid message acknowledgement")
		return
	}
	senderID, deviceName, newlyDelivered, err := s.store.ConfirmMessageDelivery(req.MessageID, req.AckToken)
	if err != nil {
		if errors.Is(err, ErrNotFound) {
			writeErr(w, http.StatusNotFound, "unknown_message", "message acknowledgement not found")
		} else {
			serverErr(w, err)
		}
		return
	}
	if newlyDelivered {
		go s.notifyMessageDelivered(senderID, deviceName)
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "duplicate": !newlyDelivered})
}

// handleCallPushAck confirms that a client received an incoming-call push.
func (s *Server) handleCallPushAck(w http.ResponseWriter, r *http.Request) {
	var req struct {
		CallID   string `json:"call_id"`
		AckToken string `json:"ack_token"`
	}
	if !decodeJSON(w, r, &req) || len(req.CallID) > 64 || len(req.AckToken) > 128 || req.CallID == "" || req.AckToken == "" {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid call acknowledgement")
		return
	}
	if s.hub.calls == nil || !s.hub.calls.ConfirmPushAck(req.CallID, req.AckToken) {
		writeErr(w, http.StatusNotFound, "unknown_call", "call acknowledgement not found")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// notifyMessageDelivered sends a push to all sender devices after one recipient device confirms delivery.
func (s *Server) notifyMessageDelivered(senderID int64, deviceName string) {
	if s.hub.calls == nil {
		return
	}
	text := "Сообщение доставлено на " + truncRunes(deviceName, 64)
	title := "Кукуха"
	now := time.Now().UnixMilli()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if s.hub.calls.push != nil {
		targets, err := s.store.PushTargets(senderID)
		if err != nil {
			slog.Warn("message delivery notification: FCM targets", "err", err)
		} else {
			for _, target := range targets {
				data := map[string]string{
					"type": "message.delivered", "title": title, "text": text,
					"time": strconv.FormatInt(now, 10),
				}
				if _, err := s.hub.calls.push.SendWithResult(ctx, target.Token, data, "", time.Minute); err != nil {
					slog.Warn("message delivery notification: FCM failed", "device_id", target.DeviceID, "err", err)
				}
			}
		}
	}
	if s.hub.calls.webPush != nil {
		targets, err := s.store.WebPushTargets(senderID)
		if err != nil {
			slog.Warn("message delivery notification: Web Push targets", "err", err)
		} else {
			payload, _ := json.Marshal(map[string]any{
				"type": "message.delivered", "title": title, "body": text, "text": text, "time": now,
			})
			for _, target := range targets {
				if err := s.hub.calls.webPush.Send(ctx, target, payload, time.Minute); err != nil {
					slog.Warn("message delivery notification: Web Push failed", "device_id", target.DeviceID, "err", err)
				}
			}
		}
	}
}

// ---------- user photos and profile ----------

const maxAvatarBytes = 2 << 20
const maxAvatarDimension = 2048

func (s *Server) avatarPaths(login string) (user, admin string) {
	dir := filepath.Join(s.cfg.DataDir, "avatars")
	return filepath.Join(dir, login+".user.jpg"), filepath.Join(dir, login+".jpg")
}

func (s *Server) avatarFile(u User) string {
	user, admin := s.avatarPaths(u.Login)
	if st, err := os.Stat(user); err == nil && !st.IsDir() && st.Size() > 0 && st.Size() <= maxAvatarBytes {
		return user
	}
	return admin
}

func (s *Server) avatarVersion(id int64) int64 {
	u, err := s.store.UserByID(id)
	if err != nil {
		return 0
	}
	return s.avatarVersionForLogin(u.Login)
}

func (s *Server) avatarVersionForLogin(login string) int64 {
	user, admin := s.avatarPaths(login)
	path := admin
	if st, err := os.Stat(user); err == nil && !st.IsDir() && st.Size() > 0 && st.Size() <= maxAvatarBytes {
		path = user
	}
	st, err := os.Stat(path)
	if err != nil || st.IsDir() || st.Size() == 0 || st.Size() > maxAvatarBytes {
		return 0
	}
	return st.ModTime().UnixNano()
}

func (s *Server) handleMeGet(w http.ResponseWriter, r *http.Request, sess *Session) {
	writeJSON(w, http.StatusOK, map[string]any{
		"user":   sess.User,
		"avatar": s.avatarVersion(sess.User.ID),
		"media": map[string]any{
			"video": map[string]int{
				"width":       s.cfg.Media.Video.Width,
				"height":      s.cfg.Media.Video.Height,
				"fps":         s.cfg.Media.Video.FPS,
				"max_bitrate": s.cfg.Media.Video.MaxBitrate,
			},
		},
	})
}

func (s *Server) handleMe(w http.ResponseWriter, r *http.Request, sess *Session) {
	var req struct {
		Name string `json:"name"`
	}
	if !decodeJSON(w, r, &req) {
		return
	}
	name := strings.TrimSpace(req.Name)
	if name == "" || len([]rune(name)) > 64 {
		writeErr(w, http.StatusBadRequest, "bad_request", "name must contain 1-64 characters")
		return
	}
	if err := s.store.SetName(sess.User.ID, name); err != nil {
		if errors.Is(err, ErrNameTaken) {
			writeErr(w, http.StatusConflict, "name_taken", "display name is already used by another user")
			return
		}
		serverErr(w, err)
		return
	}
	sess.User.Name = name
	writeJSON(w, http.StatusOK, map[string]any{"user": sess.User})
}

func (s *Server) handleUploadAvatar(w http.ResponseWriter, r *http.Request, sess *Session) {
	r.Body = http.MaxBytesReader(w, r.Body, maxAvatarBytes)
	data, err := io.ReadAll(r.Body)
	if err != nil || len(data) < 4 || len(data) > maxAvatarBytes {
		writeErr(w, http.StatusBadRequest, "bad_avatar", "avatar must be a JPEG up to 2 MiB")
		return
	}
	cfg, err := jpeg.DecodeConfig(bytes.NewReader(data))
	if err != nil || cfg.Width < 1 || cfg.Height < 1 || cfg.Width > maxAvatarDimension || cfg.Height > maxAvatarDimension {
		writeErr(w, http.StatusBadRequest, "bad_avatar", "invalid JPEG or image dimensions exceed 2048x2048")
		return
	}
	if _, err := jpeg.Decode(bytes.NewReader(data)); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_avatar", "invalid JPEG data")
		return
	}
	userPath, _ := s.avatarPaths(sess.User.Login)
	if err := os.MkdirAll(filepath.Dir(userPath), 0o700); err != nil {
		serverErr(w, err)
		return
	}
	tmp, err := os.CreateTemp(filepath.Dir(userPath), ".avatar-*.tmp")
	if err != nil {
		serverErr(w, err)
		return
	}
	tmpName := tmp.Name()
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		_ = os.Remove(tmpName)
		serverErr(w, err)
		return
	}
	if err := tmp.Close(); err != nil {
		_ = os.Remove(tmpName)
		serverErr(w, err)
		return
	}
	if err := os.Rename(tmpName, userPath); err != nil {
		_ = os.Remove(tmpName)
		serverErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "avatar": s.avatarVersion(sess.User.ID)})
}

func (s *Server) handleDeleteAvatar(w http.ResponseWriter, r *http.Request, sess *Session) {
	userPath, _ := s.avatarPaths(sess.User.Login)
	if err := os.Remove(userPath); err != nil && !errors.Is(err, os.ErrNotExist) {
		serverErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "avatar": s.avatarVersion(sess.User.ID)})
}

func (s *Server) handleAvatar(w http.ResponseWriter, r *http.Request, sess *Session) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		writeErr(w, http.StatusNotFound, "not_found", "no photo")
		return
	}
	u, err := s.store.UserByID(id)
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "no photo")
		return
	}
	path := s.avatarFile(u)
	st, err := os.Stat(path)
	if err != nil || st.IsDir() || st.Size() == 0 || st.Size() > maxAvatarBytes {
		writeErr(w, http.StatusNotFound, "not_found", "no photo")
		return
	}
	f, err := os.Open(path)
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "no photo")
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "private, max-age=0, must-revalidate")
	http.ServeContent(w, r, "avatar.jpg", st.ModTime(), f)
}
