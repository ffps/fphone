package main

import (
	"context"
	_ "embed"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

// The version is set at build time: go build -ldflags "-X main.version=0.3.17"
var version = "0.3.17"

//go:embed fphone.example.yaml
var exampleConfig []byte

func usage() {
	fmt.Fprint(os.Stderr, `fphone - family video call server

Usage: fphone [-c fphone.yaml] <command>

  serve                        run the server
  user add [-name NAME] LOGIN  create a user (the password is asked interactively)
  user list                    list users
  user passwd LOGIN            change a password (all devices of the user are logged out)
  user disable|enable LOGIN    block / unblock a user
  user del [-y] LOGIN          delete a user
  device list [LOGIN]          list devices
  device revoke ID             revoke the token of a device
  device log ID                download the diagnostic log from an online phone
  user name LOGIN [NAME]       show or change a user's display name
  fingerprint                  show the certificate fingerprint to pin in the client
  push-test [-validate-only] [TOKEN|id=ID|login=LOGIN] [title=TITLE] [text=TEXT]
                               send a test push to a token, device, or all devices of a user
  generate-vapid               generate a Web Push VAPID key pair
  example-config               print an example config
  version                      print the version

The -c flag must be given BEFORE the command.
`)
}

func main() {
	cfgPath := flag.String("c", "fphone.yaml", "path to the config file")
	flag.Usage = usage
	flag.Parse()
	args := flag.Args()
	if len(args) == 0 {
		usage()
		os.Exit(2)
	}

	switch args[0] {
	case "version":
		fmt.Println(version)
		return
	case "generate-vapid":
		publicKey, privateKey, err := GenerateVAPIDKeys()
		if err != nil {
			fmt.Fprintln(os.Stderr, "error:", err)
			os.Exit(1)
		}
		fmt.Println("web_push:")
		fmt.Printf("  public_key: %q\n", publicKey)
		fmt.Printf("  private_key: %q\n", privateKey)
		fmt.Println("  subject: \"mailto:admin@example.com\"")
		return
	case "example-config":
		os.Stdout.Write(exampleConfig)
		return
	case "serve", "user", "device", "fingerprint", "push-test":
	default:
		usage()
		os.Exit(2)
	}

	cfg, err := LoadConfig(*cfgPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}

	switch args[0] {
	case "serve":
		err = cmdServe(cfg)
	case "user":
		err = cmdUser(cfg, args[1:])
	case "device":
		err = cmdDevice(cfg, args[1:])
	case "fingerprint":
		err = cmdFingerprint(cfg)
	case "push-test":
		err = cmdPushTest(cfg, args[1:])
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func cmdServe(cfg *Config) error {
	logger, closeLog, err := setupLogging(cfg)
	if err != nil {
		return err
	}
	defer closeLog()
	slog.SetDefault(logger)

	store, err := OpenStore(cfg.DataDir, cfg.tokenTTL())
	if err != nil {
		return fmt.Errorf("database: %w", err)
	}
	defer store.Close()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	hub := NewHub(store, cfg)
	calls := NewCalls(cfg, store, hub)
	hub.SetCalls(calls)
	if err := startControlServer(ctx, cfg, hub); err != nil {
		return err
	}

	if cfg.Turn.Enabled {
		relay, err := NewRelayIP(cfg)
		if err != nil {
			return fmt.Errorf("TURN: %w", err)
		}
		go relay.Run(ctx, time.Duration(cfg.Turn.RelayIPRefreshSeconds)*time.Second)
		turnSrv, err := StartTurn(cfg, relay, calls.AllowTurn)
		if err != nil {
			return fmt.Errorf("TURN: %w", err)
		}
		defer turnSrv.Close()
		calls.SetICE(turnSrv)
		slog.Info("TURN started", "port", cfg.Turn.ListenPort,
			"relay_ip", relay.Get().String(), "relay_ip_follows_dns", relay.Dynamic(),
			"relay_ports", fmt.Sprintf("%d-%d", cfg.Turn.RelayPortMin, cfg.Turn.RelayPortMax),
			"ice_policy", cfg.Turn.ICEPolicy)
	} else {
		slog.Warn("TURN is disabled: calls will work only when both phones can reach each other directly")
	}

	var fcm *FCM
	if cfg.FCM.CredentialsFile != "" {
		fcm, err = NewFCM(cfg)
		if err != nil {
			return err
		}
		slog.Info("push (FCM) enabled", "project", fcm.project, "send_caller_name", fcm.sendName)
	} else {
		slog.Warn("push (FCM) is disabled")
	}
	var webPush *WebPush
	if cfg.WebPush.PublicKey != "" {
		webPush, err = NewWebPush(cfg.WebPush)
		if err != nil {
			return err
		}
		slog.Info("push (Web Push) enabled")
	} else {
		slog.Warn("push (Web Push) is disabled")
	}
	calls.SetPush(fcm, webPush)

	srv, err := NewServer(cfg, store, hub)
	if err != nil {
		return err
	}

	var certFile, keyFile string
	if cfg.TLS.Mode != "off" {
		certFile, keyFile, err = ensureTLS(cfg)
		if err != nil {
			return err
		}
		if _, pin, perr := spkiPin(certFile); perr == nil {
			slog.Info("certificate pin for clients", "pin", pin)
		}
	} else {
		slog.Warn("TLS is off: use this mode only behind an HTTPS proxy (for example, Keenetic)")
	}

	httpSrv := &http.Server{
		Addr:              cfg.Listen,
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	go hub.Run(ctx)

	errCh := make(chan error, 1)
	go func() {
		if cfg.TLS.Mode == "off" {
			errCh <- httpSrv.ListenAndServe()
		} else {
			errCh <- httpSrv.ListenAndServeTLS(certFile, keyFile)
		}
	}()
	slog.Info("fphone started", "version", version, "listen", cfg.Listen, "tls", cfg.TLS.Mode, "data", cfg.DataDir)

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
	}
	slog.Info("shutting down")
	sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	httpSrv.Shutdown(sctx)
	hub.CloseAll()
	return nil
}
