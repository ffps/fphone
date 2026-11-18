package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"text/tabwriter"
	"time"

	"golang.org/x/term"
)

func fmtTime(ts int64) string {
	if ts <= 0 {
		return "-"
	}
	return time.Unix(ts, 0).Format("2006-01-02 15:04")
}

// readPassword reads a password without echo from a terminal, or one line when input is piped.
func readPassword(prompt string) (string, error) {
	fd := int(os.Stdin.Fd())
	if term.IsTerminal(fd) {
		fmt.Fprint(os.Stderr, prompt)
		b, err := term.ReadPassword(fd)
		fmt.Fprintln(os.Stderr)
		if err != nil {
			return "", err
		}
		return string(b), nil
	}
	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil && line == "" {
		return "", err
	}
	return strings.TrimRight(line, "\r\n"), nil
}

// readNewPassword asks for a new password. There is no minimum length on purpose
// (the administrator chooses passwords); the only limits are "not empty" and "at most 256 bytes".
func readNewPassword() (string, error) {
	p1, err := readPassword("Password: ")
	if err != nil {
		return "", err
	}
	if p1 == "" {
		return "", errors.New("password must not be empty")
	}
	if len(p1) > 256 {
		return "", errors.New("password is too long (maximum 256 bytes)")
	}
	if term.IsTerminal(int(os.Stdin.Fd())) {
		p2, err := readPassword("Repeat password: ")
		if err != nil {
			return "", err
		}
		if p1 != p2 {
			return "", errors.New("passwords do not match")
		}
	}
	return p1, nil
}

func cmdUser(cfg *Config, args []string) error {
	if len(args) == 0 {
		return errors.New("user: a subcommand is required (add, list, name, passwd, disable, enable, del)")
	}
	store, err := OpenStore(cfg.DataDir, cfg.tokenTTL())
	if err != nil {
		return err
	}
	defer store.Close()
	sub, rest := args[0], args[1:]
	switch sub {
	case "add":
		return userAdd(store, rest)
	case "list":
		return userList(store)
	case "name":
		return userName(store, rest)
	case "passwd":
		return userPasswd(store, rest)
	case "disable":
		return userDisable(store, rest, true)
	case "enable":
		return userDisable(store, rest, false)
	case "del":
		return userDel(store, rest)
	}
	return fmt.Errorf("user: unknown subcommand %q", sub)
}

func userAdd(store *Store, args []string) error {
	fs := flag.NewFlagSet("user add", flag.ContinueOnError)
	name := fs.String("name", "", "display name")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return errors.New("usage: fphone user add [-name NAME] LOGIN")
	}
	login := fs.Arg(0)
	if !validLogin(login) {
		return errors.New("login must be 1-64 characters: first character A-Z, a-z or _, then A-Z, a-z, 0-9 or _")
	}
	pw, err := readNewPassword()
	if err != nil {
		return err
	}
	hash, err := HashPassword(pw)
	if err != nil {
		return err
	}
	dn := strings.TrimSpace(*name)
	if dn == "" {
		dn = login
	}
	if err := store.CreateUser(login, dn, hash); err != nil {
		if errors.Is(err, ErrExists) {
			return fmt.Errorf("user %q already exists", login)
		}
		if errors.Is(err, ErrNameTaken) {
			return fmt.Errorf("display name %q is already used by another user", dn)
		}
		return err
	}
	fmt.Printf("user %q created\n", login)
	return nil
}

func userList(store *Store) error {
	users, err := store.ListUsers()
	if err != nil {
		return err
	}
	w := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(w, "ID\tLOGIN\tNAME\tSTATUS\tDEVICES\tLAST ACTIVE")
	for _, u := range users {
		st := "active"
		if u.Disabled {
			st = "disabled"
		}
		fmt.Fprintf(w, "%d\t%s\t%s\t%s\t%d\t%s\n", u.ID, u.Login, u.Name, st, u.Devices, fmtTime(u.LastSeen))
	}
	return w.Flush()
}

func userName(store *Store, args []string) error {
	if len(args) < 1 || len(args) > 2 {
		return errors.New("usage: fphone user name LOGIN [NAME] (omit NAME to show current name)")
	}
	u, _, err := store.UserByLogin(args[0])
	if err != nil {
		if errors.Is(err, ErrNotFound) {
			return fmt.Errorf("user %q not found", args[0])
		}
		return err
	}
	if len(args) == 1 {
		fmt.Println(u.Name)
		return nil
	}
	name := strings.TrimSpace(args[1])
	if name == "" || len([]rune(name)) > 64 {
		return errors.New("name must contain 1-64 characters")
	}
	if err := store.SetName(u.ID, name); err != nil {
		if errors.Is(err, ErrNameTaken) {
			return fmt.Errorf("display name %q is already used by another user", name)
		}
		return err
	}
	fmt.Printf("display name for %q changed to %q\n", u.Login, name)
	return nil
}

func userPasswd(store *Store, args []string) error {
	if len(args) != 1 {
		return errors.New("usage: fphone user passwd LOGIN")
	}
	login := args[0]
	pw, err := readNewPassword()
	if err != nil {
		return err
	}
	hash, err := HashPassword(pw)
	if err != nil {
		return err
	}
	if err := store.SetPassword(login, hash); err != nil {
		if errors.Is(err, ErrNotFound) {
			return fmt.Errorf("user %q not found", login)
		}
		return err
	}
	fmt.Printf("password changed; all devices of user %q are logged out (a new login is required)\n", login)
	return nil
}

func userDisable(store *Store, args []string, disabled bool) error {
	if len(args) != 1 {
		return errors.New("usage: fphone user disable|enable LOGIN")
	}
	if err := store.SetDisabled(args[0], disabled); err != nil {
		if errors.Is(err, ErrNotFound) {
			return fmt.Errorf("user %q not found", args[0])
		}
		return err
	}
	if disabled {
		fmt.Println("user disabled (open connections are closed within a minute)")
	} else {
		fmt.Println("user enabled")
	}
	return nil
}

func userDel(store *Store, args []string) error {
	fs := flag.NewFlagSet("user del", flag.ContinueOnError)
	yes := fs.Bool("y", false, "do not ask for confirmation")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if fs.NArg() != 1 {
		return errors.New("usage: fphone user del [-y] LOGIN")
	}
	login := fs.Arg(0)
	if !*yes {
		fmt.Fprintf(os.Stderr, "Delete user %q and all their devices? [y/N] ", login)
		line, _ := bufio.NewReader(os.Stdin).ReadString('\n')
		a := strings.ToLower(strings.TrimSpace(line))
		if a != "y" && a != "yes" {
			fmt.Println("cancelled")
			return nil
		}
	}
	if err := store.DeleteUser(login); err != nil {
		if errors.Is(err, ErrNotFound) {
			return fmt.Errorf("user %q not found", login)
		}
		return err
	}
	fmt.Println("user deleted")
	return nil
}

func cmdDevice(cfg *Config, args []string) error {
	if len(args) == 0 {
		return errors.New("device: a subcommand is required (list, log, revoke)")
	}
	store, err := OpenStore(cfg.DataDir, cfg.tokenTTL())
	if err != nil {
		return err
	}
	defer store.Close()
	switch args[0] {
	case "list":
		login := ""
		if len(args) > 1 {
			login = args[1]
		}
		devs, err := store.ListDevices(login)
		if err != nil {
			return err
		}
		w := tabwriter.NewWriter(os.Stdout, 0, 4, 2, ' ', 0)
		fmt.Fprintln(w, "ID\tUSER\tDEVICE\tPUSH\tCREATED\tLAST ACTIVE\tTOKEN UNTIL")
		for _, d := range devs {
			push := "no"
			if d.HasFCM {
				push = "yes"
			}
			fmt.Fprintf(w, "%d\t%s\t%s\t%s\t%s\t%s\t%s\n", d.ID, d.UserLogin, d.Name, push,
				fmtTime(d.CreatedAt), fmtTime(d.LastSeen), fmtTime(d.ExpiresAt))
		}
		return w.Flush()
	case "log":
		if len(args) != 2 {
			return errors.New("usage: fphone device log ID")
		}
		id, err := strconv.ParseInt(args[1], 10, 64)
		if err != nil || id <= 0 {
			return fmt.Errorf("invalid device ID %q", args[1])
		}
		devices, err := store.ListDevices("")
		if err != nil {
			return err
		}
		found := false
		for _, d := range devices {
			if d.ID == id {
				found = true
				break
			}
		}
		if !found {
			return fmt.Errorf("device %d not found", id)
		}
		logText, err := requestDeviceLog(cfg, id)
		if err != nil {
			return err
		}
		path := filepath.Join(cfg.DataDir, fmt.Sprintf("%d.log", id))
		if err := os.WriteFile(path, []byte(logText), 0600); err != nil {
			return err
		}
		fmt.Printf("device log saved: %s\n", path)
		return nil
	case "revoke":
		if len(args) != 2 {
			return errors.New("usage: fphone device revoke ID")
		}
		id, err := strconv.ParseInt(args[1], 10, 64)
		if err != nil {
			return fmt.Errorf("invalid ID %q", args[1])
		}
		if err := store.DeleteDevice(id); err != nil {
			if errors.Is(err, ErrNotFound) {
				return fmt.Errorf("device %d not found", id)
			}
			return err
		}
		fmt.Println("device token revoked (an open connection is closed within a minute)")
		return nil
	}
	return fmt.Errorf("device: unknown subcommand %q", args[0])
}

func cmdFingerprint(cfg *Config) error {
	if cfg.TLS.Mode == "off" {
		return errors.New("tls.mode is off: the server does not use its own certificate")
	}
	certFile, _ := tlsFiles(cfg)
	if _, err := os.Stat(certFile); err != nil {
		return fmt.Errorf("the certificate does not exist yet (%s): start the server once", certFile)
	}
	hexFP, pin, err := spkiPin(certFile)
	if err != nil {
		return err
	}
	fmt.Println("certificate:    ", certFile)
	fmt.Println("SHA-256 (hex):  ", hexFP)
	fmt.Println("pin for clients:", pin)
	return nil
}

// cmdPushTest checks the FCM setup. Without a token it uses a fake one: FCM then answers with
// an error about the token, which proves that the credentials and the connection are fine.
// With a real token (from an app that uses Firebase) it sends a real test message.
func cmdPushTest(cfg *Config, args []string) error {
	fs := flag.NewFlagSet("push-test", flag.ContinueOnError)
	validate := fs.Bool("validate-only", false, "ask FCM to check the message without delivering it")
	ttl := fs.Duration("ttl", time.Minute, "how long FCM keeps the message while the phone is unreachable")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if cfg.FCM.CredentialsFile == "" {
		return errors.New("fcm.credentials_file is not set in the config")
	}
	push, err := NewFCM(cfg)
	if err != nil {
		return err
	}
	push.validateOnly = *validate
	var token, title, body string
	var deviceID int64
	var login string
	for _, a := range fs.Args() {
		k, v, ok := strings.Cut(a, "=")
		if !ok {
			if token != "" {
				return errors.New("only one TOKEN may be specified")
			}
			token = a
			continue
		}
		switch k {
		case "id":
			deviceID, err = strconv.ParseInt(v, 10, 64)
			if err != nil || deviceID <= 0 {
				return fmt.Errorf("invalid device ID %q", v)
			}
		case "login":
			login = v
		case "title":
			title = v
		case "text":
			body = v
		default:
			return fmt.Errorf("unknown argument %q", k)
		}
	}
	if deviceID > 0 && login != "" {
		return errors.New("use either id=ID or login=LOGIN, not both")
	}
	if (deviceID > 0 || login != "") && token != "" {
		return errors.New("do not combine TOKEN with id=ID or login=LOGIN")
	}
	if title == "" {
		title = "fPhone test"
	}
	if body == "" {
		body = "hello from fphone"
	}
	type target struct {
		id           int64
		login, token string
	}
	targets := []target{}
	dummy := false
	if deviceID > 0 || login != "" {
		store, e := OpenStore(cfg.DataDir, cfg.tokenTTL())
		if e != nil {
			return e
		}
		defer store.Close()
		if deviceID > 0 {
			t, l, e := store.PushTargetByDevice(deviceID)
			if e != nil {
				if errors.Is(e, ErrNotFound) {
					return fmt.Errorf("device %d not found", deviceID)
				}
				return e
			}
			if t.Token == "" {
				return fmt.Errorf("device %d has no FCM token", deviceID)
			}
			targets = append(targets, target{t.DeviceID, l, t.Token})
		} else {
			u, _, e := store.UserByLogin(login)
			if e != nil {
				if errors.Is(e, ErrNotFound) {
					return fmt.Errorf("user %q not found", login)
				}
				return e
			}
			ts, e := store.PushTargets(u.ID)
			if e != nil {
				return e
			}
			if len(ts) == 0 {
				return fmt.Errorf("user %q has no devices with FCM tokens", login)
			}
			for _, t := range ts {
				targets = append(targets, target{t.DeviceID, u.Login, t.Token})
			}
		}
	} else if token != "" {
		targets = append(targets, target{0, "manual", token})
	} else {
		token = "fphone-test-token-that-does-not-exist"
		dummy = true
		targets = append(targets, target{0, "dummy", token})
		fmt.Println("no target given: using a fake token (credentials and connection test only)")
	}
	fmt.Println("project:", push.project)
	for _, t := range targets {
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		messageID, e := push.SendWithResult(ctx, t.token, map[string]string{"type": "test", "title": title, "text": body, "time": strconv.FormatInt(time.Now().Unix(), 10)}, "fphone-test", *ttl)
		cancel()
		if e == nil {
			if t.id > 0 {
				fmt.Printf("OK: device %d (%s), FCM message %s\n", t.id, t.login, messageID)
			} else {
				fmt.Printf("OK: FCM accepted message %s\n", messageID)
			}
			continue
		}
		if errors.Is(e, ErrTokenInvalid) && dummy {
			fmt.Println("OK: credentials and connection work (FCM rejected the expected fake token).")
			continue
		}
		if errors.Is(e, ErrTokenInvalid) {
			return fmt.Errorf("FCM does not know the token for %s (UNREGISTERED)", t.login)
		}
		if dummy && strings.Contains(e.Error(), "HTTP 400") {
			fmt.Println("OK: credentials and connection work (FCM rejected the expected fake token).")
			continue
		}
		return e
	}
	if *validate {
		fmt.Println("validate-only: no message was delivered")
	}
	return nil
}

func explainPushResult(err error, dummy, validateOnly bool) error {
	switch {
	case err == nil:
		if dummy {
			fmt.Println("unexpected: FCM accepted a fake token")
		} else if validateOnly {
			fmt.Println("OK: FCM accepts this message and token (validate-only: nothing was delivered).")
		} else {
			fmt.Println("OK: FCM accepted the message (HTTP 200). It should reach the phone within seconds.")
		}
		return nil
	case errors.Is(err, ErrTokenInvalid):
		if dummy {
			fmt.Println("OK: credentials and connection work (FCM answered that the token is not registered, as expected for a fake token).")
			return nil
		}
		return errors.New("FCM does not know this token (UNREGISTERED): the app was reinstalled, or the token belongs to another Firebase project")
	}

	msg := err.Error()
	if dummy && strings.Contains(msg, "HTTP 400") {
		fmt.Println("OK: credentials and connection work (FCM answered 400 INVALID_ARGUMENT for the fake token, as expected).")
		return nil
	}
	switch {
	case strings.Contains(msg, "HTTP 401") || strings.Contains(msg, "HTTP 403"):
		fmt.Println("hint: Google accepted the key but does not allow sending. Check that the Firebase Cloud Messaging API (V1) is enabled for the project and that the service account may send messages.")
	case strings.Contains(msg, "oauth2"):
		fmt.Println("hint: could not get an access token from Google. Check the system time (NTP), outgoing HTTPS to oauth2.googleapis.com, and that the key file is complete and was not revoked.")
	case strings.Contains(msg, "HTTP 400"):
		fmt.Println("hint: FCM rejected the message or the token as invalid.")
	default:
		fmt.Println("hint: no valid answer from Google. Check outgoing HTTPS to fcm.googleapis.com and oauth2.googleapis.com.")
	}
	return err
}
