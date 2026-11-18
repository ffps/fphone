package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

func controlToken(dataDir string) (string, error) {
	path := filepath.Join(dataDir, "control.token")
	if b, err := os.ReadFile(path); err == nil {
		v := strings.TrimSpace(string(b))
		if len(v) >= 32 {
			return v, nil
		}
	}
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	v := hex.EncodeToString(b)
	if err := os.WriteFile(path, []byte(v+"\n"), 0600); err != nil {
		return "", err
	}
	return v, nil
}

func startControlServer(ctx context.Context, cfg *Config, hub *Hub) error {
	token, err := controlToken(cfg.DataDir)
	if err != nil {
		return fmt.Errorf("local control token: %w", err)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /device/log/{id}", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+token {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
		if err != nil || id <= 0 {
			http.Error(w, "invalid device ID", http.StatusBadRequest)
			return
		}
		text, err := hub.RequestDeviceLog(id, 12*time.Second)
		if err != nil {
			http.Error(w, err.Error(), http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(map[string]string{"text": text})
	})
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", cfg.ControlPort))
	if err != nil {
		return fmt.Errorf("local control listener 127.0.0.1:%d: %w", cfg.ControlPort, err)
	}
	srv := &http.Server{Handler: mux, ReadHeaderTimeout: 3 * time.Second}
	go func() { _ = srv.Serve(ln) }()
	go func() {
		<-ctx.Done()
		c, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = srv.Shutdown(c)
	}()
	return nil
}

func requestDeviceLog(cfg *Config, id int64) (string, error) {
	token, err := controlToken(cfg.DataDir)
	if err != nil {
		return "", err
	}
	client := &http.Client{Timeout: 15 * time.Second}
	req, err := http.NewRequest(http.MethodPost, fmt.Sprintf("http://127.0.0.1:%d/device/log/%d", cfg.ControlPort, id), nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("cannot contact the running fphone server on 127.0.0.1:%d: %w", cfg.ControlPort, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("device log request failed: %s", strings.TrimSpace(string(b)))
	}
	var out struct {
		Text string `json:"text"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", err
	}
	return out.Text, nil
}
