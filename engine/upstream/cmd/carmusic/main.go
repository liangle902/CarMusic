// CarMusic embeds the complete upstream service, bound to this device only.
package main

import (
	"context"
	"github.com/guohuiyuan/go-music-dl/internal/web"
	"net"
	"os"
	"strconv"
	"time"
)

func main() {
	// Android does not expose a normal resolv.conf to a pure-Go daemon.
	net.DefaultResolver = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
		dialer := net.Dialer{Timeout: 3 * time.Second}
		conn, err := dialer.DialContext(ctx, "udp", "223.5.5.5:53")
		if err != nil {
			return dialer.DialContext(ctx, "udp", "119.29.29.29:53")
		}
		return conn, nil
	}}
	port := os.Getenv("MUSIC_DL_PORT")
	number, err := strconv.Atoi(port)
	if err != nil || number < 1024 || number > 65535 {
		port = "37777"
	}
	web.StartWithOptions(port, web.StartOptions{DisableAuth: true, ListenHost: "127.0.0.1", BasePath: "/music"})
}
