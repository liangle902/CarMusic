package web

import (
	"os"
	"path/filepath"
	"testing"
)

func TestIndexedMusicCannotEscapeDownloadRoot(t *testing.T) {
	base := t.TempDir()
	root := filepath.Join(base, "music")
	if err := os.MkdirAll(root, 0700); err != nil {
		t.Fatal(err)
	}
	inside := filepath.Join(root, "song.mp3")
	outside := filepath.Join(base, "outside.mp3")
	for _, name := range []string{inside, outside} {
		if err := os.WriteFile(name, []byte("audio"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	if got, err := indexedLocalPath(root, "song.mp3"); err != nil || got != inside {
		t.Fatalf("valid path rejected: %v", err)
	}
	for _, relative := range []string{"../outside.mp3", outside, "", "."} {
		if _, err := indexedLocalPath(root, relative); err == nil {
			t.Fatalf("escaping path accepted: %q", relative)
		}
	}
	link := filepath.Join(root, "outside-link.mp3")
	if err := os.Symlink(outside, link); err != nil {
		t.Skipf("symlink creation unavailable: %v", err)
	}
	if _, err := indexedLocalPath(root, "outside-link.mp3"); err == nil {
		t.Fatal("outside symlink accepted")
	}
}
