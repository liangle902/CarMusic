package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"time"
)

func init() {
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			d := net.Dialer{Timeout: 3 * time.Second}
			conn, err := d.DialContext(ctx, "udp", "223.5.5.5:53")
			if err != nil {
				return d.DialContext(ctx, "udp", "119.29.29.29:53")
			}
			return conn, nil
		},
	}
}

func main() {
	if err := rootCmd.Execute(); err != nil {
		fmt.Println(err)
		os.Exit(1)
	}
}
