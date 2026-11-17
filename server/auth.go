package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/argon2"
)

// ---------- passwords: argon2id ----------

const (
	argonTime    = 2
	argonMemKiB  = 32 * 1024
	argonThreads = 1
	argonKeyLen  = 32
	saltLen      = 16
)

func HashPassword(pw string) (string, error) {
	salt := make([]byte, saltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	key := argon2.IDKey([]byte(pw), salt, argonTime, argonMemKiB, argonThreads, argonKeyLen)
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2.Version, argonMemKiB, argonTime, argonThreads,
		base64.RawStdEncoding.EncodeToString(salt),
		base64.RawStdEncoding.EncodeToString(key)), nil
}

func CheckPassword(pw, enc string) bool {
	parts := strings.Split(enc, "$") // "", argon2id, v=19, m=..,t=..,p=.., salt, key
	if len(parts) != 6 || parts[1] != "argon2id" {
		return false
	}
	var m, t uint32
	var p uint8
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &m, &t, &p); err != nil {
		return false
	}
	if m > 256*1024 || t > 10 || p == 0 { // protection against a damaged record in the database
		return false
	}
	salt, err := base64.RawStdEncoding.DecodeString(parts[4])
	if err != nil {
		return false
	}
	want, err := base64.RawStdEncoding.DecodeString(parts[5])
	if err != nil || len(want) == 0 {
		return false
	}
	got := argon2.IDKey([]byte(pw), salt, t, m, p, uint32(len(want)))
	return subtle.ConstantTimeCompare(got, want) == 1
}

// ---------- session tokens ----------

// NewToken returns a random token (given to the client) and its hash (stored in the database).
func NewToken() (token, hash string) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	token = base64.RawURLEncoding.EncodeToString(b)
	return token, HashToken(token)
}

func HashToken(t string) string {
	h := sha256.Sum256([]byte(t))
	return hex.EncodeToString(h[:])
}

// ---------- login attempt limiter (per login) ----------
//
// The client IP address is not available behind the Keenetic proxy, so attempts are counted per login.
// After 5 failures in a row the login is blocked for 30 s, then 60 s, 2 min ... up to 15 min.
// Downside: someone who knows a login can block that user from logging in. This is acceptable
// for a home system. The state is kept in memory: restarting the server resets all blocks.

type attempt struct {
	fails       int
	lockedUntil time.Time
	last        time.Time
}

type Limiter struct {
	mu sync.Mutex
	m  map[string]*attempt
}

func NewLimiter() *Limiter {
	return &Limiter{m: map[string]*attempt{}}
}

// Check returns how long to wait and whether a login attempt is allowed now.
func (l *Limiter) Check(key string) (time.Duration, bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	a := l.m[key]
	if a == nil {
		return 0, true
	}
	now := time.Now()
	if now.Sub(a.last) > time.Hour {
		delete(l.m, key)
		return 0, true
	}
	if now.Before(a.lockedUntil) {
		return a.lockedUntil.Sub(now), false
	}
	return 0, true
}

func (l *Limiter) Fail(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.m) > 10000 { // protection against unbounded memory growth
		l.m = map[string]*attempt{}
	}
	a := l.m[key]
	if a == nil {
		a = &attempt{}
		l.m[key] = a
	}
	now := time.Now()
	a.fails++
	a.last = now
	if a.fails >= 5 {
		shift := a.fails - 5
		if shift > 5 {
			shift = 5
		}
		d := 30 * time.Second << uint(shift)
		if d > 15*time.Minute {
			d = 15 * time.Minute
		}
		a.lockedUntil = now.Add(d)
	}
}

func (l *Limiter) Reset(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.m, key)
}
