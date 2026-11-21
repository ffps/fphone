package main

import (
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

var (
	ErrNotFound  = errors.New("not found")
	ErrExists    = errors.New("already exists")
	ErrNameTaken = errors.New("display name is already used by another user")
)

// last_seen and expires_at are updated at most once a day per device,
// so frequent connections do not turn into frequent writes to the SD card.
const refreshEvery = 24 * time.Hour

type Store struct {
	db  *sql.DB
	ttl time.Duration
}

type User struct {
	ID       int64  `json:"id"`
	Login    string `json:"login"`
	Name     string `json:"name"`
	Disabled bool   `json:"-"`
}

type Session struct {
	DeviceID  int64
	DeviceUID string
	User      User
}

type UserRow struct {
	User
	Devices   int
	CreatedAt int64
	LastSeen  int64
}

type DeviceRow struct {
	ID           int64
	UserLogin    string
	UID          string
	Name         string
	HasPush      bool
	CreatedAt    int64
	LastSeen     int64
	LastActivity int64
	ExpiresAt    int64
}

func OpenStore(dir string, ttl time.Duration) (*Store, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	path := filepath.ToSlash(filepath.Join(dir, "fphone.db"))
	dsn := path + "?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)&_pragma=foreign_keys(1)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, err
	}
	s := &Store{db: db, ttl: ttl}
	if err := s.createSchema(); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) Close() error { return s.db.Close() }

// createSchema creates a new database. Existing databases are not migrated.
func (s *Store) createSchema() error {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS users (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			login TEXT NOT NULL UNIQUE COLLATE NOCASE,
			name TEXT NOT NULL,
			pass_hash TEXT NOT NULL,
			disabled INTEGER NOT NULL DEFAULT 0,
			created_at INTEGER NOT NULL
		)`,
		`CREATE TABLE IF NOT EXISTS devices (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
			device_uid TEXT NOT NULL,
			name TEXT NOT NULL,
			token_hash TEXT NOT NULL UNIQUE,
			fcm_token TEXT NOT NULL DEFAULT '',
			created_at INTEGER NOT NULL,
			last_seen INTEGER NOT NULL,
			last_activity INTEGER NOT NULL DEFAULT 0,
			expires_at INTEGER NOT NULL,
			UNIQUE(user_id, device_uid)
		)`,
		`CREATE INDEX IF NOT EXISTS devices_user ON devices(user_id)`,
		`CREATE TABLE IF NOT EXISTS web_push_subscriptions (
			device_id INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
			endpoint TEXT NOT NULL UNIQUE,
			p256dh TEXT NOT NULL,
			auth TEXT NOT NULL,
			created_at INTEGER NOT NULL,
			updated_at INTEGER NOT NULL
		)`,
	}
	for _, q := range stmts {
		if _, err := s.db.Exec(q); err != nil {
			return fmt.Errorf("create database schema: %w", err)
		}
	}
	// Fail clearly if an older database was supplied; this release intentionally has no migrations.
	var hasActivity int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM pragma_table_info('devices') WHERE name='last_activity'`).Scan(&hasActivity)
	if err != nil {
		return err
	}
	if hasActivity == 0 {
		return errors.New("database has an old schema; stop fphone and remove fphone.db, fphone.db-wal and fphone.db-shm")
	}
	return nil
}

// ---------- users ----------

func (s *Store) CreateUser(login, name, passHash string) error {
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM users WHERE name=? COLLATE NOCASE OR login=? COLLATE NOCASE`, name, name).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return ErrNameTaken
	}
	_, err := s.db.Exec(`INSERT INTO users(login,name,pass_hash,created_at) VALUES(?,?,?,?)`,
		login, name, passHash, time.Now().Unix())
	if err != nil && strings.Contains(err.Error(), "UNIQUE") {
		return ErrExists
	}
	return err
}

func (s *Store) UserByLogin(login string) (User, string, error) {
	var u User
	var hash string
	var dis int
	err := s.db.QueryRow(`SELECT id,login,name,disabled,pass_hash FROM users WHERE login=?`, login).
		Scan(&u.ID, &u.Login, &u.Name, &dis, &hash)
	if errors.Is(err, sql.ErrNoRows) {
		return User{}, "", ErrNotFound
	}
	if err != nil {
		return User{}, "", err
	}
	u.Disabled = dis != 0
	return u, hash, nil
}

func (s *Store) SetName(userID int64, name string) error {
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM users WHERE id<>? AND (name=? COLLATE NOCASE OR login=? COLLATE NOCASE)`, userID, name, name).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return ErrNameTaken
	}
	res, err := s.db.Exec(`UPDATE users SET name=? WHERE id=?`, name, userID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// SetPassword changes the password and revokes all tokens of the user.
func (s *Store) SetPassword(login, passHash string) error {
	res, err := s.db.Exec(`UPDATE users SET pass_hash=? WHERE login=?`, passHash, login)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	_, err = s.db.Exec(`DELETE FROM devices WHERE user_id=(SELECT id FROM users WHERE login=?)`, login)
	return err
}

func (s *Store) SetDisabled(login string, disabled bool) error {
	v := 0
	if disabled {
		v = 1
	}
	res, err := s.db.Exec(`UPDATE users SET disabled=? WHERE login=?`, v, login)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *Store) DeleteUser(login string) error {
	res, err := s.db.Exec(`DELETE FROM users WHERE login=?`, login)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *Store) ListUsers() ([]UserRow, error) {
	rows, err := s.db.Query(`SELECT u.id,u.login,u.name,u.disabled,u.created_at,COUNT(d.id),COALESCE(MAX(d.last_activity),0)
		FROM users u LEFT JOIN devices d ON d.user_id=u.id GROUP BY u.id ORDER BY u.login`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []UserRow
	for rows.Next() {
		var r UserRow
		var dis int
		if err := rows.Scan(&r.ID, &r.Login, &r.Name, &dis, &r.CreatedAt, &r.Devices, &r.LastSeen); err != nil {
			return nil, err
		}
		r.Disabled = dis != 0
		out = append(out, r)
	}
	return out, rows.Err()
}

// ContactRow is a user in the contact list. HasPush tells whether at least one device of the
// user has a push token, so the user can be woken up for a call while the app is closed.
type ContactRow struct {
	User
	HasPush  bool
	LastSeen int64
}

// Contacts returns all active users except the one who asks.
func (s *Store) Contacts(exceptUserID int64) ([]ContactRow, error) {
	rows, err := s.db.Query(`SELECT u.id,u.login,u.name,
			EXISTS(SELECT 1 FROM devices d WHERE d.user_id=u.id AND (d.fcm_token<>'' OR EXISTS(SELECT 1 FROM web_push_subscriptions w WHERE w.device_id=d.id))),
			COALESCE((SELECT MAX(d.last_activity) FROM devices d WHERE d.user_id=u.id),0)
		FROM users u WHERE u.disabled=0 AND u.id<>? ORDER BY u.name`, exceptUserID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []ContactRow
	for rows.Next() {
		var c ContactRow
		var push int
		if err := rows.Scan(&c.ID, &c.Login, &c.Name, &push, &c.LastSeen); err != nil {
			return nil, err
		}
		c.HasPush = push != 0
		out = append(out, c)
	}
	return out, rows.Err()
}

// ---------- devices and tokens ----------

// UpsertDevice creates or updates a device (user + device_uid) and issues a new token.
func (s *Store) UpsertDevice(userID int64, uid, name, fcm string) (token string, expires int64, err error) {
	token, hash := NewToken()
	now := time.Now()
	expires = now.Add(s.ttl).Unix()
	_, err = s.db.Exec(`INSERT INTO devices(user_id,device_uid,name,token_hash,fcm_token,created_at,last_seen,expires_at)
		VALUES(?,?,?,?,?,?,?,?)
		ON CONFLICT(user_id,device_uid) DO UPDATE SET
			name=excluded.name, token_hash=excluded.token_hash, fcm_token=excluded.fcm_token,
			last_seen=excluded.last_seen, expires_at=excluded.expires_at`,
		userID, uid, name, hash, fcm, now.Unix(), now.Unix(), expires)
	if err != nil {
		return "", 0, err
	}
	return token, expires, nil
}

// Authenticate checks a token and extends its lifetime if needed (at most once a day).
func (s *Store) Authenticate(token string) (*Session, error) {
	if token == "" || len(token) > 200 {
		return nil, ErrNotFound
	}
	var ss Session
	var dis int
	var exp, last int64
	err := s.db.QueryRow(`SELECT d.id,d.device_uid,d.expires_at,d.last_seen,u.id,u.login,u.name,u.disabled
		FROM devices d JOIN users u ON u.id=d.user_id WHERE d.token_hash=?`, HashToken(token)).
		Scan(&ss.DeviceID, &ss.DeviceUID, &exp, &last, &ss.User.ID, &ss.User.Login, &ss.User.Name, &dis)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	now := time.Now()
	if dis != 0 || exp <= now.Unix() {
		return nil, ErrNotFound
	}
	s.maybeExtend(ss.DeviceID, last, now)
	return &ss, nil
}

func (s *Store) maybeExtend(deviceID, lastSeen int64, now time.Time) {
	if now.Sub(time.Unix(lastSeen, 0)) < refreshEvery {
		return
	}
	s.db.Exec(`UPDATE devices SET last_seen=?, expires_at=? WHERE id=?`,
		now.Unix(), now.Add(s.ttl).Unix(), deviceID)
}

// Revalidate checks that a device is still valid (used for WebSocket connections that are already open).
func (s *Store) Revalidate(deviceID int64) bool {
	var dis int
	var exp, last int64
	err := s.db.QueryRow(`SELECT d.expires_at,d.last_seen,u.disabled
		FROM devices d JOIN users u ON u.id=d.user_id WHERE d.id=?`, deviceID).Scan(&exp, &last, &dis)
	if err != nil {
		return !errors.Is(err, sql.ErrNoRows) // on a database error keep the session
	}
	now := time.Now()
	if dis != 0 || exp <= now.Unix() {
		return false
	}
	s.maybeExtend(deviceID, last, now)
	return true
}

func (s *Store) MarkSeen(deviceID int64) {
	now := time.Now().Unix()
	_, _ = s.db.Exec(`UPDATE devices SET last_seen=? WHERE id=?`, now, deviceID)
}

// MarkActivity records the time when a device starts or answers a call.
func (s *Store) MarkActivity(deviceID int64) {
	now := time.Now().Unix()
	_, _ = s.db.Exec(`UPDATE devices SET last_activity=? WHERE id=?`, now, deviceID)
}

func (s *Store) SetFCM(deviceID int64, fcm string) error {
	_, err := s.db.Exec(`UPDATE devices SET fcm_token=? WHERE id=?`, fcm, deviceID)
	return err
}

func (s *Store) DeleteDevice(deviceID int64) error {
	res, err := s.db.Exec(`DELETE FROM devices WHERE id=?`, deviceID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

func (s *Store) ListDevices(login string) ([]DeviceRow, error) {
	q := `SELECT d.id,u.login,d.device_uid,d.name,(d.fcm_token<>'' OR EXISTS(SELECT 1 FROM web_push_subscriptions w WHERE w.device_id=d.id)),d.created_at,d.last_seen,d.last_activity,d.expires_at
		FROM devices d JOIN users u ON u.id=d.user_id`
	var args []any
	if login != "" {
		q += ` WHERE u.login=?`
		args = append(args, login)
	}
	q += ` ORDER BY u.login,d.id`
	rows, err := s.db.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DeviceRow
	for rows.Next() {
		var r DeviceRow
		var fcm int
		if err := rows.Scan(&r.ID, &r.UserLogin, &r.UID, &r.Name, &fcm, &r.CreatedAt, &r.LastSeen, &r.LastActivity, &r.ExpiresAt); err != nil {
			return nil, err
		}
		r.HasPush = fcm != 0
		out = append(out, r)
	}
	return out, rows.Err()
}

// UserByID is used when a call is started (the caller knows only the ID of the callee).
func (s *Store) UserByID(id int64) (User, error) {
	var u User
	var dis int
	err := s.db.QueryRow(`SELECT id,login,name,disabled FROM users WHERE id=?`, id).
		Scan(&u.ID, &u.Login, &u.Name, &dis)
	if errors.Is(err, sql.ErrNoRows) {
		return User{}, ErrNotFound
	}
	if err != nil {
		return User{}, err
	}
	u.Disabled = dis != 0
	return u, nil
}

// PushTarget is a device that has a push token.
type PushTarget struct {
	DeviceID int64
	Token    string
}

// PushTargets returns the devices of a user that have a push token.
func (s *Store) PushTargets(userID int64) ([]PushTarget, error) {
	rows, err := s.db.Query(`SELECT id,fcm_token FROM devices WHERE user_id=? AND fcm_token<>''`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []PushTarget
	for rows.Next() {
		var t PushTarget
		if err := rows.Scan(&t.DeviceID, &t.Token); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// ClearFCM removes a push token that FCM does not know any more. It does nothing if the
// device has already registered a different token.
func (s *Store) ClearFCM(deviceID int64, token string) error {
	_, err := s.db.Exec(`UPDATE devices SET fcm_token='' WHERE id=? AND fcm_token=?`, deviceID, token)
	return err
}

func (s *Store) PushTargetByDevice(deviceID int64) (PushTarget, string, error) {
	var t PushTarget
	var login string
	err := s.db.QueryRow(`SELECT d.id,d.fcm_token,u.login FROM devices d JOIN users u ON u.id=d.user_id WHERE d.id=?`, deviceID).Scan(&t.DeviceID, &t.Token, &login)
	if errors.Is(err, sql.ErrNoRows) {
		return PushTarget{}, "", ErrNotFound
	}
	return t, login, err
}

// WebPushTarget is a browser subscription registered by one device.
type WebPushTarget struct {
	DeviceID int64
	Endpoint string
	P256dh   string
	Auth     string
	Login    string
}

// SetWebPush stores or replaces the Web Push subscription for a device.
func (s *Store) SetWebPush(deviceID int64, endpoint, p256dh, auth string) error {
	now := time.Now().Unix()
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	if _, err = tx.Exec(`DELETE FROM web_push_subscriptions WHERE device_id=? OR endpoint=?`, deviceID, endpoint); err != nil {
		_ = tx.Rollback()
		return err
	}
	if _, err = tx.Exec(`INSERT INTO web_push_subscriptions(device_id,endpoint,p256dh,auth,created_at,updated_at)
		VALUES(?,?,?,?,?,?)`, deviceID, endpoint, p256dh, auth, now, now); err != nil {
		_ = tx.Rollback()
		return err
	}
	return tx.Commit()
}

func (s *Store) DeleteWebPush(deviceID int64) error {
	_, err := s.db.Exec(`DELETE FROM web_push_subscriptions WHERE device_id=?`, deviceID)
	return err
}

func (s *Store) WebPushTargets(userID int64) ([]WebPushTarget, error) {
	rows, err := s.db.Query(`SELECT d.id,w.endpoint,w.p256dh,w.auth,u.login
		FROM web_push_subscriptions w
		JOIN devices d ON d.id=w.device_id
		JOIN users u ON u.id=d.user_id
		WHERE d.user_id=?`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []WebPushTarget
	for rows.Next() {
		var t WebPushTarget
		if err := rows.Scan(&t.DeviceID, &t.Endpoint, &t.P256dh, &t.Auth, &t.Login); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

func (s *Store) ClearWebPush(deviceID int64, endpoint string) error {
	_, err := s.db.Exec(`DELETE FROM web_push_subscriptions WHERE device_id=? AND endpoint=?`, deviceID, endpoint)
	return err
}
