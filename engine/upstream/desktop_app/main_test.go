package main

import (
	"net/url"
	"strings"
	"testing"
)

func TestBridgeRoutesOnlyBrowserDownloadsExternally(t *testing.T) {
	if strings.Contains(bridgeScript, "setting-download-to-local") {
		t.Fatal("bridgeScript must not depend on removed download-to-local toggle")
	}
	if !strings.Contains(bridgeScript, `closest(".btn-download, .btn-browser-download")`) {
		t.Fatal("bridgeScript should inspect both local-save and browser-download buttons")
	}
	if !strings.Contains(bridgeScript, `if (link.classList.contains("btn-download"))`) {
		t.Fatal("bridgeScript should leave local-save downloads to app.js POST handling")
	}
	if !strings.Contains(bridgeScript, `url.searchParams.delete("save_local")`) {
		t.Fatal("bridgeScript should strip save_local before opening external browser downloads")
	}
}

func TestBridgeReportsPlaybackState(t *testing.T) {
	for _, want := range []string{
		"musicDlPlaybackState",
		`notifyPlaybackState("playing")`,
		`notifyPlaybackState("paused")`,
		`notifyPlaybackState("ended")`,
		`notifyPlaybackState("released")`,
		`bindAPlayerPlayback`,
		`window.ap.audio === audio`,
	} {
		if !strings.Contains(bridgeScript, want) {
			t.Fatalf("bridgeScript missing playback token %q", want)
		}
	}
}

func TestBridgeReportsAppLoadState(t *testing.T) {
	for _, want := range []string{
		"musicDlAppState",
		`notifyAppState("loaded")`,
	} {
		if !strings.Contains(bridgeScript, want) {
			t.Fatalf("bridgeScript missing app state token %q", want)
		}
	}
}

func TestHandleWebViewMessagePlaybackDoesNotOpenURL(t *testing.T) {
	app := newDesktopApp(nil, nil)
	app.handleWebViewMessage("playback:paused")
	if app.pendingExternalOpenTo != nil {
		t.Fatalf("playback message should not be treated as URL: %s", app.pendingExternalOpenTo)
	}
}

func TestPlaybackStateDefersResumeReloadUntilStopped(t *testing.T) {
	app := newDesktopApp(nil, nil)
	app.reloadDeferred = true
	app.reloadPending = true

	app.handlePlaybackState("playing")
	if !app.playbackActive {
		t.Fatal("playing should mark playback as active")
	}
	if app.reloadDeferred || app.reloadPending {
		t.Fatal("playing should cancel pending resume reloads")
	}

	app.handlePlaybackState("paused")
	if app.playbackActive {
		t.Fatal("paused should clear playback active state")
	}
	if app.reloadPending || app.reloadDeferred {
		t.Fatal("paused should not create a reload without a new frame gap")
	}
}

func TestPlaybackStopFlushesDeferredReload(t *testing.T) {
	app := newDesktopApp(nil, nil)
	app.handlePlaybackState("playing")
	app.reloadDeferred = true

	app.handlePlaybackState("ended")
	if !app.reloadPending || app.reloadDeferred {
		t.Fatal("stopping playback should flush a deferred resume reload")
	}
}

func TestHandleWebViewMessageAppStateDoesNotOpenURL(t *testing.T) {
	app := newDesktopApp(nil, nil)
	app.handleWebViewMessage("state:loaded")
	if app.pendingExternalOpenTo != nil {
		t.Fatalf("app state message should not be treated as URL: %s", app.pendingExternalOpenTo)
	}
	if !app.initialNavAcked {
		t.Fatal("state:loaded should acknowledge the initial navigation")
	}
}

func TestNavigationEventTracksCurrentURL(t *testing.T) {
	app := newDesktopApp(nil, nil)
	app.initialNavReady = true
	app.handleNavigationEvent("http://127.0.0.1:37777/music/settings")

	if app.currentWebURL != "http://127.0.0.1:37777/music/settings" {
		t.Fatalf("currentWebURL = %q", app.currentWebURL)
	}
	if app.initialNavAcked {
		t.Fatal("navigation event alone must not acknowledge an HTTP page; only DOMContentLoaded can")
	}
}

func TestNavigationEventAcknowledgesStartupErrorDataURL(t *testing.T) {
	app := newDesktopApp(nil, nil)
	app.initialNavReady = true
	app.initialNavURL = "data:text/html;charset=utf-8,startup-error"
	app.handleNavigationEvent(app.initialNavURL)

	if !app.initialNavAcked {
		t.Fatal("data URL navigation should acknowledge the startup error page")
	}
}

func TestHandleWebViewMessageDownloadURL(t *testing.T) {
	app := newDesktopApp(nil, nil)
	raw := "http://127.0.0.1:37777/music/download?id=1&source=qq"
	app.handleWebViewMessage(raw)
	if app.pendingExternalOpenTo == nil {
		t.Fatal("download URL was not queued for external open")
	}
	if got := app.pendingExternalOpenTo.String(); got != raw {
		t.Fatalf("pendingExternalOpenTo = %q, want %q", got, raw)
	}
	if _, err := url.Parse(gotURL(app)); err != nil {
		t.Fatalf("pending URL is not parseable: %v", err)
	}
}

func TestInitialNavigationWaitsForServerReady(t *testing.T) {
	ch := make(chan initialNavigationResult, 1)
	app := newDesktopApp(nil, ch)
	app.callbackRegistered = true
	app.pendingInitialNav = true

	app.consumeInitialNavigationResult()
	if app.initialNavReady {
		t.Fatal("initial navigation should not be ready before the probe result")
	}

	ch <- initialNavigationResult{URL: "http://127.0.0.1:37777/music/"}
	app.consumeInitialNavigationResult()
	if !app.initialNavReady {
		t.Fatal("initial navigation should be ready after the probe result")
	}
	if app.initialNavURL != "http://127.0.0.1:37777/music/" {
		t.Fatalf("initialNavURL = %q", app.initialNavURL)
	}
	if !app.pendingInitialNav {
		t.Fatal("pendingInitialNav should be restored after the server becomes ready")
	}
}

func TestBridgeAcksLoadWhenInjectedAfterDOMContentLoaded(t *testing.T) {
	// 桥接脚本晚于 DOMContentLoaded 注入时，只挂监听器会永远等不到事件，
	// Go 侧收不到加载确认就会一直重试导航，把搜索结果冲掉。
	if !strings.Contains(bridgeScript, `document.readyState === "loading"`) {
		t.Fatal("bridgeScript should check document.readyState before waiting for DOMContentLoaded")
	}
	// 两个分支都要补发确认。
	if strings.Count(bridgeScript, `notifyAppState("loaded")`) < 2 {
		t.Fatal(`bridgeScript should notify "loaded" both when loading and when already ready`)
	}
}

func TestBridgeSyncsSPANavigationURL(t *testing.T) {
	for _, want := range []string{
		"nativePushState",
		"nativeReplaceState",
		`"url:" + url`,
	} {
		if !strings.Contains(bridgeScript, want) {
			t.Fatalf("bridgeScript missing SPA URL sync token %q", want)
		}
	}
}

func TestHandleWebViewMessageSPAURLTracksCurrentURL(t *testing.T) {
	app := newDesktopApp(nil, nil)
	raw := "http://127.0.0.1:37777/music/search?q=%E5%91%A8%E6%9D%B0%E4%BC%A6"
	app.handleWebViewMessage("url:" + raw)

	if app.pendingExternalOpenTo != nil {
		t.Fatal("url: message must not be treated as an external download")
	}
	if app.currentWebURL != raw {
		t.Fatalf("currentWebURL = %q, want %q", app.currentWebURL, raw)
	}
	if app.initialNavAcked {
		t.Fatal("SPA URL sync must not acknowledge the initial navigation")
	}
}

func gotURL(app *desktopApp) string {
	if app.pendingExternalOpenTo == nil {
		return ""
	}
	return app.pendingExternalOpenTo.String()
}
