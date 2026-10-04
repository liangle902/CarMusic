package web

import (
	"strings"
	"testing"
)

func TestIssue240SettingsAndPlayerPolish(t *testing.T) {
	appContent, err := templateFS.ReadFile("templates/static/js/app.js")
	if err != nil {
		t.Fatalf("ReadFile(app.js): %v", err)
	}
	modalContent, err := templateFS.ReadFile("templates/partials/modals.html")
	if err != nil {
		t.Fatalf("ReadFile(modals.html): %v", err)
	}
	styleContent, err := templateFS.ReadFile("templates/static/css/style.css")
	if err != nil {
		t.Fatalf("ReadFile(style.css): %v", err)
	}

	appJS := string(appContent)
	modalsHTML := string(modalContent)
	styleCSS := string(styleContent)

	for _, want := range []string{
		`"/home/appuser/data"`,
		"downloadTipDuration",
		"downloadNoticeDuration()",
		`btn.classList.toggle("is-stop", isPlaying)`,
		"bindSettingsTabs()",
	} {
		if !strings.Contains(appJS, want) {
			t.Fatalf("app.js missing issue #240 token %q", want)
		}
	}

	for _, want := range []string{
		`data-settings-tab="general"`,
		`data-settings-tab="download"`,
		`data-settings-tab="cookies"`,
		`id="setting-download-tip-duration"`,
		`value="/home/appuser/data"`,
	} {
		if !strings.Contains(modalsHTML, want) {
			t.Fatalf("modals.html missing issue #240 token %q", want)
		}
	}

	downloadStart := strings.Index(modalsHTML, `id="downloadRecordsModal"`)
	playbackStart := strings.Index(modalsHTML, `id="playbackHistoryModal"`)
	if downloadStart < 0 || playbackStart <= downloadStart {
		t.Fatal("could not isolate utility record modals")
	}
	downloadModal := modalsHTML[downloadStart:playbackStart]
	playbackModal := modalsHTML[playbackStart:]
	if strings.Contains(downloadModal, "download-records-footer") {
		t.Fatal("download records modal should not render a duplicate footer close button")
	}
	if strings.Contains(playbackModal, "utility-modal-footer") {
		t.Fatal("playback history modal should not render a duplicate footer close button")
	}

	for _, want := range []string{
		".btn-play.is-stop",
		".settings-tabs",
		".settings-tab-panel",
		".settings-save-bar",
		".aplayer.aplayer-fixed.aplayer-narrow .player-speed-wrap",
	} {
		if !strings.Contains(styleCSS, want) {
			t.Fatalf("style.css missing issue #240 token %q", want)
		}
	}
}
