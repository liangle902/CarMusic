package web

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestLocalHostTokenProtectsEveryBusinessRoute(t *testing.T) {
	gin.SetMode(gin.TestMode)
	token := strings.Repeat("a", 64)
	router := gin.New()
	router.Use(localTokenRequired(token, "/music/healthz"))
	router.NoRoute(func(c *gin.Context) { c.Status(http.StatusOK) })
	for _, path := range []string{"/music/cookies", "/music/settings", "/music/native/offline", "/music/download", "/music/cover_proxy", "/music/videos/song.mp4", "/music/static/main.js", "/music/qr_login", "/"} {
		for _, method := range []string{http.MethodGet, http.MethodPost, http.MethodDelete, http.MethodOptions, http.MethodHead} {
			for _, header := range []string{"", token, "Bearer wrong", "Bearer " + token} {
				request := httptest.NewRequest(method, path+"?token="+token, nil)
				request.Header.Set("Authorization", header)
				request.Header.Set("X-Requested-With", "XMLHttpRequest")
				response := httptest.NewRecorder()
				router.ServeHTTP(response, request)
				expected := http.StatusUnauthorized
				if header == "Bearer "+token {
					expected = http.StatusOK
				}
				if response.Code != expected {
					t.Fatalf("%s %s: got %d expected %d", method, path, response.Code, expected)
				}
			}
		}
	}
}

func TestLocalHealthOnlyAllowsReadWithoutToken(t *testing.T) {
	gin.SetMode(gin.TestMode)
	router := gin.New()
	router.Use(localTokenRequired(strings.Repeat("a", 64), "/music/healthz"))
	router.NoRoute(func(c *gin.Context) { c.Status(http.StatusOK) })
	for _, test := range []struct {
		method, path string
		expected     int
	}{
		{"GET", "/music/healthz", 200}, {"HEAD", "/music/healthz", 200},
		{"POST", "/music/healthz", 401}, {"GET", "/music/healthz/anything", 401},
		{"GET", "/healthz", 401},
	} {
		response := httptest.NewRecorder()
		router.ServeHTTP(response, httptest.NewRequest(test.method, test.path, nil))
		if response.Code != test.expected {
			t.Fatalf("%s %s: %d", test.method, test.path, response.Code)
		}
	}
}
