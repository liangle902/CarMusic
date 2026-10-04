package core

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"os"
	"strings"

	"gorm.io/gorm"
)

const encryptedConfigPrefix = "carmusic-aes-gcm-v1:"

func configCipher() (cipher.AEAD, error) {
	encoded := os.Getenv("MUSIC_DL_CONFIG_KEY")
	if encoded == "" {
		return nil, nil
	} // Upstream desktop/CLI storage remains compatible.
	key, err := hex.DecodeString(encoded)
	if err != nil || len(key) != 32 {
		return nil, errors.New("invalid configuration storage key")
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

func sealConfig(value, identity string) (string, error) {
	aead, err := configCipher()
	if err != nil || aead == nil {
		return value, err
	}
	nonce := make([]byte, aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	encrypted := aead.Seal(nonce, nonce, []byte(value), []byte(identity))
	return encryptedConfigPrefix + base64.StdEncoding.EncodeToString(encrypted), nil
}

func openConfig(value, identity string) (string, error) {
	if !strings.HasPrefix(value, encryptedConfigPrefix) {
		return value, nil
	}
	aead, err := configCipher()
	if err != nil {
		return "", err
	}
	if aead == nil {
		return "", errors.New("encrypted configuration requires its storage key")
	}
	data, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(value, encryptedConfigPrefix))
	if err != nil || len(data) < aead.NonceSize()+aead.Overhead() {
		return "", errors.New("invalid encrypted configuration")
	}
	plain, err := aead.Open(nil, data[:aead.NonceSize()], data[aead.NonceSize():], []byte(identity))
	if err != nil {
		return "", errors.New("configuration authentication failed")
	}
	return string(plain), nil
}

// Hooks keep decrypted values in memory; SQLite and its journals only see ciphertext.
func (row *configKV) BeforeSave(_ *gorm.DB) error {
	plain, err := openConfig(row.Value, "setting:"+row.Key)
	if err != nil {
		return err
	}
	value, err := sealConfig(plain, "setting:"+row.Key)
	if err == nil {
		row.Value = value
	}
	return err
}
func (row *configKV) AfterFind(_ *gorm.DB) error {
	value, err := openConfig(row.Value, "setting:"+row.Key)
	if err == nil {
		row.Value = value
	}
	return err
}
func (row *cookieEntry) BeforeSave(_ *gorm.DB) error {
	plain, err := openConfig(row.Value, "cookie:"+row.Source)
	if err != nil {
		return err
	}
	value, err := sealConfig(plain, "cookie:"+row.Source)
	if err == nil {
		row.Value = value
	}
	return err
}
func (row *cookieEntry) AfterFind(_ *gorm.DB) error {
	value, err := openConfig(row.Value, "cookie:"+row.Source)
	if err == nil {
		row.Value = value
	}
	return err
}

func encryptExistingConfig(db *gorm.DB) error {
	aead, err := configCipher()
	if err != nil || aead == nil {
		return err
	}
	// secure_delete plus VACUUM removes plaintext from old SQLite free pages.
	if err := db.Exec("PRAGMA secure_delete = ON").Error; err != nil {
		return err
	}
	changed := false
	err = db.Transaction(func(tx *gorm.DB) error {
		for _, table := range []struct{ name, key, prefix string }{
			{"config_kvs", "key", "setting:"}, {"cookie_entries", "source", "cookie:"},
		} {
			var rows []struct {
				Identity string
				Value    string
			}
			if err := tx.Table(table.name).Select(table.key + " AS identity, value").Scan(&rows).Error; err != nil {
				return err
			}
			for _, row := range rows {
				identity := table.prefix + row.Identity
				if strings.HasPrefix(row.Value, encryptedConfigPrefix) {
					if _, err := openConfig(row.Value, identity); err != nil {
						return err
					}
					continue
				}
				encrypted, err := sealConfig(row.Value, identity)
				if err != nil {
					return err
				}
				if err := tx.Exec("UPDATE "+table.name+" SET value = ? WHERE "+table.key+" = ?", encrypted, row.Identity).Error; err != nil {
					return err
				}
				changed = true
			}
		}
		return nil
	})
	if err != nil {
		return err
	}
	if changed {
		if err := db.Exec("PRAGMA wal_checkpoint(TRUNCATE)").Error; err != nil {
			return err
		}
		return db.Exec("VACUUM").Error
	}
	return nil
}
