package web

import (
	"strings"
	"testing"
)

func TestBatchDownloadUsesCompactRecordBackedFeedback(t *testing.T) {
	appContent, err := templateFS.ReadFile("templates/static/js/app.js")
	if err != nil {
		t.Fatalf("read app.js: %v", err)
	}
	appJS := string(appContent)

	start := strings.Index(appJS, "async function batchDownload()")
	end := strings.Index(appJS, "async function deleteLocalMusic(")
	if start < 0 || end <= start {
		t.Fatal("could not isolate batchDownload implementation")
	}
	batchDownloadJS := appJS[start:end]

	for _, unwanted := range []string{
		"showDownloadPanel(",
		"updateDownloadPanelItem(",
		"/api/downloads/precheck",
		"buildBatchFailureMessage(",
		"webSettings.downloadDir",
	} {
		if strings.Contains(batchDownloadJS, unwanted) {
			t.Fatalf("batchDownload should not contain verbose feedback token %q", unwanted)
		}
	}

	for _, want := range []string{
		"确认批量下载",
		"downloadNoticeDuration()",
		"批量下载已开始",
		"右侧“下载记录”",
		`setDownloadRecordsButtonState("downloading")`,
		`setDownloadRecordsButtonState("updated")`,
		"dismissBatchStartNotice(true)",
		"refreshOpenDownloadRecords()",
	} {
		if !strings.Contains(batchDownloadJS, want) {
			t.Fatalf("batchDownload missing compact feedback token %q", want)
		}
	}
	if strings.Count(batchDownloadJS, "showToast(") != 2 {
		t.Fatalf("batchDownload should show exactly two compact notices, got %d", strings.Count(batchDownloadJS, "showToast("))
	}

	indexContent, err := templateFS.ReadFile("templates/pages/index.html")
	if err != nil {
		t.Fatalf("read index.html: %v", err)
	}
	indexHTML := string(indexContent)
	if strings.Contains(indexHTML, `id="download-panel"`) {
		t.Fatal("index.html should not render the large batch download panel")
	}
	for _, want := range []string{
		`id="download-records-button"`,
		`class="rt-btn rt-btn-download-records"`,
		`aria-label="下载记录"`,
	} {
		if !strings.Contains(indexHTML, want) {
			t.Fatalf("download records entry missing %q", want)
		}
	}

	styleContent, err := templateFS.ReadFile("templates/static/css/style.css")
	if err != nil {
		t.Fatalf("read style.css: %v", err)
	}
	if strings.Contains(string(styleContent), ".download-panel {") {
		t.Fatal("style.css should not retain the removed large download panel")
	}
}
