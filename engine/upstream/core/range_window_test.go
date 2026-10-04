package core

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

func TestParallelRangeWindowPreservesBytesAndBoundsConcurrency(t *testing.T) {
	data := bytes.Repeat([]byte("0123456789abcdef"), 400000)
	var active, maximum atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		current := active.Add(1)
		defer active.Add(-1)
		for old := maximum.Load(); current > old; old = maximum.Load() {
			if maximum.CompareAndSwap(old, current) {
				break
			}
		}
		var start, end int
		if _, err := fmt.Sscanf(r.Header.Get("Range"), "bytes=%d-%d", &start, &end); err != nil {
			t.Error(err)
			w.WriteHeader(400)
			return
		}
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write(data[start : end+1])
	}))
	defer server.Close()
	var output bytes.Buffer
	if err := writeParallelRange(&output, server.URL, "", 0, int64(len(data)-1)); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(output.Bytes(), data) {
		t.Fatal("ranged audio bytes changed order")
	}
	if maximum.Load() > 16 {
		t.Fatal("too many concurrent range requests")
	}
}

type failingRangeWriter struct{}

func (failingRangeWriter) Write([]byte) (int, error) { return 0, errors.New("destination closed") }

func TestHugeAdvertisedRangeDoesNotScheduleWholeFile(t *testing.T) {
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		var start, end int64
		_, _ = fmt.Sscanf(r.Header.Get("Range"), "bytes=%d-%d", &start, &end)
		w.WriteHeader(http.StatusPartialContent)
		_, _ = io.CopyN(w, bytes.NewReader(make([]byte, 256*1024)), end-start+1)
	}))
	err := writeParallelRange(failingRangeWriter{}, server.URL, "", 0, 1<<50)
	server.Close()
	if err == nil {
		t.Fatal("writer failure ignored")
	}
	if requests.Load() > 16 {
		t.Fatal("scheduled beyond the first bounded window")
	}
}
