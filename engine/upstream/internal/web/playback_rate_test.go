package web

import (
	"strings"
	"testing"
)

func TestPlaybackRateControlsUseSharedState(t *testing.T) {
	appContent, err := templateFS.ReadFile("templates/static/js/app.js")
	if err != nil {
		t.Fatalf("ReadFile(app.js): %v", err)
	}
	videoContent, err := templateFS.ReadFile("templates/static/js/videogen.js")
	if err != nil {
		t.Fatalf("ReadFile(videogen.js): %v", err)
	}

	appJS := string(appContent)
	for _, want := range []string{
		"window.PLAYER_SPEEDS = PLAYER_SPEEDS;",
		"window.setPlayerPlaybackRate = setPlayerPlaybackRate;",
		"window.applyPlayerPlaybackRate = applyPlayerPlaybackRate;",
		`ap.audio.addEventListener("emptied", () => {`,
		"applyPlayerPlaybackRate(playerSpeed);",
	} {
		if !strings.Contains(appJS, want) {
			t.Fatalf("app.js missing playback rate token %q", want)
		}
	}

	videoJS := string(videoContent)
	for _, want := range []string{
		"window.PLAYER_SPEEDS",
		"window.setPlayerPlaybackRate(normalized);",
		"this.getCurrentPlaybackRate()",
		"2.25",
		"2.75",
	} {
		if !strings.Contains(videoJS, want) {
			t.Fatalf("videogen.js missing shared playback rate token %q", want)
		}
	}
	if strings.Contains(videoJS, "[0.5, 1, 1.25, 1.5, 2]") {
		t.Fatal("videogen.js still uses the old limited playback rate list")
	}
}
