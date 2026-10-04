package core

import (
	"bytes"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/guohuiyuan/music-lib/model"
)

func TestPlaybackArgsInjectsRefererAndCookie(t *testing.T) {
	CM.SetAll(map[string]string{"netease": "MUSIC_U=token"})
	t.Cleanup(func() { CM.SetAll(map[string]string{"netease": ""}) })

	song := &model.Song{ID: "1", Source: "netease"}
	args := PlaybackArgs(song, "http://example.com/a.mp3")

	joined := strings.Join(args, " ")
	if !strings.Contains(joined, "-nodisp") || !strings.Contains(joined, "-autoexit") {
		t.Fatalf("missing base flags: %v", args)
	}
	if args[len(args)-1] != "http://example.com/a.mp3" {
		t.Fatalf("url must be last arg, got %v", args)
	}

	uaIdx := indexOf(args, "-user_agent")
	if uaIdx < 0 || args[uaIdx+1] != UA_Common {
		t.Fatalf("expected common UA, got %v", args)
	}

	hdrIdx := indexOf(args, "-headers")
	if hdrIdx < 0 {
		t.Fatalf("expected -headers, got %v", args)
	}
	headers := args[hdrIdx+1]
	if !strings.Contains(headers, "Referer: "+Ref_Netease) {
		t.Fatalf("expected netease referer in headers: %q", headers)
	}
	if !strings.Contains(headers, "Cookie: MUSIC_U=token") {
		t.Fatalf("expected cookie in headers: %q", headers)
	}
}

func TestPlaybackArgsMiguUsesMobileUA(t *testing.T) {
	song := &model.Song{ID: "9", Source: "migu"}
	args := PlaybackArgs(song, "http://example.com/b.mp3")

	uaIdx := indexOf(args, "-user_agent")
	if uaIdx < 0 || args[uaIdx+1] != UA_Mobile {
		t.Fatalf("expected mobile UA for migu, got %v", args)
	}
}

func TestResolveFFplayPathUsesEnv(t *testing.T) {
	dir := t.TempDir()
	tool := filepath.Join(dir, "ffplay")
	if err := os.WriteFile(tool, []byte("test"), 0755); err != nil {
		t.Fatalf("write tool: %v", err)
	}

	t.Setenv(ffplayEnvName, tool)
	got, err := ResolveFFplayPath()
	if err != nil {
		t.Fatalf("ResolveFFplayPath error: %v", err)
	}
	if got != tool {
		t.Fatalf("ResolveFFplayPath = %q, want %q", got, tool)
	}
}

func TestLiveMiguPlaybackSource(t *testing.T) {
	cookie := os.Getenv("MIGU_TEST_COOKIE")
	if cookie == "" {
		t.Skip("set MIGU_TEST_COOKIE to run the live Migu playback test")
	}

	CM.SetAll(map[string]string{"migu": cookie})
	t.Cleanup(func() { CM.SetAll(map[string]string{"migu": ""}) })
	song := &model.Song{
		Source: "migu",
		ID:     "600919000009811300|2|Z3D",
		Extra: map[string]string{
			"content_id":    "600919000009811300",
			"resource_type": "2",
			"format_type":   "Z3D",
			"copyright_id":  "6005861HZLK",
			"song_id":       "1138997181",
			"album_id":      "1138997327",
		},
	}

	playURL, tempFile, err := PreparePlaybackSource(song)
	if err != nil {
		t.Fatalf("PreparePlaybackSource() error = %v", err)
	}
	if playURL == "" {
		t.Fatal("PreparePlaybackSource() returned an empty playback URL")
	}
	if tempFile != "" && playURL != tempFile {
		t.Fatalf("playURL = %q, tempFile = %q", playURL, tempFile)
	}
	if tempFile != "" {
		t.Cleanup(func() { os.Remove(tempFile) })

		data, err := os.ReadFile(tempFile)
		if err != nil {
			t.Fatalf("read playback file error = %v", err)
		}
		if !bytes.HasPrefix(data, []byte("RIFF")) &&
			!bytes.HasPrefix(data, []byte("fLaC")) &&
			!bytes.HasPrefix(data, []byte("ID3")) {
			t.Fatalf("playback file is not a supported audio stream")
		}
		return
	}

	req, err := http.NewRequest(http.MethodGet, playURL, nil)
	if err != nil {
		t.Fatalf("http.NewRequest() error = %v", err)
	}
	req.Header.Set("User-Agent", UA_Mobile)
	req.Header.Set("Range", "bytes=0-15")
	resp, err := (&http.Client{}).Do(req)
	if err != nil {
		t.Fatalf("playback URL request error = %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < http.StatusOK || resp.StatusCode >= http.StatusMultipleChoices {
		t.Fatalf("playback URL status = %d", resp.StatusCode)
	}
	prefix, err := io.ReadAll(io.LimitReader(resp.Body, 16))
	if err != nil {
		t.Fatalf("playback URL read error = %v", err)
	}
	if !bytes.HasPrefix(prefix, []byte("RIFF")) &&
		!bytes.HasPrefix(prefix, []byte("fLaC")) &&
		!bytes.HasPrefix(prefix, []byte("ID3")) &&
		!(len(prefix) >= 2 && prefix[0] == 0xFF && prefix[1]&0xE0 == 0xE0) {
		t.Fatalf("playback URL returned invalid audio data")
	}
}

func indexOf(args []string, target string) int {
	for i, a := range args {
		if a == target {
			return i
		}
	}
	return -1
}
