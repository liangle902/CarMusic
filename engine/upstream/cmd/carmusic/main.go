// CarMusic embeds the complete upstream service, bound to this device only.
package main

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"github.com/guohuiyuan/go-music-dl/core"
	"github.com/guohuiyuan/go-music-dl/internal/web"
	"net"
	"os"
	"strings"
	"sync/atomic"
	"time"
)

func main() {
	// Use DNS advertised by Android (including VPN networks). Pure-Go Android
	// has no resolv.conf; public resolvers are only a last resort without a network.
	servers := make([]string, 0)
	for _, value := range strings.Split(os.Getenv("MUSIC_DL_DNS_SERVERS"), ",") {
		if ip := net.ParseIP(value); ip != nil {
			servers = append(servers, net.JoinHostPort(ip.String(), "53"))
		}
	}
	if len(servers) == 0 {
		servers = []string{"223.5.5.5:53", "119.29.29.29:53"}
	}
	var next atomic.Uint64
	net.DefaultResolver = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
		dialer := net.Dialer{Timeout: 3 * time.Second}
		return dialer.DialContext(ctx, network, servers[(next.Add(1)-1)%uint64(len(servers))])
	}}
	endpointFile := os.Getenv("MUSIC_DL_ENDPOINT_FILE")
	instance := os.Getenv("MUSIC_DL_INSTANCE")
	token := os.Getenv("MUSIC_DL_LOCAL_TOKEN")
	configKey, keyError := hex.DecodeString(os.Getenv("MUSIC_DL_CONFIG_KEY"))
	if endpointFile == "" || instance == "" || len(token) != 64 || keyError != nil || len(configKey) != 32 {
		panic("Android host endpoint configuration is missing")
	}
	if err := core.ValidateConfigStorage(); err != nil {
		panic("configuration storage initialization failed")
	}
	web.StartWithOptions("0", web.StartOptions{
		DisableAuth: true, LocalToken: token, ListenHost: "127.0.0.1", BasePath: "/music", InstanceID: instance,
		OnListen: func(address net.Addr) error {
			bound, ok := address.(*net.TCPAddr)
			if !ok || !bound.IP.IsLoopback() || bound.Port == 0 {
				return errors.New("invalid local listener address")
			}
			data, err := json.Marshal(map[string]interface{}{"port": bound.Port, "pid": os.Getpid(), "instance": instance, "token": token})
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
