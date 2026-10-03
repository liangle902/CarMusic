// CarMusic embeds the complete upstream service, bound to this device only.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"github.com/guohuiyuan/go-music-dl/internal/web"
	"net"
	"os"
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
	endpointFile := os.Getenv("MUSIC_DL_ENDPOINT_FILE")
	instance := os.Getenv("MUSIC_DL_INSTANCE")
	if endpointFile == "" || instance == "" {
		panic("Android host endpoint configuration is missing")
	}
	web.StartWithOptions("0", web.StartOptions{
		DisableAuth: true, ListenHost: "127.0.0.1", BasePath: "/music", InstanceID: instance,
		OnListen: func(address net.Addr) error {
			bound, ok := address.(*net.TCPAddr)
			if !ok || !bound.IP.IsLoopback() || bound.Port == 0 {
				return errors.New("invalid local listener address")
			}
			data, err := json.Marshal(map[string]interface{}{"port": bound.Port, "pid": os.Getpid(), "instance": instance})
			if err != nil {
				return err
			}
			temporary := endpointFile + ".tmp"
			if err := os.WriteFile(temporary, data, 0600); err != nil {
				return err
			}
			return os.Rename(temporary, endpointFile)
		},
	})
}
