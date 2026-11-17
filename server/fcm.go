package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"
)

// FCM sends data messages through the Firebase Cloud Messaging HTTP v1 API.
// It authenticates with the service account file (OAuth2 access tokens are created
// and refreshed automatically). The server needs outgoing HTTPS access to
// oauth2.googleapis.com and fcm.googleapis.com.

const fcmScope = "https://www.googleapis.com/auth/firebase.messaging"

// ErrTokenInvalid means that FCM does not know the push token any more
// (for example, the app was uninstalled). The token should be removed.
var ErrTokenInvalid = errors.New("push token is not registered")

type FCM struct {
	client   *http.Client
	url      string
	project  string
	sendName bool // put the caller name into the push (it travels through Google servers)

	validateOnly bool // test mode: FCM checks the message but does not deliver it
}

func NewFCM(cfg *Config) (*FCM, error) {
	data, err := os.ReadFile(cfg.FCM.CredentialsFile)
	if err != nil {
		return nil, fmt.Errorf("fcm.credentials_file: %w", err)
	}
	ctx := context.Background()
	creds, err := google.CredentialsFromJSON(ctx, data, fcmScope)
	if err != nil {
		return nil, fmt.Errorf("fcm.credentials_file: %w", err)
	}
	project := cfg.FCM.ProjectID
	if project == "" {
		project = creds.ProjectID
	}
	if project == "" {
		return nil, errors.New("fcm: project_id was not found in the credentials file, set fcm.project_id")
	}
	client := oauth2.NewClient(ctx, creds.TokenSource)
	client.Timeout = 10 * time.Second
	return &FCM{
		client:   client,
		url:      fmt.Sprintf("https://fcm.googleapis.com/v1/projects/%s/messages:send", project),
		project:  project,
		sendName: cfg.FCM.SendCallerName,
	}, nil
}

// Send sends one high-priority data message. All values in data must be strings.
// Messages with the same collapseKey replace each other while they wait for an offline phone.
func (f *FCM) Send(ctx context.Context, token string, data map[string]string, collapseKey string, ttl time.Duration) error {
	payload := map[string]any{
		"message": map[string]any{
			"token": token,
			"data":  data,
			"android": map[string]any{
				"priority":     "HIGH",
				"ttl":          fmt.Sprintf("%ds", int(ttl.Seconds())),
				"collapse_key": collapseKey,
			},
		},
	}
	if f.validateOnly {
		payload["validate_only"] = true
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, f.url, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json; charset=utf-8")
	resp, err := f.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	if resp.StatusCode == http.StatusOK {
		return nil
	}
	var er struct {
		Error struct {
			Status  string `json:"status"`
			Message string `json:"message"`
			Details []struct {
				ErrorCode string `json:"errorCode"`
			} `json:"details"`
		} `json:"error"`
	}
	json.Unmarshal(b, &er)
	for _, d := range er.Error.Details {
		if d.ErrorCode == "UNREGISTERED" {
			return ErrTokenInvalid
		}
	}
	return fmt.Errorf("FCM HTTP %d %s: %s", resp.StatusCode, er.Error.Status, er.Error.Message)
}
