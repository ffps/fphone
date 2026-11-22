package main

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

var ErrWebPushGone = errors.New("web push subscription is gone")

type WebPushConfig struct {
	PublicKey  string `yaml:"public_key"`
	PrivateKey string `yaml:"private_key"`
	Subject    string `yaml:"subject"`
}

type WebPush struct {
	publicKey  string
	privateKey *ecdsa.PrivateKey
	subject    string
	client     *http.Client
}

type webPushSubscription struct {
	Endpoint string `json:"endpoint"`
	Keys     struct {
		P256dh string `json:"p256dh"`
		Auth   string `json:"auth"`
	} `json:"keys"`
}

// GenerateVAPIDKeys creates a P-256 VAPID key pair in URL-safe Base64 format.
func GenerateVAPIDKeys() (string, string, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return "", "", err
	}
	pub := elliptic.Marshal(elliptic.P256(), key.X, key.Y)
	priv := make([]byte, 32)
	key.D.FillBytes(priv)
	return base64.RawURLEncoding.EncodeToString(pub), base64.RawURLEncoding.EncodeToString(priv), nil
}

func NewWebPush(cfg WebPushConfig) (*WebPush, error) {
	if cfg.PublicKey == "" || cfg.PrivateKey == "" {
		return nil, errors.New("web_push.public_key and web_push.private_key are required")
	}
	pub, err := base64.RawURLEncoding.DecodeString(cfg.PublicKey)
	if err != nil || len(pub) != 65 {
		return nil, errors.New("web_push.public_key must be a URL-safe Base64 P-256 public key")
	}
	priv, err := base64.RawURLEncoding.DecodeString(cfg.PrivateKey)
	if err != nil || len(priv) != 32 {
		return nil, errors.New("web_push.private_key must be a URL-safe Base64 P-256 private key")
	}
	x, y := elliptic.Unmarshal(elliptic.P256(), pub)
	if x == nil || y == nil {
		return nil, errors.New("web_push.public_key is invalid")
	}
	d := new(big.Int).SetBytes(priv)
	if d.Sign() <= 0 || d.Cmp(elliptic.P256().Params().N) >= 0 {
		return nil, errors.New("web_push.private_key is invalid")
	}
	x2, y2 := elliptic.P256().ScalarBaseMult(priv)
	if x2.Cmp(x) != 0 || y2.Cmp(y) != 0 {
		return nil, errors.New("VAPID public and private keys do not match")
	}
	if cfg.Subject == "" {
		cfg.Subject = "mailto:admin@localhost"
	}
	if !strings.HasPrefix(cfg.Subject, "mailto:") && !strings.HasPrefix(cfg.Subject, "https://") {
		return nil, errors.New("web_push.subject must be a mailto: or https:// URL")
	}
	return &WebPush{
		publicKey:  cfg.PublicKey,
		privateKey: &ecdsa.PrivateKey{PublicKey: ecdsa.PublicKey{Curve: elliptic.P256(), X: x, Y: y}, D: d},
		subject:    cfg.Subject,
		client:     newWebPushHTTPClient(),
	}, nil
}

func newWebPushHTTPClient() *http.Client {
	transport := &http.Transport{
		ForceAttemptHTTP2: true,
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			host, port, err := net.SplitHostPort(address)
			if err != nil {
				return nil, err
			}
			ips, err := net.DefaultResolver.LookupIPAddr(ctx, host)
			if err != nil {
				return nil, err
			}
			for _, item := range ips {
				ip := item.IP
				if ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsUnspecified() || ip.IsMulticast() {
					return nil, errors.New("Web Push endpoint resolved to a non-public address")
				}
			}
			dialer := net.Dialer{Timeout: 10 * time.Second}
			var lastErr error
			for _, item := range ips {
				conn, dialErr := dialer.DialContext(ctx, network, net.JoinHostPort(item.IP.String(), port))
				if dialErr == nil {
					return conn, nil
				}
				lastErr = dialErr
			}
			if lastErr == nil {
				lastErr = errors.New("Web Push endpoint has no IP addresses")
			}
			return nil, lastErr
		},
	}
	return &http.Client{
		Transport: transport,
		Timeout:   15 * time.Second,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
}

func validateWebPushSubscription(sub webPushSubscription) error {
	u, err := url.Parse(sub.Endpoint)
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil ||
		u.Hostname() == "localhost" || strings.HasSuffix(strings.ToLower(u.Hostname()), ".local") {
		return errors.New("invalid Web Push endpoint")
	}
	if ip := net.ParseIP(u.Hostname()); ip != nil &&
		(ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsUnspecified() || ip.IsMulticast()) {
		return errors.New("invalid Web Push endpoint")
	}
	p256dh, err := base64.RawURLEncoding.DecodeString(sub.Keys.P256dh)
	if err != nil || len(p256dh) != 65 {
		return errors.New("invalid Web Push p256dh key")
	}
	auth, err := base64.RawURLEncoding.DecodeString(sub.Keys.Auth)
	if err != nil || len(auth) != 16 {
		return errors.New("invalid Web Push auth key")
	}
	return nil
}

func (w *WebPush) PublicKey() string { return w.publicKey }

func (w *WebPush) Send(ctx context.Context, sub WebPushTarget, payload []byte, ttl time.Duration) error {
	u, err := url.Parse(sub.Endpoint)
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || u.Hostname() == "localhost" || strings.HasSuffix(strings.ToLower(u.Hostname()), ".local") {
		return errors.New("invalid Web Push endpoint")
	}
	if ip := net.ParseIP(u.Hostname()); ip != nil && (ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsUnspecified()) {
		return errors.New("invalid Web Push endpoint")
	}
	body, err := encryptWebPush(sub.P256dh, sub.Auth, payload)
	if err != nil {
		return err
	}
	jwt, err := w.vapidJWT(u.Scheme + "://" + u.Host)
	if err != nil {
		return err
	}
	seconds := int(ttl.Seconds())
	if seconds < 0 {
		seconds = 0
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, sub.Endpoint, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/octet-stream")
	req.Header.Set("Content-Encoding", "aes128gcm")
	req.Header.Set("TTL", fmt.Sprint(seconds))
	req.Header.Set("Urgency", "high")
	req.Header.Set("Authorization", "vapid t="+jwt+", k="+w.publicKey)
	resp, err := w.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	if resp.StatusCode == http.StatusGone || resp.StatusCode == http.StatusNotFound {
		return ErrWebPushGone
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("push service returned HTTP %d", resp.StatusCode)
	}
	return nil
}

func (w *WebPush) vapidJWT(audience string) (string, error) {
	header, _ := json.Marshal(map[string]string{"typ": "JWT", "alg": "ES256"})
	claims, _ := json.Marshal(map[string]any{
		"aud": audience,
		"exp": time.Now().Add(12 * time.Hour).Unix(),
		"sub": w.subject,
	})
	unsigned := base64.RawURLEncoding.EncodeToString(header) + "." + base64.RawURLEncoding.EncodeToString(claims)
	hash := sha256.Sum256([]byte(unsigned))
	r, s, err := ecdsa.Sign(rand.Reader, w.privateKey, hash[:])
	if err != nil {
		return "", err
	}
	sig := make([]byte, 64)
	r.FillBytes(sig[:32])
	s.FillBytes(sig[32:])
	return unsigned + "." + base64.RawURLEncoding.EncodeToString(sig), nil
}

func encryptWebPush(clientKey, authKey string, payload []byte) ([]byte, error) {
	clientPubBytes, err := base64.RawURLEncoding.DecodeString(clientKey)
	if err != nil {
		return nil, errors.New("invalid subscription p256dh key")
	}
	auth, err := base64.RawURLEncoding.DecodeString(authKey)
	if err != nil || len(auth) == 0 {
		return nil, errors.New("invalid subscription auth key")
	}
	curve := ecdh.P256()
	clientPub, err := curve.NewPublicKey(clientPubBytes)
	if err != nil {
		return nil, errors.New("invalid subscription public key")
	}
	serverPriv, err := curve.GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	shared, err := serverPriv.ECDH(clientPub)
	if err != nil {
		return nil, err
	}
	info := append([]byte("WebPush: info\x00"), clientPubBytes...)
	info = append(info, serverPriv.PublicKey().Bytes()...)
	prkKey := hkdfExtract(auth, shared)
	ikm := hkdfExpand(prkKey, info, 32)
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return nil, err
	}
	prk := hkdfExtract(salt, ikm)

    cek := hkdfExpand(prk, []byte("Content-Encoding: aes128gcm\x00"), 16)
    nonce := hkdfExpand(prk, []byte("Content-Encoding: nonce\x00"), 12)

	block, err := aes.NewCipher(cek)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	plain := append(append([]byte(nil), payload...), 2)
	sealed := gcm.Seal(nil, nonce, plain, nil)
	pub := serverPriv.PublicKey().Bytes()
	record := make([]byte, 0, 16+4+1+len(pub)+len(sealed))
	record = append(record, salt...)
	rs := make([]byte, 4)
	binary.BigEndian.PutUint32(rs, 4096)
	record = append(record, rs...)
	record = append(record, byte(len(pub)))
	record = append(record, pub...)
	record = append(record, sealed...)
	return record, nil
}

func hkdfExtract(salt, ikm []byte) []byte {
	if len(salt) == 0 {
		salt = make([]byte, sha256.Size)
	}
	h := hmacSHA256(salt, ikm)
	return h
}

func hkdfExpand(prk, info []byte, length int) []byte {
	var out, prev []byte
	for counter := byte(1); len(out) < length; counter++ {
		data := append(append(append([]byte(nil), prev...), info...), counter)
		prev = hmacSHA256(prk, data)
		out = append(out, prev...)
	}
	return out[:length]
}

func hmacSHA256(key, data []byte) []byte {
	h := hmac.New(sha256.New, key)
	_, _ = h.Write(data)
	return h.Sum(nil)
}
