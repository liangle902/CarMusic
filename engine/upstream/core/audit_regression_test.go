package core

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func prepareAuditDB(t *testing.T) {
	t.Helper()
	directory := t.TempDir()
	t.Setenv("MUSIC_DL_CONFIG_DB", filepath.Join(directory, "settings.db"))
	t.Setenv("MUSIC_DL_COOKIE_FILE", filepath.Join(directory, "absent.json"))
	t.Setenv("MUSIC_DL_CONFIG_KEY", strings.Repeat("ab", 32))
	resetConfigStateForTest()
	t.Cleanup(resetConfigStateForTest)
	if err := ensureConfigDB(); err != nil {
		t.Fatal(err)
	}
}

func TestRepeatedEncryptedModelSavePreservesPlaintext(t *testing.T) {
	prepareAuditDB(t)
	row := configKV{Key: "repeated", Value: "same-setting"}
	for attempt := 0; attempt < 3; attempt++ {
		if err := configDB.Save(&row).Error; err != nil {
			t.Fatal(err)
		}
		var raw string
		if err := configDB.Raw("SELECT value FROM config_kvs WHERE key = ?", row.Key).Row().Scan(&raw); err != nil {
			t.Fatal(err)
		}
		if !strings.HasPrefix(raw, encryptedConfigPrefix) {
			t.Fatal("plaintext was stored")
		}
		plain, err := openConfig(raw, "setting:"+row.Key)
		if err != nil || plain != "same-setting" {
			t.Fatal("persisted value has multiple encryption layers")
		}
	}
	// A failed database write may leave a model in its BeforeSave representation.
	row.Value, _ = sealConfig("same-setting", "setting:"+row.Key)
	if err := configDB.Save(&row).Error; err != nil {
		t.Fatal(err)
	}
	var restored configKV
	if err := configDB.First(&restored, "key = ?", row.Key).Error; err != nil {
		t.Fatal(err)
	}
	if restored.Value != "same-setting" {
		t.Fatal("retry changed the stored plaintext")
	}
	cookie := cookieEntry{Source: "qq", Value: "cookie-value"}
	for attempt := 0; attempt < 2; attempt++ {
		if err := configDB.Save(&cookie).Error; err != nil {
			t.Fatal(err)
		}
		var restoredCookie cookieEntry
		if err := configDB.First(&restoredCookie, "source = ?", cookie.Source).Error; err != nil {
			t.Fatal(err)
		}
		if restoredCookie.Value != "cookie-value" {
			t.Fatal("repeated cookie save added an encryption layer")
		}
	}
}

func TestFailedCookieSaveDoesNotPublishOrEraseCredentials(t *testing.T) {
	prepareAuditDB(t)
	if err := CM.UpdateAndSave(map[string]string{"qq": "original"}); err != nil {
		t.Fatal(err)
	}
	if err := configDB.Exec("CREATE TRIGGER reject_cookie_insert BEFORE INSERT ON cookie_entries BEGIN SELECT RAISE(ABORT, 'simulated storage failure'); END").Error; err != nil {
		t.Fatal(err)
	}
	if err := CM.UpdateAndSave(map[string]string{"qq": "replacement", "netease": "new"}); err == nil {
		t.Fatal("failed save was reported successful")
	}
	if CM.Get("qq") != "original" || CM.Get("netease") != "" {
		t.Fatal("failed transaction changed in-memory credentials")
	}
	CM.Load()
	if CM.Get("qq") != "original" || CM.Get("netease") != "" {
		t.Fatal("failed transaction changed stored credentials")
	}
}

func TestConfigurationInitializationRetriesAfterDirectoryRecovery(t *testing.T) {
	directory := t.TempDir()
	blocked := filepath.Join(directory, "blocked")
	t.Setenv("MUSIC_DL_CONFIG_DB", filepath.Join(blocked, "settings.db"))
	t.Setenv("MUSIC_DL_COOKIE_FILE", filepath.Join(directory, "absent.json"))
	t.Setenv("MUSIC_DL_CONFIG_KEY", "")
	resetConfigStateForTest()
	t.Cleanup(resetConfigStateForTest)
	if err := os.WriteFile(blocked, []byte("not a directory"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := ensureConfigDB(); err == nil {
		t.Fatal("invalid directory should fail")
	}
	if err := os.Remove(blocked); err != nil {
		t.Fatal(err)
	}
	if err := ensureConfigDB(); err != nil {
		t.Fatalf("storage did not recover: %v", err)
	}
}
