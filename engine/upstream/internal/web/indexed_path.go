package web

import (
	"errors"
	"path/filepath"
	"strings"
)

// Index rows are not a filesystem boundary, including after corruption or migration.
func indexedLocalPath(rootAbs, relative string) (string, error) {
	clean := filepath.Clean(filepath.FromSlash(strings.TrimSpace(relative)))
	if clean == "." || filepath.IsAbs(clean) {
		return "", errors.New("invalid local music path")
	}
	target := filepath.Join(rootAbs, clean)
	if !isPathInside(rootAbs, target) {
		return "", errors.New("local music path escaped root")
	}
	actualRoot, err := filepath.EvalSymlinks(rootAbs)
	if err != nil {
		return "", err
	}
	actualTarget, err := filepath.EvalSymlinks(target)
	if err != nil {
		return "", err
	}
	if !isPathInside(actualRoot, actualTarget) {
		return "", errors.New("local music link escaped root")
	}
	return actualTarget, nil
}
