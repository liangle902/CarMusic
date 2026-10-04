package core

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

func TestUploadSongToWebDAVCreatesDirectoriesAndUploads(t *testing.T) {
	var (
		mu      sync.Mutex
		mkcol   []string
		putPath string
		putBody string
		authOK  bool
	)

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		user, pass, ok := r.BasicAuth()
		if !ok || user != "test" || pass != "123456" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}

		mu.Lock()
		authOK = true
		mu.Unlock()

		switch r.Method {
		case "MKCOL":
			mu.Lock()
			mkcol = append(mkcol, r.URL.Path)
			mu.Unlock()
			w.WriteHeader(http.StatusCreated)
		case http.MethodPut:
			body, _ := io.ReadAll(r.Body)
			mu.Lock()
			putPath = r.URL.Path
			putBody = string(body)
			mu.Unlock()
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusMethodNotAllowed)
		}
	}))
	defer server.Close()

	settings := WebSettings{
		WebDAVEnabled:  true,
		WebDAVURL:      server.URL + "/dav",
		WebDAVUsername: "test",
		WebDAVPassword: "123456",
		WebDAVDir:      "music-dl",
	}

	if err := UploadSongToWebDAV(settings, "Artist\\Album\\Song.mp3", []byte("audio-data")); err != nil {
		t.Fatalf("UploadSongToWebDAV: %v", err)
	}

	mu.Lock()
	defer mu.Unlock()
	if !authOK {
		t.Fatal("expected basic auth on webdav requests")
	}
	wantDirs := []string{
		"/dav/music-dl/",
		"/dav/music-dl/Artist/",
		"/dav/music-dl/Artist/Album/",
	}
	if strings.Join(mkcol, ",") != strings.Join(wantDirs, ",") {
		t.Fatalf("mkcol paths mismatch\ngot:  %#v\nwant: %#v", mkcol, wantDirs)
	}
	if putPath != "/dav/music-dl/Artist/Album/Song.mp3" {
		t.Fatalf("put path = %q, want %q", putPath, "/dav/music-dl/Artist/Album/Song.mp3")
	}
	if putBody != "audio-data" {
		t.Fatalf("put body = %q, want %q", putBody, "audio-data")
	}
}

func TestUploadSongToWebDAVFailsOnServerError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "MKCOL" {
			w.WriteHeader(http.StatusCreated)
			return
		}
		http.Error(w, "storage full", http.StatusInsufficientStorage)
	}))
	defer server.Close()

	settings := WebSettings{
		WebDAVEnabled:  true,
		WebDAVURL:      server.URL,
		WebDAVUsername: "test",
		WebDAVPassword: "123456",
		WebDAVDir:      "music-dl",
	}
	err := UploadSongToWebDAV(settings, "song.mp3", []byte("audio"))
	if err == nil {
		t.Fatal("expected upload error")
	}
	if !strings.Contains(err.Error(), "storage full") {
		t.Fatalf("upload error = %q, want storage full message", err.Error())
	}
}

func TestUploadSongToWebDAVDisabledIsNoop(t *testing.T) {
	called := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	if err := UploadSongToWebDAV(WebSettings{WebDAVURL: server.URL}, "song.mp3", []byte("audio")); err != nil {
		t.Fatalf("UploadSongToWebDAV: %v", err)
	}
	if called {
		t.Fatal("disabled webdav should not send requests")
	}
}
