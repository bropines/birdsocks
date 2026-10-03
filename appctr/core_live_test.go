package appctr

import (
	"os"
	"strings"
	"testing"
	"time"
)

// TestDaemonLive runs a real daemon: BIRDSOCKS_LIVE_LIB is a directory holding
// a libnetbird.so built for this host. With BIRDSOCKS_LIVE_SETUP_KEY it also
// logs in and brings the tunnel up.
func TestDaemonLive(t *testing.T) {
	lib := os.Getenv("BIRDSOCKS_LIVE_LIB")
	if lib == "" {
		t.Skip("BIRDSOCKS_LIVE_LIB is not set")
	}
	dir := t.TempDir()
	if err := SetDNSServers(dir, "1.1.1.1\n"); err != nil {
		t.Fatal(err)
	}
	if err := Start(&StartOptions{NativeLibDir: lib, DataDir: dir, SocksPort: 21080, LogLevel: "debug"}); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	if err := WaitReady(10000); err != nil {
		t.Fatal(err)
	}
	for _, m := range []string{"GetFeatures", "ListProfiles", "GetActiveProfile"} {
		out, err := Call(m, `{"username":"android"}`, 5000)
		t.Logf("%s: %s %v", m, out, err)
	}
	statuses := make(chan string, 16)
	sub, err := Subscribe("SubscribeStatus", `{"getFullPeerStatus":true}`, handler{statuses})
	if err != nil {
		t.Fatal(err)
	}
	defer sub.Cancel()
	select {
	case s := <-statuses:
		t.Logf("first status: %.300s", s)
	case <-time.After(5 * time.Second):
		t.Fatal("no status")
	}
	key := os.Getenv("BIRDSOCKS_LIVE_SETUP_KEY")
	if key == "" {
		out, err := Call("Login", `{"hostname":"birdsocks-live","username":"android"}`, 30000)
		t.Logf("SSO login: %s %v", out, err)
		return
	}
	out, err := Call("Login", `{"setupKey":"`+key+`","hostname":"birdsocks-live","username":"android"}`, 30000)
	t.Logf("login: %s %v", out, err)
	out, err = Call("Up", `{"username":"android"}`, 60000)
	t.Logf("up: %s %v", out, err)
	deadline := time.After(40 * time.Second)
	for {
		select {
		case s := <-statuses:
			if strings.Contains(s, `"status":"Connected"`) {
				t.Logf("connected: %.600s", s)
				return
			}
		case <-deadline:
			t.Fatal("not connected")
		}
	}
}

type handler struct{ ch chan string }

func (h handler) OnMessage(json string) {
	select {
	case h.ch <- json:
	default:
	}
}
func (h handler) OnEnd(err string) { h.ch <- "END " + err }
