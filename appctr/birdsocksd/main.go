// birdsocksd is BirdSocks's NetBird daemon: NetBird's own daemon server
// (client/server, which `netbird service run` hosts on desktops) serving its
// gRPC API on a unix socket, without the system service manager around it —
// kardianos/service has nothing to manage on Android.
//
// build.sh copies this directory into the NetBird tree as client/birdsocksd
// and builds it there, so it compiles against NetBird's own go.mod and may
// use the client's internal packages.
//
// It is a Linux binary, not an Android one (resolver.go says why). The app
// starts it (appctr/core.go) with NB_STATE_DIR pointing at its
// files, so profiles and state live there and not in /var/lib/netbird, and
// with NB_USE_NETSTACK_MODE=true and the SOCKS5 settings in the environment:
// the tunnel is a userspace netstack, not a kernel TUN.
package main

import (
	"context"
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"
	// Android has no /usr/share/zoneinfo for a Linux binary; TZ comes from the app.
	_ "time/tzdata"

	log "github.com/sirupsen/logrus"
	"google.golang.org/grpc"

	"github.com/netbirdio/netbird/client/iface/netstack"
	"github.com/netbirdio/netbird/client/internal"
	"github.com/netbirdio/netbird/client/internal/ipcauth"
	"github.com/netbirdio/netbird/client/proto"
	"github.com/netbirdio/netbird/client/server"
	"github.com/netbirdio/netbird/client/system"
	"github.com/netbirdio/netbird/util"
)

func main() {
	socket := flag.String("socket", "", "unix socket to serve the daemon gRPC API on")
	config := flag.String("config", "", "the default profile's config file")
	logFile := flag.String("log-file", "console", "where the log goes: a path or console")
	logLevel := flag.String("log-level", "info", "log level")
	hostname := flag.String("hostname", "", "the name this device registers and reports under")
	dnsServers := flag.String("dns-file", "", "the network's DNS servers, one per line, kept current by the app")
	flag.Parse()
	if *socket == "" || *config == "" {
		fmt.Fprintln(os.Stderr, "birdsocksd: -socket and -config are required")
		os.Exit(2)
	}

	// -log-file takes several targets, comma-separated: console and a file.
	logTargets := strings.Split(*logFile, ",")
	if err := util.InitLog(*logLevel, logTargets...); err != nil {
		fmt.Fprintf(os.Stderr, "birdsocksd: log: %v\n", err)
		os.Exit(1)
	}

	if *dnsServers != "" {
		installResolver(*dnsServers)
	}
	// The proxies live as long as the daemon, not one engine: with no
	// engine (no login, an expired session, a reconnect) they reach the
	// internet directly instead of refusing every app behind them.
	if netstack.IsEnabled() {
		if err := netstack.StartFront(); err != nil {
			log.Errorf("proxies: %v", err)
		}
	}

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()
	// The server reads its connection state from the context, as the
	// desktop service sets it up before creating one.
	ctx = internal.CtxInitState(ctx)
	// Every request the daemon makes — the Up after login included, which
	// carries no name of its own — reports this device name; without it the
	// peer would call itself by os.Hostname(), "localhost" on Android.
	if *hostname != "" {
		ctx = context.WithValue(ctx, system.DeviceNameCtxKey, *hostname) //nolint:staticcheck
	}

	system.UpdateStaticInfoAsync()

	// A socket left by a killed daemon would make the listen fail.
	_ = os.Remove(*socket)
	lis, err := net.Listen("unix", *socket)
	if err != nil {
		log.Fatalf("listen on %s: %v", *socket, err)
	}
	// The app and this daemon share a UID; nobody else needs the socket.
	if err := os.Chmod(*socket, 0o600); err != nil {
		log.Warnf("chmod %s: %v", *socket, err)
	}

	// Peer credentials, as the desktop daemon installs them: the handlers ask
	// who is calling for the few privileged changes, and an unprivileged
	// daemon grants those to callers of its own UID — the app.
	var opts []grpc.ServerOption
	if creds := ipcauth.NewTransportCredentials(); creds != nil { //nolint:staticcheck
		opts = append(opts, grpc.Creds(creds))
	}
	srv := grpc.NewServer(opts...)

	// Packet capture (`netbird debug capture`) on: the socket is the app's
	// alone (0600, same UID), and it is how a tunnel problem is seen.
	// The server's log file is what the debug bundle collects: the first
	// target that is a file.
	serverLog := "console"
	for _, t := range logTargets {
		if t != "console" && t != "syslog" {
			serverLog = t
			break
		}
	}
	s := server.New(ctx, serverLog, *config, false, false, true, false)
	if err := s.Start(); err != nil {
		log.Fatalf("start daemon: %v", err)
	}
	proto.RegisterDaemonServiceServer(srv, s)

	// The app's network callback: SIGUSR1 when the default network changed
	// (or came back), SIGUSR2 when there is none. Netstack mode has no
	// network monitor of its own.
	netSig := make(chan os.Signal, 4)
	signal.Notify(netSig, syscall.SIGUSR1, syscall.SIGUSR2)
	go func() {
		for sig := range netSig {
			if sig == syscall.SIGUSR2 {
				log.Info("network: none available")
				s.SetNetworkAvailable(false)
				continue
			}
			log.Info("network: changed")
			s.NotifyNetworkChange()
		}
	}()

	go func() {
		<-ctx.Done()
		log.Info("stopping")
		if _, err := s.Down(context.Background(), &proto.DownRequest{}); err != nil {
			log.Debugf("down: %v", err)
		}
		// GracefulStop waits for every open call, and a client that keeps
		// one open (a status stream not yet torn down) held the process for
		// the app's whole 10 s grace before it was killed.
		stopped := make(chan struct{})
		go func() { srv.GracefulStop(); close(stopped) }()
		select {
		case <-stopped:
		case <-time.After(3 * time.Second):
			log.Warn("open calls did not finish, stopping the API now")
			srv.Stop()
		}
	}()

	log.Infof("birdsocksd serving the daemon API on %s", *socket)
	if err := srv.Serve(lis); err != nil {
		log.Errorf("serve: %v", err)
	}
}
