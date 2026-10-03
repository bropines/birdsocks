package appctr

// rpc.go — the app's way into the daemon: its gRPC API, over the unix socket
// core.go gave it, with JSON on the Kotlin side. Rather than one Go wrapper
// per method, Call takes a method name and its request as protobuf JSON and
// answers with the response the same way; the method's types come from
// daemon.proto's own descriptor, so every unary method the daemon serves is
// reachable, and a NetBird update that adds one needs no bridge change.
//
//	Call("Login", `{"setupKey":"…","managementUrl":"https://…"}`, 30000)
//	Call("Status", `{"getFullPeerStatus":true}`, 5000)
//
// The two streams the UI lives on — status and system events — go through
// Subscribe, which hands every message to a Kotlin callback.

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"sync"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/reflect/protoreflect"
	"google.golang.org/protobuf/types/dynamicpb"

	nbproto "github.com/netbirdio/netbird/client/proto"
)

var (
	clientMu sync.Mutex
	client   *grpc.ClientConn
)

var (
	toJSON = protojson.MarshalOptions{EmitUnpopulated: true}
	// DiscardUnknown: a field the app knows and this daemon does not is
	// dropped instead of failing the call.
	fromJSON = protojson.UnmarshalOptions{DiscardUnknown: true}
)

// conn is the client connection to the running daemon's socket, made on first
// use. grpc connects lazily and reconnects by itself; core.go drops it when
// the daemon exits, so a new daemon is met on a fresh connection.
func conn() (*grpc.ClientConn, error) {
	clientMu.Lock()
	defer clientMu.Unlock()
	if client != nil {
		return client, nil
	}
	stateMu.Lock()
	dir := dataDir
	stateMu.Unlock()
	if dir == "" {
		return nil, errors.New("the daemon has not been started")
	}
	c, err := grpc.NewClient("unix://"+socketPath(dir),
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithDefaultCallOptions(grpc.MaxCallRecvMsgSize(64<<20)),
	)
	if err != nil {
		return nil, err
	}
	client = c
	return c, nil
}

func closeClient() {
	clientMu.Lock()
	c := client
	client = nil
	clientMu.Unlock()
	if c != nil {
		_ = c.Close()
	}
}

// method is the descriptor of one DaemonService method, by its short name.
func method(name string) (protoreflect.MethodDescriptor, error) {
	svc := nbproto.File_daemon_proto.Services().ByName("DaemonService")
	m := svc.Methods().ByName(protoreflect.Name(name))
	if m == nil {
		return nil, fmt.Errorf("the daemon has no method %q", name)
	}
	return m, nil
}

func fullName(m protoreflect.MethodDescriptor) string {
	return fmt.Sprintf("/%s/%s", m.Parent().FullName(), m.Name())
}

// newRequest parses requestJSON ("" is an empty request) into m's input type.
func newRequest(m protoreflect.MethodDescriptor, requestJSON string) (proto.Message, error) {
	req := dynamicpb.NewMessage(m.Input())
	if requestJSON != "" {
		if err := fromJSON.Unmarshal([]byte(requestJSON), req); err != nil {
			return nil, fmt.Errorf("%s request: %w", m.Name(), err)
		}
	}
	return req, nil
}

// rpcError is a daemon error as the user should read it: the status message
// without gRPC's "rpc error: code = … desc =" wrapping.
func rpcError(name string, err error) error {
	if s, ok := status.FromError(err); ok {
		return fmt.Errorf("%s: %s", name, s.Message())
	}
	return fmt.Errorf("%s: %w", name, err)
}

// Call runs one unary daemon method: requestJSON in, the response's JSON out.
// timeoutMs bounds the call; WaitSSOLogin, which blocks until the user
// finishes signing in, wants minutes, Status a few seconds.
func Call(name, requestJSON string, timeoutMs int64) (string, error) {
	m, err := method(name)
	if err != nil {
		return "", err
	}
	if m.IsStreamingClient() || m.IsStreamingServer() {
		return "", fmt.Errorf("%s is a stream: use Subscribe", name)
	}
	req, err := newRequest(m, requestJSON)
	if err != nil {
		return "", err
	}
	c, err := conn()
	if err != nil {
		return "", err
	}
	if timeoutMs <= 0 {
		timeoutMs = 15000
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	resp := dynamicpb.NewMessage(m.Output())
	if err := c.Invoke(ctx, fullName(m), req, resp); err != nil {
		return "", rpcError(name, err)
	}
	out, err := toJSON.Marshal(resp)
	if err != nil {
		return "", err
	}
	return string(out), nil
}

// StreamHandler receives a server stream's messages, one JSON object per
// call, and then exactly one OnEnd: "" when the stream was cancelled, the
// reason otherwise.
type StreamHandler interface {
	OnMessage(json string)
	OnEnd(err string)
}

// Subscription is a running stream; Cancel ends it.
type Subscription struct {
	cancel context.CancelFunc
}

// Cancel ends the stream. Safe to call more than once.
func (s *Subscription) Cancel() { s.cancel() }

// Subscribe opens a server-streaming daemon method (SubscribeStatus,
// SubscribeEvents) and feeds h from a goroutine until the stream ends or is
// cancelled.
func Subscribe(name, requestJSON string, h StreamHandler) (*Subscription, error) {
	m, err := method(name)
	if err != nil {
		return nil, err
	}
	if !m.IsStreamingServer() || m.IsStreamingClient() {
		return nil, fmt.Errorf("%s is not a server stream", name)
	}
	req, err := newRequest(m, requestJSON)
	if err != nil {
		return nil, err
	}
	c, err := conn()
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	desc := &grpc.StreamDesc{StreamName: string(m.Name()), ServerStreams: true}
	stream, err := c.NewStream(ctx, desc, fullName(m))
	if err == nil {
		if err = stream.SendMsg(req); err == nil {
			err = stream.CloseSend()
		}
	}
	if err != nil {
		cancel()
		return nil, rpcError(name, err)
	}
	go func() {
		defer cancel()
		for {
			msg := dynamicpb.NewMessage(m.Output())
			if err := stream.RecvMsg(msg); err != nil {
				switch {
				case ctx.Err() != nil:
					h.OnEnd("")
				case errors.Is(err, io.EOF):
					h.OnEnd("the daemon closed the stream")
				default:
					h.OnEnd(rpcError(name, err).Error())
				}
				return
			}
			out, err := toJSON.Marshal(msg)
			if err != nil {
				slog.Warn("stream message", "method", name, "err", err)
				continue
			}
			h.OnMessage(string(out))
		}
	}()
	return &Subscription{cancel: cancel}, nil
}

// WaitReady blocks until the daemon answers on its socket, or timeoutMs
// passes: the moment after Start when the app may begin calling it.
func WaitReady(timeoutMs int64) error {
	deadline := time.Now().Add(time.Duration(timeoutMs) * time.Millisecond)
	var err error
	for time.Now().Before(deadline) {
		if !IsRunning() {
			return errors.New("the daemon exited")
		}
		if _, err = Call("GetFeatures", "", 1000); err == nil {
			return nil
		}
		time.Sleep(150 * time.Millisecond)
	}
	return fmt.Errorf("the daemon did not answer: %w", err)
}
