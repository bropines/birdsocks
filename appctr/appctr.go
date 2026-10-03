// Package appctr is the bridge BirdSocks's Kotlin side calls through gomobile:
// it starts and stops the NetBird daemon (core.go), talks to it over its gRPC
// API (rpc.go), and keeps the in-app log (log.go).
package appctr

import (
	// gomobile bind needs its runtime in this module's graph.
	_ "golang.org/x/mobile/bind"
)
