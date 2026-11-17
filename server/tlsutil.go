package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/pem"
	"fmt"
	"log/slog"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"time"
)

// tlsFiles returns the certificate and key paths for the current tls.mode.
func tlsFiles(cfg *Config) (certFile, keyFile string) {
	switch cfg.TLS.Mode {
	case "self-signed":
		return filepath.Join(cfg.DataDir, "tls.crt"), filepath.Join(cfg.DataDir, "tls.key")
	case "files":
		return cfg.TLS.Cert, cfg.TLS.Key
	}
	return "", ""
}

// ensureTLS creates a self-signed certificate if needed and checks that the files can be loaded.
func ensureTLS(cfg *Config) (certFile, keyFile string, err error) {
	certFile, keyFile = tlsFiles(cfg)
	if cfg.TLS.Mode == "self-signed" {
		_, e1 := os.Stat(certFile)
		_, e2 := os.Stat(keyFile)
		if e1 != nil || e2 != nil {
			if err := os.MkdirAll(cfg.DataDir, 0o700); err != nil {
				return "", "", err
			}
			if err := generateSelfSigned(certFile, keyFile, cfg.PublicHost); err != nil {
				return "", "", fmt.Errorf("create certificate: %w", err)
			}
			slog.Info("self-signed certificate created", "cert", certFile)
		}
	}
	pair, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return "", "", fmt.Errorf("TLS: %w", err)
	}
	leaf, err := x509.ParseCertificate(pair.Certificate[0])
	if err != nil {
		return "", "", fmt.Errorf("TLS: %w", err)
	}
	if cfg.PublicHost != "" {
		if err := leaf.VerifyHostname(cfg.PublicHost); err != nil {
			slog.Warn("public_host is not in the certificate (SAN)",
				"host", cfg.PublicHost,
				"hint", "for self-signed: delete tls.crt and tls.key in data_dir to create them again (clients must then pin the new key)")
		}
	}
	return certFile, keyFile, nil
}

func generateSelfSigned(certFile, keyFile, host string) error {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 127))
	if err != nil {
		return err
	}
	tpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "fphone"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		DNSNames:              []string{"localhost"},
		IPAddresses:           []net.IP{net.ParseIP("127.0.0.1")},
	}
	if host != "" {
		if ip := net.ParseIP(host); ip != nil {
			tpl.IPAddresses = append(tpl.IPAddresses, ip)
		} else {
			tpl.DNSNames = append(tpl.DNSNames, host)
		}
	}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	if err != nil {
		return err
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return err
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})
	if err := os.WriteFile(keyFile, keyPEM, 0o600); err != nil {
		return err
	}
	return os.WriteFile(certFile, certPEM, 0o644)
}

// spkiPin returns the SHA-256 of the certificate public key (SubjectPublicKeyInfo),
// as hex and as "sha256/<base64>", which is the format OkHttp CertificatePinner uses.
func spkiPin(certFile string) (hexFP, pin string, err error) {
	b, err := os.ReadFile(certFile)
	if err != nil {
		return "", "", err
	}
	block, _ := pem.Decode(b)
	if block == nil {
		return "", "", fmt.Errorf("no PEM certificate in %s", certFile)
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return "", "", err
	}
	sum := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
	return hex.EncodeToString(sum[:]), "sha256/" + base64.StdEncoding.EncodeToString(sum[:]), nil
}
