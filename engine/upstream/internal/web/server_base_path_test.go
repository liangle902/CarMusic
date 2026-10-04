package web

import "testing"

func TestNormalizeBasePath(t *testing.T) {
	tests := []struct {
		name string
		in   string
		want string
	}{
		{name: "empty falls back to default", in: "", want: DefaultRoutePrefix},
		{name: "default is stable", in: "/music", want: "/music"},
		{name: "trailing slash is stripped", in: "/dl/", want: "/dl"},
		{name: "multiple trailing slashes are stripped", in: "/dl///", want: "/dl"},
		{name: "leading slash is added", in: "dl", want: "/dl"},
		{name: "whitespace is trimmed", in: "  /dl/  ", want: "/dl"},
		{name: "root falls back to default", in: "/", want: DefaultRoutePrefix},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := NormalizeBasePath(tt.in); got != tt.want {
				t.Fatalf("NormalizeBasePath(%q) = %q, want %q", tt.in, got, tt.want)
			}
		})
	}
}
