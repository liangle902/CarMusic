package web

import (
	"crypto/subtle"
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
)

// A loopback listener is reachable by other Android UIDs. The native host
// supplies a new private token for each engine instance; never accept it in URLs.
func localTokenRequired(token, healthPath string) gin.HandlerFunc {
	return func(c *gin.Context) {
		if c.Request.URL.Path == healthPath && (c.Request.Method == http.MethodGet || c.Request.Method == http.MethodHead) {
			c.Next()
			return
		}
		header := c.GetHeader("Authorization")
		provided := strings.TrimPrefix(header, "Bearer ")
		if !strings.HasPrefix(header, "Bearer ") || subtle.ConstantTimeCompare([]byte(provided), []byte(token)) != 1 {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"error": "unauthorized local request"})
			return
		}
		c.Next()
	}
}
