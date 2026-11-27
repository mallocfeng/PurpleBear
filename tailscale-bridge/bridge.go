// Package tailscalebridge embeds a Tailscale userspace node for the Android
// application. It deliberately does not create an Android VpnService: Xray
// owns the only system TUN while this package exposes a loopback SOCKS5 proxy
// for tailnet traffic.
package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
	"unsafe"

	"tailscale.com/envknob"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/netmon"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
)

// Config is supplied by the Kotlin runtime as JSON.
type Config struct {
	StateDir      string            `json:"stateDir"`
	Hostname      string            `json:"hostname"`
	AuthKey       string            `json:"authKey"`
	ControlURL    string            `json:"controlUrl"`
	AlwaysUseDERP bool              `json:"alwaysUseDerp"`
	Interfaces    []interfaceConfig `json:"interfaces"`
}

// interfaceConfig is supplied from Android's ConnectivityManager. Android
// apps cannot query interfaces through Go's net.Interfaces on recent SDKs,
// so Tailscale explicitly provides RegisterInterfaceGetter for this case.
type interfaceConfig struct {
	Name      string   `json:"name"`
	MTU       int      `json:"mtu"`
	Addresses []string `json:"addresses"`
}

// Bridge owns one in-process Tailscale node and its loopback SOCKS proxy.
type Bridge struct {
	mu        sync.Mutex
	server    *tsnet.Server
	started   bool
	lastError string
	proxyAddr string
	proxyPass string
	// Tailnet listeners by tailnet port (the MCP server, the remote panel).
	forwards map[int]net.Listener
}

// NewBridge returns a new bridge instance.
func NewBridge() *Bridge { return new(Bridge) }

// Start authenticates the node (when needed) and returns a JSON snapshot.
func (b *Bridge) Start(configJSON string) (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.started {
		return b.statusJSONLocked()
	}
	var cfg Config
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return "", fmt.Errorf("invalid Tailscale configuration: %w", err)
	}
	if strings.TrimSpace(cfg.StateDir) == "" {
		return "", errors.New("Tailscale state directory is required")
	}
	if strings.TrimSpace(cfg.AuthKey) == "" && !hasState(cfg.StateDir) {
		return "", errors.New("请输入 Tailscale 认证密钥")
	}
	if err := os.MkdirAll(cfg.StateDir, 0o700); err != nil {
		return "", fmt.Errorf("create Tailscale state directory: %w", err)
	}
	// Android native processes do not define HOME/XDG/TMPDIR. Tailscale's
	// logpolicy requires one of these to select a safe private directory and
	// deliberately panics if none exists. Keep every runtime path under the
	// app-owned state directory supplied by Kotlin.
	cacheDir := filepath.Join(cfg.StateDir, "cache")
	configDir := filepath.Join(cfg.StateDir, "config")
	tempDir := filepath.Join(cfg.StateDir, "tmp")
	for _, dir := range []string{cacheDir, configDir, tempDir} {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return "", fmt.Errorf("create Tailscale runtime directory: %w", err)
		}
	}
	_ = os.Setenv("HOME", cfg.StateDir)
	_ = os.Setenv("XDG_CACHE_HOME", cacheDir)
	_ = os.Setenv("XDG_CONFIG_HOME", configDir)
	_ = os.Setenv("TMPDIR", tempDir)
	registerAndroidInterfaces(cfg.Interfaces)

	// The upstream knob is process-global. The Android app only owns a single
	// bridge, and settings changes fully restart it before this line runs.
	if cfg.AlwaysUseDERP {
		envknob.Setenv("TS_DEBUG_ALWAYS_USE_DERP", "true")
	} else {
		envknob.Setenv("TS_DEBUG_ALWAYS_USE_DERP", "false")
	}

	hostname := strings.TrimSpace(cfg.Hostname)
	if hostname == "" {
		hostname = "mallocgfw-android"
	}
	b.server = &tsnet.Server{
		Dir:        cfg.StateDir,
		Hostname:   hostname,
		AuthKey:    strings.TrimSpace(cfg.AuthKey),
		ControlURL: strings.TrimSpace(cfg.ControlURL),
		Logf: func(format string, args ...any) {
			log.Printf("tailscale: "+format, args...)
		},
		UserLogf: func(format string, args ...any) {
			log.Printf("tailscale: "+format, args...)
		},
	}

	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	if _, err := b.server.Up(ctx); err != nil {
		b.lastError = err.Error()
		_ = b.server.Close()
		b.server = nil
		return "", fmt.Errorf("Tailscale 连接失败: %w", err)
	}
	addr, pass, _, err := b.server.Loopback()
	if err != nil {
		b.lastError = err.Error()
		_ = b.server.Close()
		b.server = nil
		return "", fmt.Errorf("启动 Tailscale 本地 SOCKS5 失败: %w", err)
	}
	b.started = true
	b.lastError = ""
	b.proxyAddr = addr
	b.proxyPass = pass
	return b.statusJSONLocked()
}

func registerAndroidInterfaces(configs []interfaceConfig) {
	interfaces := make([]netmon.Interface, 0, len(configs))
	for index, cfg := range configs {
		name := strings.TrimSpace(cfg.Name)
		if name == "" {
			continue
		}
		mtu := cfg.MTU
		if mtu <= 0 {
			mtu = 1500
		}
		iface := netmon.Interface{
			Interface: &net.Interface{
				Index: index + 1,
				MTU:   mtu,
				Name:  name,
				Flags: net.FlagUp | net.FlagBroadcast | net.FlagMulticast,
			},
			AltAddrs: []net.Addr{},
		}
		for _, value := range cfg.Addresses {
			ip, network, err := net.ParseCIDR(strings.TrimSpace(value))
			if err != nil {
				continue
			}
			network.IP = ip
			iface.AltAddrs = append(iface.AltAddrs, network)
		}
		interfaces = append(interfaces, iface)
	}
	if len(interfaces) == 0 {
		interfaces = append(interfaces, netmon.Interface{
			Interface: &net.Interface{
				Index: 1,
				MTU:   1500,
				Name:  "android0",
				Flags: net.FlagUp | net.FlagMulticast,
			},
			AltAddrs: []net.Addr{},
		})
	}
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		return interfaces, nil
	})
}

// Stop closes the embedded node but deliberately preserves its authenticated
// state so the next start does not need the auth key again.
func (b *Bridge) Stop() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.closeForwardLocked()
	if b.server != nil {
		if err := b.server.Close(); err != nil {
			return err
		}
	}
	b.server = nil
	b.started = false
	b.proxyAddr = ""
	b.proxyPass = ""
	return nil
}

// Status returns a JSON snapshot suitable for Kotlin state updates.
func (b *Bridge) Status() (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.statusJSONLocked()
}

// SetExitNode selects an exit node by its Tailscale StableID. An empty ID
// clears the selection. Packet-routing policy remains controlled by Kotlin.
func (b *Bridge) SetExitNode(stableID string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.server == nil {
		return errors.New("Tailscale 尚未启动")
	}
	lc, err := b.server.LocalClient()
	if err != nil {
		return err
	}
	_, err = lc.EditPrefs(context.Background(), &ipn.MaskedPrefs{
		ExitNodeIDSet: true,
		Prefs: ipn.Prefs{
			ExitNodeID: tailcfg.StableNodeID(strings.TrimSpace(stableID)),
		},
	})
	return err
}

// Logout removes the local node identity. It should only be used after Stop.
func (b *Bridge) Logout(stateDir string) error {
	if err := b.Stop(); err != nil {
		return err
	}
	if strings.TrimSpace(stateDir) == "" {
		return errors.New("Tailscale state directory is required")
	}
	return os.RemoveAll(stateDir)
}

// forwardConfig asks the bridge to accept TCP connections on a port of the
// node's tailnet address and hand them to an app-local loopback port (the MCP
// server or the remote panel). Each forwarded connection starts with one line,
//
//	PBTN1 <secret> <base64url JSON {"user","device","addr"}>\n
//
// naming the tailnet peer as resolved by WhoIs. The secret proves to the app
// that the line came from this bridge and not from another local process.
// With Stop set, the listener on TailnetPort is closed instead.
type forwardConfig struct {
	TailnetPort int    `json:"tailnetPort"`
	LocalPort   int    `json:"localPort"`
	Secret      string `json:"secret"`
	Stop        bool   `json:"stop"`
}

// ServeForward starts (or replaces) the tailnet listener on one port, or
// closes it when the configuration says stop. Other ports are left alone.
func (b *Bridge) ServeForward(configJSON string) error {
	var cfg forwardConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return fmt.Errorf("invalid forward configuration: %w", err)
	}
	if cfg.TailnetPort <= 0 || cfg.TailnetPort > 65535 {
		return errors.New("invalid forward configuration")
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if cfg.Stop {
		b.closeForwardPortLocked(cfg.TailnetPort)
		return nil
	}
	if cfg.LocalPort <= 0 || cfg.LocalPort > 65535 || len(cfg.Secret) < 16 {
		return errors.New("invalid forward configuration")
	}
	if b.server == nil {
		return errors.New("Tailscale 尚未启动")
	}
	b.closeForwardPortLocked(cfg.TailnetPort)
	lc, err := b.server.LocalClient()
	if err != nil {
		return err
	}
	ln, err := b.server.Listen("tcp", fmt.Sprintf(":%d", cfg.TailnetPort))
	if err != nil {
		return fmt.Errorf("listen on tailnet port %d: %w", cfg.TailnetPort, err)
	}
	if b.forwards == nil {
		b.forwards = map[int]net.Listener{}
	}
	b.forwards[cfg.TailnetPort] = ln
	whois := func(ctx context.Context, remote string) (string, string, error) {
		who, err := lc.WhoIs(ctx, remote)
		if err != nil {
			return "", "", err
		}
		user, device := "", ""
		if who.UserProfile != nil {
			user = who.UserProfile.LoginName
		}
		if who.Node != nil {
			device = who.Node.ComputedName
			if device == "" {
				device = strings.TrimSuffix(who.Node.Name, ".")
			}
		}
		return user, device, nil
	}
	go acceptForward(ln, cfg, whois)
	return nil
}

// StopForward closes every tailnet listener.
func (b *Bridge) StopForward() error {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.closeForwardLocked()
	return nil
}

func (b *Bridge) closeForwardLocked() {
	for port := range b.forwards {
		b.closeForwardPortLocked(port)
	}
}

func (b *Bridge) closeForwardPortLocked(port int) {
	if ln := b.forwards[port]; ln != nil {
		_ = ln.Close()
	}
	delete(b.forwards, port)
}

func acceptForward(ln net.Listener, cfg forwardConfig, whois func(context.Context, string) (string, string, error)) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go handleForward(conn, cfg, whois)
	}
}

func handleForward(conn net.Conn, cfg forwardConfig, whois func(context.Context, string) (string, string, error)) {
	defer conn.Close()
	remote := conn.RemoteAddr().String()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	user, device, err := whois(ctx, remote)
	cancel()
	if err != nil {
		// Not a known tailnet peer: refuse.
		log.Printf("tailscale forward: whois %s: %v", remote, err)
		return
	}
	host, _, _ := net.SplitHostPort(remote)
	payload, _ := json.Marshal(map[string]string{"user": user, "device": device, "addr": host})
	local, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", cfg.LocalPort), 5*time.Second)
	if err != nil {
		log.Printf("tailscale forward: dial local: %v", err)
		return
	}
	defer local.Close()
	if _, err := fmt.Fprintf(local, "PBTN1 %s %s\n", cfg.Secret, base64.RawURLEncoding.EncodeToString(payload)); err != nil {
		return
	}
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(local, conn); closeWrite(local); done <- struct{}{} }()
	go func() { _, _ = io.Copy(conn, local); closeWrite(conn); done <- struct{}{} }()
	<-done
	<-done
}

func closeWrite(c net.Conn) {
	if cw, ok := c.(interface{ CloseWrite() error }); ok {
		_ = cw.CloseWrite()
	}
}

type snapshot struct {
	State          string `json:"state"`
	Message        string `json:"message,omitempty"`
	Hostname       string `json:"hostname,omitempty"`
	DNSName        string `json:"dnsName,omitempty"`
	Tailnet        string `json:"tailnet,omitempty"`
	MagicDNSSuffix string `json:"magicDnsSuffix,omitempty"`
	IPv4           string `json:"ipv4,omitempty"`
	IPv6           string `json:"ipv6,omitempty"`
	SocksHost      string `json:"socksHost,omitempty"`
	SocksPort      int    `json:"socksPort,omitempty"`
	SocksUsername  string `json:"socksUsername,omitempty"`
	SocksPassword  string `json:"socksPassword,omitempty"`
	ExitNodeID     string `json:"exitNodeId,omitempty"`
	Peers          []peer `json:"peers"`
}

type peer struct {
	ID       string   `json:"id"`
	Name     string   `json:"name"`
	DNSName  string   `json:"dnsName"`
	IPv4     string   `json:"ipv4,omitempty"`
	IPv6     string   `json:"ipv6,omitempty"`
	Online   bool     `json:"online"`
	Active   bool     `json:"active"`
	Relay    string   `json:"relay,omitempty"`
	ExitNode bool     `json:"exitNode"`
	CanExit  bool     `json:"canExit"`
	Routes   []string `json:"routes,omitempty"`
}

func (b *Bridge) statusJSONLocked() (string, error) {
	result := snapshot{State: "stopped", Peers: []peer{}}
	if b.server == nil {
		result.Message = b.lastError
		return marshalSnapshot(result)
	}
	lc, err := b.server.LocalClient()
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		return "", err
	}
	result = makeSnapshot(st)
	result.SocksUsername = "tsnet"
	result.SocksPassword = b.proxyPass
	result.SocksHost, result.SocksPort = splitAddress(b.proxyAddr)
	return marshalSnapshot(result)
}

func makeSnapshot(st *ipnstate.Status) snapshot {
	result := snapshot{
		State:          st.BackendState,
		MagicDNSSuffix: st.MagicDNSSuffix,
		Peers:          []peer{},
	}
	if st.CurrentTailnet != nil {
		result.Tailnet = st.CurrentTailnet.Name
		result.MagicDNSSuffix = st.CurrentTailnet.MagicDNSSuffix
	}
	if st.Self != nil {
		result.Hostname = st.Self.HostName
		result.DNSName = strings.TrimSuffix(st.Self.DNSName, ".")
	}
	for _, ip := range st.TailscaleIPs {
		if ip.Is4() && result.IPv4 == "" {
			result.IPv4 = ip.String()
		} else if ip.Is6() && result.IPv6 == "" {
			result.IPv6 = ip.String()
		}
	}
	if st.ExitNodeStatus != nil {
		result.ExitNodeID = string(st.ExitNodeStatus.ID)
	}
	for _, item := range st.Peer {
		p := peer{
			ID:       string(item.ID),
			Name:     item.HostName,
			DNSName:  strings.TrimSuffix(item.DNSName, "."),
			Online:   item.Online,
			Active:   item.Active,
			Relay:    item.Relay,
			ExitNode: item.ExitNode,
			CanExit:  item.ExitNodeOption,
		}
		for _, ip := range item.TailscaleIPs {
			if ip.Is4() && p.IPv4 == "" {
				p.IPv4 = ip.String()
			} else if ip.Is6() && p.IPv6 == "" {
				p.IPv6 = ip.String()
			}
		}
		if item.PrimaryRoutes != nil {
			for index := 0; index < item.PrimaryRoutes.Len(); index++ {
				p.Routes = append(p.Routes, item.PrimaryRoutes.At(index).String())
			}
		}
		result.Peers = append(result.Peers, p)
	}
	sort.Slice(result.Peers, func(i, j int) bool {
		return result.Peers[i].Name < result.Peers[j].Name
	})
	return result
}

func marshalSnapshot(value snapshot) (string, error) {
	data, err := json.Marshal(value)
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func splitAddress(value string) (string, int) {
	if value == "" {
		return "", 0
	}
	if host, port, err := netSplitHostPort(value); err == nil {
		return host, port
	}
	return value, 0
}

func netSplitHostPort(value string) (string, int, error) {
	parts := strings.Split(value, ":")
	if len(parts) < 2 {
		return "", 0, errors.New("missing port")
	}
	var port int
	if _, err := fmt.Sscanf(parts[len(parts)-1], "%d", &port); err != nil {
		return "", 0, err
	}
	return strings.Join(parts[:len(parts)-1], ":"), port, nil
}

func hasState(stateDir string) bool {
	info, err := os.Stat(filepath.Join(stateDir, "tailscaled.state"))
	return err == nil && !info.IsDir()
}

var nativeBridge = NewBridge()

func main() {}

type nativeResponse struct {
	OK    bool            `json:"ok"`
	Value json.RawMessage `json:"value,omitempty"`
	Error string          `json:"error,omitempty"`
}

func nativeCall(fn func() (string, error)) *C.char {
	value, err := fn()
	response := nativeResponse{OK: err == nil}
	if err != nil {
		response.Error = err.Error()
	} else if value != "" {
		response.Value = json.RawMessage(value)
	}
	payload, marshalErr := json.Marshal(response)
	if marshalErr != nil {
		return C.CString(`{"ok":false,"error":"cannot encode native response"}`)
	}
	return C.CString(string(payload))
}

// NativeStart starts the singleton bridge and returns {ok,value,error} JSON.
//
//export NativeStart
func NativeStart(config *C.char) *C.char {
	return nativeCall(func() (string, error) {
		if config == nil {
			return "", errors.New("missing Tailscale configuration")
		}
		return nativeBridge.Start(C.GoString(config))
	})
}

// NativeStatus returns the current bridge status.
//
//export NativeStatus
func NativeStatus() *C.char {
	return nativeCall(nativeBridge.Status)
}

// NativeStop stops the bridge while retaining the device identity.
//
//export NativeStop
func NativeStop() *C.char {
	return nativeCall(func() (string, error) {
		return "", nativeBridge.Stop()
	})
}

// NativeSetExitNode updates the selected exit node.
//
//export NativeSetExitNode
func NativeSetExitNode(stableID *C.char) *C.char {
	return nativeCall(func() (string, error) {
		value := ""
		if stableID != nil {
			value = C.GoString(stableID)
		}
		return "", nativeBridge.SetExitNode(value)
	})
}

// NativeLogout removes the persisted node identity.
//
//export NativeLogout
func NativeLogout(stateDir *C.char) *C.char {
	return nativeCall(func() (string, error) {
		if stateDir == nil {
			return "", errors.New("missing Tailscale state directory")
		}
		return "", nativeBridge.Logout(C.GoString(stateDir))
	})
}

// NativeServeForward starts or stops one tailnet listener (MCP server, remote panel).
//
//export NativeServeForward
func NativeServeForward(config *C.char) *C.char {
	return nativeCall(func() (string, error) {
		if config == nil {
			return "", errors.New("missing forward configuration")
		}
		return "", nativeBridge.ServeForward(C.GoString(config))
	})
}

// NativeStopForward closes every tailnet listener.
//
//export NativeStopForward
func NativeStopForward() *C.char {
	return nativeCall(func() (string, error) {
		return "", nativeBridge.StopForward()
	})
}

// NativeFree releases a string returned by this library.
//
//export NativeFree
func NativeFree(value *C.char) {
	C.free(unsafe.Pointer(value))
}
