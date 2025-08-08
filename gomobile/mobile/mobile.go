package mobile

import (
	"fmt"
	"sync"
)

// PacketHandler is implemented on the Kotlin side to receive packets from Go.
type PacketHandler interface {
	OnPacket([]byte)
}

// Logger is implemented on the Kotlin side to receive log lines.
type Logger interface {
	OnLog(string)
}

// Bridge is the main entry point exported to Android via gomobile.
type Bridge struct {
	mu       sync.RWMutex
	started  bool
	onPacket PacketHandler
	onLog    Logger
}

func NewBridge() *Bridge { return &Bridge{} }

func (b *Bridge) logf(format string, args ...any) {
	b.mu.RLock()
	l := b.onLog
	b.mu.RUnlock()
	if l != nil {
		l.OnLog(fmt.Sprintf(format, args...))
	}
}

// Start initializes the dnstt client with provided params and registers callbacks.
func (b *Bridge) Start(domain, password, dns string, handler PacketHandler, logger Logger) {
	b.mu.Lock()
	b.started = true
	b.onPacket = handler
	b.onLog = logger
	b.mu.Unlock()
	b.logf("dnstt Start: domain=%s dns=%s", domain, dns)
}

// SendPacket sends an outbound IP packet into the dnstt transport.
func (b *Bridge) SendPacket(p []byte) {
	b.mu.RLock()
	ok := b.started
	h := b.onPacket
	b.mu.RUnlock()
	if !ok {
		return
	}
	// TODO: encapsulate and send via real dnstt transport
	// Echo back to demonstrate end-to-end path
	if h != nil {
		h.OnPacket(p)
	}
}

// Stop shuts down the transport.
func (b *Bridge) Stop() {
	b.mu.Lock()
	b.started = false
	b.mu.Unlock()
	b.logf("dnstt Stop")
} 