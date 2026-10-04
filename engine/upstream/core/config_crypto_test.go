package core

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestConfigurationEncryptionAuthenticationAndRandomNonce(t *testing.T) {
	t.Setenv("MUSIC_DL_CONFIG_KEY", strings.Repeat("ab", 32))
	first, err := sealConfig("secret-cookie", "cookie:qq")
	if err != nil {
		t.Fatal(err)
	}
	second, err := sealConfig("secret-cookie", "cookie:qq")
	if err != nil {
		t.Fatal(err)
	}
	if first == second || strings.Contains(first, "secret-cookie") {
		t.Fatal("ciphertext must hide content and use fresh nonces")
	}
	plain, err := openConfig(first, "cookie:qq")
	if err != nil || plain != "secret-cookie" {
		t.Fatal("round trip failed")
	}
	if _, err := openConfig(first, "cookie:netease"); err == nil {
		t.Fatal("swapped row must fail authentication")
	}
	t.Setenv("MUSIC_DL_CONFIG_KEY", strings.Repeat("cd", 32))
	if _, err := openConfig(first, "cookie:qq"); err == nil {
		t.Fatal("wrong key must fail authentication")
	}
	t.Setenv("MUSIC_DL_CONFIG_KEY", "")
	if _, err := openConfig(first, "cookie:qq"); err == nil {
		t.Fatal("missing key must not return ciphertext as cookie")
	}
}

func TestConfigurationMigratesPlaintextAndSurvivesReopen(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "settings.db")
	t.Setenv("MUSIC_DL_CONFIG_DB", path)
	t.Setenv("MUSIC_DL_COOKIE_FILE", filepath.Join(directory, "missing.json"))
	t.Setenv("MUSIC_DL_CONFIG_KEY", "")
	resetConfigStateForTest()
	t.Cleanup(resetConfigStateForTest)
	CM.Load()
	CM.SetAll(map[string]string{"qq": "unique-sensitive-cookie-marker"})
	CM.Save()
	settings := GetWebSettings()
	settings.WebDAVPassword = "unique-sensitive-webdav-marker"
	if err := SaveWebSettings(settings); err != nil {
		t.Fatal(err)
	}
	resetConfigStateForTest()
	t.Setenv("MUSIC_DL_CONFIG_KEY", strings.Repeat("ab", 32))
	CM.Load()
	if err := ensureConfigDB(); err != nil {
		t.Fatal(err)
	}
	if CM.Get("qq") != "unique-sensitive-cookie-marker" {
		t.Fatal("migration lost cookie")
	}
	if GetWebSettings().WebDAVPassword != "unique-sensitive-webdav-marker" {
		t.Fatal("migration lost settings")
	}
	// Also exercise new encrypted writes and hook-based batch inserts.
	CM.SetAll(map[string]string{"qq": "unique-updated-cookie-marker", "netease": "unique-other-cookie-marker"})
	CM.Save()
	resetConfigStateForTest()
	CM.Load()
	if CM.Get("qq") != "unique-updated-cookie-marker" || CM.Get("netease") != "unique-other-cookie-marker" {
		t.Fatal("encrypted restore failed")
	}
	for _, candidate := range []string{path, path + "-wal", path + "-journal"} {
		data, err := os.ReadFile(candidate)
		if os.IsNotExist(err) {
			continue
		}
		if err != nil {
			t.Fatal(err)
		}
		for _, marker := range []string{"unique-sensitive-cookie-marker", "unique-sensitive-webdav-marker", "unique-updated-cookie-marker", "unique-other-cookie-marker"} {
			if strings.Contains(string(data), marker) {
				t.Fatal("plaintext remains in SQLite or journal")
			}
		}
	}
}
