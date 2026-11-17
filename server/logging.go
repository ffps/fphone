package main

import (
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
)

// rotWriter is a log file with size-based rotation: path, path.1, ... path.<keep>.
type rotWriter struct {
	mu   sync.Mutex
	path string
	max  int64
	keep int
	f    *os.File
	size int64
}

func newRotWriter(path string, maxMB, keep int) (*rotWriter, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, err
	}
	w := &rotWriter{path: path, max: int64(maxMB) << 20, keep: keep}
	if err := w.open(); err != nil {
		return nil, err
	}
	return w, nil
}

func (w *rotWriter) open() error {
	f, err := os.OpenFile(w.path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o640)
	if err != nil {
		return err
	}
	st, err := f.Stat()
	if err != nil {
		f.Close()
		return err
	}
	w.f = f
	w.size = st.Size()
	return nil
}

func (w *rotWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.f == nil {
		if err := w.open(); err != nil {
			return 0, err
		}
	}
	if w.max > 0 && w.size > 0 && w.size+int64(len(p)) > w.max {
		w.rotate()
		if w.f == nil {
			if err := w.open(); err != nil {
				return 0, err
			}
		}
	}
	n, err := w.f.Write(p)
	w.size += int64(n)
	return n, err
}

func (w *rotWriter) rotate() {
	w.f.Close()
	w.f = nil
	if w.keep >= 1 {
		os.Remove(fmt.Sprintf("%s.%d", w.path, w.keep))
		for i := w.keep - 1; i >= 1; i-- {
			os.Rename(fmt.Sprintf("%s.%d", w.path, i), fmt.Sprintf("%s.%d", w.path, i+1))
		}
		os.Rename(w.path, w.path+".1")
	} else {
		os.Remove(w.path)
	}
	if err := w.open(); err != nil {
		w.f = nil
	}
}

func (w *rotWriter) Close() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.f == nil {
		return nil
	}
	err := w.f.Close()
	w.f = nil
	return err
}

func setupLogging(cfg *Config) (*slog.Logger, func(), error) {
	var w io.Writer = os.Stderr
	closeFn := func() {}
	if cfg.Log.File != "" {
		rw, err := newRotWriter(cfg.Log.File, cfg.Log.MaxSizeMB, cfg.Log.Keep)
		if err != nil {
			return nil, nil, fmt.Errorf("log file: %w", err)
		}
		w = rw
		closeFn = func() { rw.Close() }
	}
	var lvl slog.Level
	switch cfg.Log.Level {
	case "debug":
		lvl = slog.LevelDebug
	case "warn":
		lvl = slog.LevelWarn
	case "error":
		lvl = slog.LevelError
	default:
		lvl = slog.LevelInfo
	}
	h := slog.NewTextHandler(w, &slog.HandlerOptions{Level: lvl})
	return slog.New(h), closeFn, nil
}
