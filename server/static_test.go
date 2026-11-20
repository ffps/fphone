package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func staticTestServer(t *testing.T, cacheSeconds int) *Server {
	t.Helper()
	dataDir := t.TempDir()
	webDir := filepath.Join(dataDir, "web")
	if err := os.MkdirAll(filepath.Join(webDir, "pwa"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(webDir, "empty-dir"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(webDir, "index.html"), []byte("root"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(webDir, "pwa", "index.html"), []byte("pwa"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(webDir, "app.js"), []byte("let x = 1"), 0o644); err != nil {
		t.Fatal(err)
	}
	return &Server{cfg: &Config{DataDir: dataDir, StaticCacheSeconds: cacheSeconds}}
}

func TestServeStaticRootAndDirectoryIndex(t *testing.T) {
	s := staticTestServer(t, 0)
	h := s.Handler()

	for _, tc := range []struct {
		url, want string
	}{
		{"/", "root"},
		{"/pwa/", "pwa"},
	} {
		r := httptest.NewRequest(http.MethodGet, tc.url, nil)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != http.StatusOK || w.Body.String() != tc.want {
			t.Fatalf("GET %s: status=%d body=%q", tc.url, w.Code, w.Body.String())
		}
		if w.Header().Get("Cache-Control") != "no-cache" {
			t.Fatalf("GET %s: Cache-Control=%q", tc.url, w.Header().Get("Cache-Control"))
		}
	}
}

func TestServeStaticDirectoryRedirect(t *testing.T) {
	s := staticTestServer(t, 0)
	r := httptest.NewRequest(http.MethodGet, "/pwa?x=1", nil)
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != http.StatusMovedPermanently || w.Header().Get("Location") != "/pwa/?x=1" {
		t.Fatalf("unexpected redirect: status=%d location=%q", w.Code, w.Header().Get("Location"))
	}
}

func TestServeStaticContentTypeAndCache(t *testing.T) {
	s := staticTestServer(t, 300)
	r := httptest.NewRequest(http.MethodGet, "/app.js", nil)
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != http.StatusOK || w.Header().Get("Content-Type") != "application/javascript; charset=utf-8" {
		t.Fatalf("unexpected response: status=%d content-type=%q", w.Code, w.Header().Get("Content-Type"))
	}
	if w.Header().Get("Cache-Control") != "public, max-age=300" {
		t.Fatalf("unexpected Cache-Control: %q", w.Header().Get("Cache-Control"))
	}
}

func TestServeStaticNoListingAndMissingFiles(t *testing.T) {
	s := staticTestServer(t, 0)
	for _, url := range []string{"/missing", "/empty-dir/"} {
		r := httptest.NewRequest(http.MethodGet, url, nil)
		w := httptest.NewRecorder()
		s.Handler().ServeHTTP(w, r)
		if w.Code != http.StatusNotFound {
			t.Fatalf("GET %s: got status %d", url, w.Code)
		}
	}
}

func TestServeStaticMethodsAndAPIIsolation(t *testing.T) {
	s := staticTestServer(t, 0)
	for _, tc := range []struct {
		method, url string
		status      int
	}{
		{http.MethodHead, "/", http.StatusOK},
		{http.MethodPost, "/", http.StatusMethodNotAllowed},
		{http.MethodGet, "/api/not-a-route", http.StatusNotFound},
	} {
		r := httptest.NewRequest(tc.method, tc.url, nil)
		w := httptest.NewRecorder()
		s.Handler().ServeHTTP(w, r)
		if w.Code != tc.status {
			t.Fatalf("%s %s: got status %d, want %d", tc.method, tc.url, w.Code, tc.status)
		}
	}
}

func TestServeStaticRejectsSymlinkEscape(t *testing.T) {
	s := staticTestServer(t, 0)
	outside := filepath.Join(t.TempDir(), "secret.txt")
	if err := os.WriteFile(outside, []byte("secret"), 0o644); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(s.cfg.DataDir, "web", "escape.txt")
	if err := os.Symlink(outside, link); err != nil {
		t.Skipf("symlink unavailable: %v", err)
	}
	r := httptest.NewRequest(http.MethodGet, "/escape.txt", nil)
	w := httptest.NewRecorder()
	s.Handler().ServeHTTP(w, r)
	if w.Code != http.StatusNotFound || strings.Contains(w.Body.String(), "secret") {
		t.Fatalf("symlink escaped web root: status=%d body=%q", w.Code, w.Body.String())
	}
}
