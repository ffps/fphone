package main

import (
	"bytes"
	_ "embed"
	"encoding/json"
	"errors"
	"image/jpeg"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

//go:embed test.html
var testHTML []byte

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
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Write([]byte("fphone\n"))
	})
	mux.HandleFunc("GET /api/v1/ping", s.rest(s.handlePing))
	mux.HandleFunc("POST /api/v1/login", s.rest(s.handleLogin))
	mux.HandleFunc("POST /api/v1/logout", s.authed(s.handleLogout))
	mux.HandleFunc("PUT /api/v1/device", s.authed(s.handleDevice))
	mux.HandleFunc("GET /api/v1/contacts", s.authed(s.handleContacts))
	mux.HandleFunc("GET /api/v1/users/{id}/avatar", s.authed(s.handleAvatar))
	mux.HandleFunc("GET /api/v1/me", s.authed(s.handleMeGet))
	mux.HandleFunc("PUT /api/v1/me", s.authed(s.handleMe))
	mux.HandleFunc("PUT /api/v1/me/avatar", s.authed(s.handleUploadAvatar))
	mux.HandleFunc("DELETE /api/v1/me/avatar", s.authed(s.handleDeleteAvatar))
	mux.HandleFunc("GET /ws", s.hub.ServeWS)
	if s.cfg.TestPage {
		mux.HandleFunc("GET /test", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.Header().Set("Cache-Control", "no-store")
			w.Write(testHTML)
		})
	}
	return mux
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
		Name      string `json:"name"`
		Online    bool   `json:"online"`
		Reachable bool   `json:"reachable"`
		Avatar    int64  `json:"avatar"` // version of the photo (file time), 0 = no photo
		LastSeen  int64  `json:"last_seen"`
	}
	out := make([]contact, 0, len(users))
	for _, u := range users {
		on := online[u.ID]
		out = append(out, contact{
			ID: u.ID, Name: u.Name, Online: on,
			Reachable: on || (canPush && u.HasPush),
			Avatar:    s.avatarVersionForLogin(u.Login),
			LastSeen:  u.LastSeen,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"contacts": out})
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
