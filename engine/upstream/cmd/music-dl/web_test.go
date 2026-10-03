package main

import (
	"testing"

	"github.com/guohuiyuan/go-music-dl/internal/web"
)

func TestWebBasePathFlagDefault(t *testing.T) {
	flag := webCmd.Flags().Lookup("base-path")
	if flag == nil {
		t.Fatal("web command is missing --base-path")
	}
	if got, want := flag.DefValue, web.DefaultRoutePrefix; got != want {
		t.Fatalf("--base-path default = %q, want %q", got, want)
	}
}
