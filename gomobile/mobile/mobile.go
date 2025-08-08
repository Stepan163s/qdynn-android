package mobile

import (
    "context"
    "errors"
    "fmt"
    "net"
    "strings"
    "sync"
    "time"

    // _ "github.com/gharib-uk/dnstt/turbotunnel" // ensure module fetched; real client wired later
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

    // runtime state
    cancelCtx context.CancelFunc
    sendCh    chan []byte
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
    if b.started {
        b.mu.Unlock()
        return
    }
    b.started = true
    b.onPacket = handler
    b.onLog = logger

    ctx, cancel := context.WithCancel(context.Background())
    b.cancelCtx = cancel
    b.sendCh = make(chan []byte, 1024)
    b.mu.Unlock()

    b.logf("dnstt Start: domain=%s dns=%s", domain, dns)

    // Parse DNS upstream (udp:/doh:/dot:)
    upstream, err := parseUpstream(dns)
    if err != nil {
        b.logf("invalid dns upstream: %v", err)
        return
    }

    // Start minimal mock pipeline for now: echo via timers to keep async path alive
    go b.runMockClient(ctx, upstream)
    go b.runSender(ctx)
}

// SendPacket sends an outbound IP packet into the dnstt transport.
func (b *Bridge) SendPacket(p []byte) {
	b.mu.RLock()
    ok := b.started
    ch := b.sendCh
    h := b.onPacket
	b.mu.RUnlock()
	if !ok {
		return
	}
    // enqueue for sender
    if ch != nil {
        select {
        case ch <- append([]byte(nil), p...):
        default:
            // drop if congested
        }
    } else if h != nil {
        // fallback echo
        h.OnPacket(p)
    }
}

// Stop shuts down the transport.
func (b *Bridge) Stop() {
    b.mu.Lock()
    if !b.started {
        b.mu.Unlock()
        return
    }
    b.started = false
    cancel := b.cancelCtx
    b.cancelCtx = nil
    ch := b.sendCh
    b.sendCh = nil
    b.mu.Unlock()

    if cancel != nil {
        cancel()
    }
    if ch != nil {
        // drain
        for {
            select { case <-ch: default: goto drained }
        }
    }
drained:
    b.logf("dnstt Stop")
} 

// ---- helpers ----

type upstreamSpec struct {
    scheme string
    addr   string
}

func parseUpstream(s string) (upstreamSpec, error) {
    s = strings.TrimSpace(s)
    if s == "" {
        return upstreamSpec{}, errors.New("empty dns upstream")
    }
    if strings.HasPrefix(s, "udp:") {
        host := strings.TrimPrefix(s, "udp:")
        if _, _, err := net.SplitHostPort(host); err != nil {
            return upstreamSpec{}, fmt.Errorf("invalid udp host:port: %w", err)
        }
        return upstreamSpec{scheme: "udp", addr: host}, nil
    }
    if strings.HasPrefix(s, "doh:") {
        return upstreamSpec{scheme: "doh", addr: strings.TrimPrefix(s, "doh:")}, nil
    }
    if strings.HasPrefix(s, "dot:") {
        return upstreamSpec{scheme: "dot", addr: strings.TrimPrefix(s, "dot:")}, nil
    }
    // default assume raw host:port as udp
    if _, _, err := net.SplitHostPort(s); err == nil {
        return upstreamSpec{scheme: "udp", addr: s}, nil
    }
    return upstreamSpec{}, fmt.Errorf("unknown upstream format: %s", s)
}

func (b *Bridge) runSender(ctx context.Context) {
    ticker := time.NewTicker(5 * time.Second)
    defer ticker.Stop()
    for {
        select {
        case <-ctx.Done():
            return
        case p := <-b.sendCh:
            // TODO: send via real dnstt client; currently simulate immediate ack
            b.mu.RLock()
            h := b.onPacket
            b.mu.RUnlock()
            if h != nil {
                h.OnPacket(p)
            }
        case <-ticker.C:
            // keepalive log
            b.logf("dnstt keepalive")
        }
    }
}

func (b *Bridge) runMockClient(ctx context.Context, up upstreamSpec) {
    b.logf("upstream scheme=%s addr=%s", up.scheme, up.addr)
    // Placeholder for wiring dnstt client
    <-ctx.Done()
}