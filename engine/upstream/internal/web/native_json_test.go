package web

import (
	"encoding/json"
	"github.com/gin-gonic/gin"
	"github.com/guohuiyuan/music-lib/model"
	"net/http/httptest"
	"testing"
)

func TestNativeJSONContainsSourceTabPlaylists(t *testing.T) {
	initCollectionDBForTest(t)
	gin.SetMode(gin.TestMode)
	recorder := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(recorder)
	c.Request = httptest.NewRequest("GET", "/music/recommend?format=json", nil)
	c.Set("PlaylistSourceTabs", playlistSourceTabsData{Tabs: []playlistSourceTab{
		{Source: "qq", Playlists: []model.Playlist{{ID: "qq-list", Name: "QQ歌单", Source: "qq"}}},
		{Source: "netease", Playlists: []model.Playlist{{ID: "ne-list", Name: "云音乐歌单", Source: "netease"}}},
	}})
	renderIndex(c, nil, nil, "", nil, "", "playlist", "", "", "", false, "", nil)
	var result struct {
		Playlists []model.Playlist `json:"playlists"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if len(result.Playlists) != 2 || result.Playlists[0].ID != "qq-list" || result.Playlists[1].Source != "netease" {
		t.Fatalf("source tab data lost: %+v", result.Playlists)
	}
}

func TestNativeJSONDoesNotTruncatePlaylistSongs(t *testing.T) {
	initCollectionDBForTest(t)
	recorder := httptest.NewRecorder()
	c, _ := gin.CreateTestContext(recorder)
	c.Request = httptest.NewRequest("GET", "/music/playlist?format=json", nil)
	songs := make([]model.Song, 51)
	for i := range songs {songs[i].Name = "song"}
	renderIndex(c, songs, nil, "", nil, "", "song", "", "", "", false, "", nil)
	var result struct {Songs []model.Song `json:"songs"`; Total int `json:"total"`}
	if err := json.Unmarshal(recorder.Body.Bytes(), &result); err != nil {t.Fatal(err)}
	if len(result.Songs) != 51 || result.Total != 51 {t.Fatalf("playlist truncated: got %d, total %d",len(result.Songs),result.Total)}
}
