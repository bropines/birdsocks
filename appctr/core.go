package appctr

// core.go — the NetBird daemon process: started from the app's native
// library directory (libnetbird.so, built from birdsocksd/main.go), its
// output fed into the Logs screen, stopped on request. The daemon is its
// own process, as tailscaled was in TailSocks: a crash there does not take
// the app with it, and the app reattaches to the socket it leaves.

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// coreVersion is the NetBird release and the commit this bridge was built
// from; build.sh sets it.
var coreVersion = "dev"

// CoreVersion is what the About screen shows for the core.
func CoreVersion() string { return coreVersion }

// StartOptions is how the app starts the daemon.
type StartOptions struct {
	// NativeLibDir holds libnetbird.so (ApplicationInfo.nativeLibraryDir).
	NativeLibDir string
	// DataDir is the app's files directory: the socket, NetBird's state and
	// profiles live under it.
	DataDir string
	// SocksHost, SocksPort, SocksUser, SocksPass: the SOCKS5 proxy the
	// daemon's netstack serves; 127.0.0.1 unless the user shares it on the
	// LAN. Both credentials or neither.
	SocksHost string
	SocksPort int
	SocksUser string
	SocksPass string
	// Hostname is the device name the peer registers and reports.
	Hostname string
	// AndroidVersion, Model, Manufacturer: what the dashboard shows for the
	// device (patch 02).
	AndroidVersion string
	Model          string
	Manufacturer   string
	// AndroidSdk is the API level: from 30 on, interfaces are listed the
	// way an app is allowed to (patch 03).
	AndroidSdk int
	// DNSProxy is where the daemon serves plain DNS (NetBird's resolver
	// first, the network's after), host:port; empty for none.
	DNSProxy string
	// DNSUpstream, comma-separated host:port, answers what NetBird does not;
	// empty for the network's resolvers. The DNS proxy and the VPN's
	// resolver both use it.
	DNSUpstream string
	// TunDNS is the address the app's VPN announces as its DNS server; the
	// SOCKS5 proxy answers queries to it itself (netstack/tundns.go). Set
	// whether or not the VPN runs, so turning it on needs no restart.
	TunDNS string
	// RelayQUIC lets the relay try QUIC; off, it speaks WebSocket only — QUIC
	// is throttled or dropped on many networks (Russia among them), and the
	// race costs every connect a dead QUIC attempt.
	RelayQUIC bool
	// LazyConn overrides the server's lazy connections: "on", "off", or ""
	// to follow the server.
	LazyConn string
	// InboundAccess lets peers reach this device's own services: a
	// connection to its NetBird address goes on to 127.0.0.1 at the same
	// port (adb over Wi-Fi, Termux's sshd, a local web server), as the ACLs
	// allow. The app's own proxies stay out of reach.
	InboundAccess bool
	// LogLevel is NetBird's: panic, fatal, error, warn, info, debug, trace.
	LogLevel string
	// Env is extra NAME=value lines, one per line, for the daemon (NB_*
	// knobs such as NB_FORCE_RELAY); later lines win over ours.
	Env string
}

// DaemonListener hears about the daemon process ending, whether it was
// stopped or died: err is empty for a clean exit.
type DaemonListener interface {
	OnExit(err string)
}

var (
	stateMu  sync.Mutex
	listener DaemonListener
	daemon   *exec.Cmd
	// daemonDone is closed when the running daemon exits.
	daemonDone chan struct{}
	dataDir    string
	startedAt  time.Time

	processHandlesOnce sync.Once
)

func socketPath(dir string) string  { return filepath.Join(dir, "netbird.sock") }
func dnsFilePath(dir string) string { return filepath.Join(dir, "dns-servers") }

// SetDNSServers records the current network's DNS servers, one per line, for
// the daemon's own lookups (birdsocksd/resolver.go); it re-reads the file on
// change, so the app calls this whenever the default network moves.
func SetDNSServers(dir, servers string) error {
	tmp := dnsFilePath(dir) + ".tmp"
	if err := os.WriteFile(tmp, []byte(servers), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, dnsFilePath(dir))
}

// SetDaemonListener replaces the listener told when the daemon exits.
func SetDaemonListener(l DaemonListener) {
	stateMu.Lock()
	listener = l
	stateMu.Unlock()
}

// SocketPath is where the daemon serves its API, for the app's diagnostics.
func SocketPath() string {
	stateMu.Lock()
	defer stateMu.Unlock()
	return socketPath(dataDir)
}

// Start launches the daemon unless it already runs, and returns once the
// process exists; the API answers a moment later (WaitReady).
func Start(opt *StartOptions) error {
	stateMu.Lock()
	defer stateMu.Unlock()
	if daemon != nil {
		return nil
	}
	if opt == nil || opt.NativeLibDir == "" || opt.DataDir == "" {
		return errors.New("start: native library and data directories are required")
	}
	bin := filepath.Join(opt.NativeLibDir, "libnetbird.so")
	if _, err := os.Stat(bin); err != nil {
		return fmt.Errorf("start: %w", err)
	}
	state := filepath.Join(opt.DataDir, "netbird")
	if err := os.MkdirAll(state, 0o700); err != nil {
		return fmt.Errorf("start: %w", err)
	}
	level := opt.LogLevel
	if level == "" {
		level = "info"
	}
	// The debug bundle collects the daemon's log from a file; console is
	// what feeds the Logs screen.
	cmd := exec.Command(bin,
		"-socket", socketPath(opt.DataDir),
		"-config", filepath.Join(state, "default.json"),
		"-log-file", "console,"+filepath.Join(state, "client.log"),
		"-log-level", level,
		"-dns-file", dnsFilePath(opt.DataDir),
		"-hostname", opt.Hostname,
	)
	cmd.Dir = opt.DataDir
	cmd.Env = daemonEnv(opt, state)
	out, err := cmd.StdoutPipe()
	if err != nil {
		return fmt.Errorf("start: %w", err)
	}
	cmd.Stderr = cmd.Stdout
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start: %w", err)
	}
	processHandlesOnce.Do(reportProcessHandles)
	done := make(chan struct{})
	daemon, daemonDone, dataDir, startedAt = cmd, done, opt.DataDir, time.Now()
	slog.Info("NetBird daemon started", "pid", cmd.Process.Pid, "version", coreVersion)
	go pipeDaemonLog(out)
	go func() {
		err := cmd.Wait()
		slog.Info("NetBird daemon exited", "err", err)
		stateMu.Lock()
		if daemon == cmd {
			daemon = nil
		}
		l := listener
		stateMu.Unlock()
		closeClient()
		close(done)
		if l != nil {
			msg := ""
			if err != nil {
				msg = err.Error()
			}
			l.OnExit(msg)
		}
	}()
	return nil
}

// daemonEnv is the daemon's environment: the app's, minus anything NetBird
// would read by accident, plus netstack mode, the proxy and the state
// directory, then the user's extra lines.
func daemonEnv(opt *StartOptions, state string) []string {
	var env []string
	for _, kv := range os.Environ() {
		if !strings.HasPrefix(kv, "NB_") && !strings.HasPrefix(kv, "USER=") {
			env = append(env, kv)
		}
	}
	env = append(env,
		"HOME="+opt.DataDir,
		// os/user without cgo reads /etc/passwd, which Android does not have,
		// and falls back to $USER — which an app's environment lacks. NetBird
		// asks for the user on every management and signal dial (to choose
		// its root-only dialer) and fails the dial without one.
		"USER=birdsocks",
	)
	// Go's TLS roots on Linux are /etc/ssl/certs and friends; Android keeps
	// them in the Conscrypt APEX (Android 14+) and in /system. Without this
	// every TLS handshake — management, signal, relay — fails with "unknown
	// authority".
	var caDirs []string
	for _, d := range []string{"/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts"} {
		if st, err := os.Stat(d); err == nil && st.IsDir() {
			caDirs = append(caDirs, d)
		}
	}
	if len(caDirs) > 0 {
		env = append(env, "SSL_CERT_DIR="+strings.Join(caDirs, ":"))
	}
	tmp := filepath.Join(opt.DataDir, "tmp")
	_ = os.MkdirAll(tmp, 0o700)
	env = append(env,
		// os.TempDir is /tmp for a Linux binary; debug bundles and captures go there.
		"TMPDIR="+tmp,
		"NB_STATE_DIR="+state,
		// A userspace netstack instead of a kernel TUN: no VPN slot, apps
		// reach the network through the proxy below.
		"NB_USE_NETSTACK_MODE=true",
		fmt.Sprintf("NB_SOCKS5_LISTENER_PORT=%d", opt.SocksPort),
	)
	for k, v := range map[string]string{
		"NB_ANDROID_VERSION":      opt.AndroidVersion,
		"NB_ANDROID_MODEL":        opt.Model,
		"NB_ANDROID_MANUFACTURER": opt.Manufacturer,
		"NB_ANDROID_SDK":          strconv.Itoa(opt.AndroidSdk),
	} {
		if v != "" && v != "0" {
			env = append(env, k+"="+v)
		}
	}
	if !opt.RelayQUIC {
		env = append(env, "NB_RELAY_TRANSPORT=ws")
	}
	if opt.LazyConn != "" {
		env = append(env, "NB_LAZY_CONN="+opt.LazyConn)
	}
	if opt.InboundAccess {
		env = append(env, "NB_ENABLE_NETSTACK_LOCAL_FORWARDING=true", "NB_BIRDSOCKS_NO_FORWARD_PORTS="+ownPorts(opt))
	}
	if opt.DNSProxy != "" {
		env = append(env, "NB_DNS_PROXY_ADDRESS="+opt.DNSProxy)
	}
	if opt.DNSUpstream != "" {
		env = append(env, "NB_DNS_PROXY_UPSTREAM="+opt.DNSUpstream)
	}
	if opt.TunDNS != "" {
		env = append(env, "NB_TUN_DNS_ADDRESS="+opt.TunDNS)
	}
	if opt.SocksHost != "" {
		env = append(env, "NB_SOCKS5_LISTENER_ADDRESS="+opt.SocksHost)
	}
	if opt.SocksUser != "" && opt.SocksPass != "" {
		env = append(env, "NB_SOCKS5_USER="+opt.SocksUser, "NB_SOCKS5_PASS="+opt.SocksPass)
	}
	if timeZoneID != "" {
		env = append(env, "TZ="+timeZoneID)
	}
	for _, line := range strings.Split(opt.Env, "\n") {
		if line = strings.TrimSpace(line); strings.Contains(line, "=") && !strings.HasPrefix(line, "#") {
			env = append(env, line)
		}
	}
	return env
}

// Stop asks the daemon to go down and waits up to ten seconds for it to
// exit before killing it.
func Stop() {
	stateMu.Lock()
	cmd, done := daemon, daemonDone
	stateMu.Unlock()
	if cmd == nil {
		return
	}
	_ = cmd.Process.Signal(syscall.SIGTERM)
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		slog.Warn("NetBird daemon did not exit, killing it")
		_ = cmd.Process.Kill()
		<-done
	}
}

// NetworkChanged tells the daemon the default network switched or came
// back (SIGUSR1): its connections redial on the new one at once.
func NetworkChanged() { signalDaemon(syscall.SIGUSR1) }

// NetworkLost tells the daemon there is no network at all (SIGUSR2).
func NetworkLost() { signalDaemon(syscall.SIGUSR2) }

func signalDaemon(sig syscall.Signal) {
	stateMu.Lock()
	cmd := daemon
	stateMu.Unlock()
	if cmd != nil {
		_ = cmd.Process.Signal(sig)
	}
}

// IsRunning reports whether this app's daemon process is alive.
func IsRunning() bool {
	stateMu.Lock()
	defer stateMu.Unlock()
	return daemon != nil
}

// DaemonStartTime is when the running daemon started, in Unix milliseconds,
// or 0.
func DaemonStartTime() int64 {
	stateMu.Lock()
	defer stateMu.Unlock()
	if daemon == nil {
		return 0
	}
	return startedAt.UnixMilli()
}

// logrus' text format, as birdsocksd writes it to the console:
// "2026-10-03T19:44:28.945Z INFO client/server/server.go:318: message".
var daemonLine = regexp.MustCompile(`^\S+T\S+\s+(TRAC|DEBG|INFO|WARN|ERRO|FATL|PANC)\s+(.*)$`)

// pipeDaemonLog files every daemon line under NETBIRD in the Logs screen,
// with the level the line carries.
func pipeDaemonLog(r io.Reader) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	for sc.Scan() {
		line := sc.Text()
		level := slog.LevelInfo
		if strings.HasPrefix(line, "panic:") || strings.HasPrefix(line, "fatal error:") {
			level = slog.LevelError
		} else if m := daemonLine.FindStringSubmatch(line); m != nil {
			line = m[2]
			switch m[1] {
			case "TRAC", "DEBG":
				level = slog.LevelDebug
			case "WARN":
				level = slog.LevelWarn
			case "ERRO", "FATL", "PANC":
				level = slog.LevelError
			}
		}
		slog.Log(context.Background(), level, line, "src", "daemon")
	}
}

// ownPorts are the app's listeners inbound forwarding must not reach.
func ownPorts(opt *StartOptions) string {
	ports := []string{strconv.Itoa(opt.SocksPort)}
	if _, p, err := net.SplitHostPort(opt.DNSProxy); err == nil {
		ports = append(ports, p)
	}
	return strings.Join(ports, ",")
}
