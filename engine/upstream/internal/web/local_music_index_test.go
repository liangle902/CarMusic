package web

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/guohuiyuan/go-music-dl/core"
	"github.com/guohuiyuan/music-lib/model"
)

// 扫盘对账：本地已经没有文件的歌，去重记录要一并回收，否则再下载会被判成「已下载」。
func TestReconcileDownloadDedupWithScanDropsMissingTracks(t *testing.T) {
	initCollectionDBForTest(t)

	keepSong := &model.Song{Name: "Keep", Artist: "Artist"}
	dropSong := &model.Song{Name: "Drop", Artist: "Artist"}
	if err := core.SaveDownloadDedupEntry(keepSong.Name, keepSong.Artist, "Artist - Keep.flac"); err != nil {
		t.Fatalf("seed keep entry: %v", err)
	}
	if err := core.SaveDownloadDedupEntry(dropSong.Name, dropSong.Artist, "Artist - Drop.flac"); err != nil {
		t.Fatalf("seed drop entry: %v", err)
	}
	t.Cleanup(func() {
		_ = core.ForgetDownloadedSong(keepSong.Name, keepSong.Artist, "Artist - Keep.flac")
		_ = core.ForgetDownloadedSong(dropSong.Name, dropSong.Artist, "Artist - Drop.flac")
	})

	reconcileDownloadDedupWithScan([]*localMusicTrack{
		{Name: "Keep", Artist: "Artist", RelPath: "Artist - Keep.flac"},
	}, t.TempDir())

	index, err := core.LoadDownloadDedupSet()
	if err != nil {
		t.Fatalf("LoadDownloadDedupSet: %v", err)
	}
	if !core.IsSongDownloaded(keepSong, index) {
		t.Fatal("文件还在本地，去重记录不该被回收")
	}
	if core.IsSongDownloaded(dropSong, index) {
		t.Fatal("文件已不在本地，去重记录应当被回收")
	}
}

// 扫描结果为空（下载目录未挂载或目录被清空）时必须保守，不能清空去重索引。
func TestReconcileDownloadDedupWithScanKeepsEntriesWhenScanIsEmpty(t *testing.T) {
	initCollectionDBForTest(t)

	song := &model.Song{Name: "Keep", Artist: "Artist"}
	if err := core.SaveDownloadDedupEntry(song.Name, song.Artist, "Artist - Keep.flac"); err != nil {
		t.Fatalf("seed entry: %v", err)
	}
	t.Cleanup(func() { _ = core.ForgetDownloadedSong(song.Name, song.Artist, "Artist - Keep.flac") })

	reconcileDownloadDedupWithScan(nil, t.TempDir())

	index, err := core.LoadDownloadDedupSet()
	if err != nil {
		t.Fatalf("LoadDownloadDedupSet: %v", err)
	}
	if !core.IsSongDownloaded(song, index) {
		t.Fatal("空扫描结果时不应回收任何去重记录")
	}
}

// 旧去重记录没有文件路径：磁盘上已经没有这首歌时，下载前要把它清掉。
func TestResolveLegacyDedupForSongForgetsMissingFile(t *testing.T) {
	initCollectionDBForTest(t)

	downloadDir := t.TempDir()
	withLocalMusicDownloadDir(t, downloadDir)

	song := &model.Song{Name: "Gone", Artist: "Artist"}
	// 索引里还留着这首歌的行（还没被扫盘清掉），但文件确实不在磁盘上。
	if err := db.Create(&LocalMusicIndex{ID: encodeLocalMusicID("Gone.mp3"), RelPath: "Gone.mp3", Name: "Gone", Artist: "Artist"}).Error; err != nil {
		t.Fatalf("seed index row: %v", err)
	}
	if err := core.SaveDownloadDedupEntry(song.Name, song.Artist, ""); err != nil {
		t.Fatalf("seed legacy dedup entry: %v", err)
	}
	t.Cleanup(func() { _ = core.ForgetDownloadedSong(song.Name, song.Artist, "") })

	index, err := core.LoadDownloadDedupSet()
	if err != nil {
		t.Fatalf("LoadDownloadDedupSet: %v", err)
	}
	if !core.IsSongDownloaded(song, index) {
		t.Fatal("前置条件：旧记录应当先能被命中")
	}

	resolveLegacyDedupForSong(index, song)

	if core.IsSongDownloaded(song, index) {
		t.Fatal("文件已不在磁盘上，旧去重记录应被回收")
	}
	reloaded, err := core.LoadDownloadDedupSet()
	if err != nil {
		t.Fatalf("LoadDownloadDedupSet after resolve: %v", err)
	}
	if core.IsSongDownloaded(song, reloaded) {
		t.Fatal("回收结果需要写回数据库，不能只改内存")
	}
}

// 全量扫描已经把旧索引行清空时，也必须能判断旧去重记录对应的文件已经不在
// 下载目录里。否则当下载目录只剩这一首歌时，删除文件后仍会一直「跳过」。
func TestResolveLegacyDedupForSongForgetsMissingFileWhenIndexWasSwept(t *testing.T) {
	initCollectionDBForTest(t)

	withLocalMusicDownloadDir(t, t.TempDir())

	song := &model.Song{Name: "Gone", Artist: "Artist"}
	if err := core.SaveDownloadDedupEntry(song.Name, song.Artist, ""); err != nil {
		t.Fatalf("seed legacy dedup entry: %v", err)
	}
	t.Cleanup(func() { _ = core.ForgetDownloadedSong(song.Name, song.Artist, "") })

	index, err := core.LoadDownloadDedupSet()
	if err != nil {
		t.Fatalf("LoadDownloadDedupSet: %v", err)
	}
	if !core.IsSongDownloaded(song, index) {
		t.Fatal("前置条件：旧记录应当先能被命中")
	}

	resolveLegacyDedupForSong(index, song)

	if core.IsSongDownloaded(song, index) {
		t.Fatal("扫描已确认下载目录为空，旧去重记录不能被保留")
	}
}

// 下载目录整体不可用时不能把旧记录当成文件已删除，否则 NAS 挂载恢复后会
// 重复下载整批歌曲。
func TestResolveLegacyDedupForSongKeepsEntryWhenDownloadDirMissing(t *testing.T) {
	initCollectionDBForTest(t)

	withLocalMusicDownloadDir(t, filepath.Join(t.TempDir(), "missing"))

	song := &model.Song{Name: "Keep", Artist: "Artist"}
	if err := core.SaveDownloadDedupEntry(song.Name, song.Artist, ""); err != nil {
		t.Fatalf("seed legacy dedup entry: %v", err)
	}
	t.Cleanup(func() { _ = core.ForgetDownloadedSong(song.Name, song.Artist, "") })

	index, err := core.LoadDownloadDedupSet()
	if err != nil {
		t.Fatalf("LoadDownloadDedupSet: %v", err)
	}

	resolveLegacyDedupForSong(index, song)

	if !core.IsSongDownloaded(song, index) {
		t.Fatal("下载目录不存在时必须保留旧去重记录")
	}
}

// 相似歌名不能代替原歌曲。例如原文件已删除、只剩 Hello (Live) 时，不能把
// 它回填给旧的 Hello 记录，否则再次下载仍会被误判为“已下载”。
func TestResolveLegacyDedupForSongDoesNotUseFuzzyTitleMatch(t *testing.T) {
	initCollectionDBForTest(t)

	downloadDir := t.TempDir()
	withLocalMusicDownloadDir(t, downloadDir)

	const relPath = "Hello (Live).mp3"
	if err := os.WriteFile(filepath.Join(downloadDir, relPath), []byte("audio"), 0644); err != nil {
		t.Fatalf("write local audio: %v", err)
	}
	if err := db.Create(&LocalMusicIndex{
		ID:      encodeLocalMusicID(relPath),
		RelPath: relPath,
		Name:    "Hello (Live)",
		Artist:  "Artist",
	}).Error; err != nil {
		t.Fatalf("seed index row: %v", err)
	}

	song := &model.Song{Name: "Hello", Artist: "Artist"}
	if err := core.SaveDownloadDedupEntry(song.Name, song.Artist, ""); err != nil {
		t.Fatalf("seed legacy dedup entry: %v", err)
	}
	t.Cleanup(func() { _ = core.ForgetDownloadedSong(song.Name, song.Artist, "") })

	index, err := core.LoadDownloadDedupSet()
	if err != nil {
		t.Fatalf("LoadDownloadDedupSet: %v", err)
	}

	resolveLegacyDedupForSong(index, song)

	if core.IsSongDownloaded(song, index) {
		t.Fatal("相似歌名的文件不能替代原歌曲的去重记录")
	}
}

// 旧去重记录对应的文件还在：顺手把文件路径补回去，后续判定直接走磁盘校验。
func TestResolveLegacyDedupForSongBackfillsRelPath(t *testing.T) {
	initCollectionDBForTest(t)

	downloadDir := t.TempDir()
	withLocalMusicDownloadDir(t, downloadDir)

	if err := os.WriteFile(filepath.Join(downloadDir, "Here.mp3"), []byte("audio"), 0644); err != nil {
		t.Fatalf("write local audio: %v", err)
	}

	song := &model.Song{Name: "Here", Artist: "Artist"}
	if err := db.Create(&LocalMusicIndex{ID: encodeLocalMusicID("Here.mp3"), RelPath: "Here.mp3", Name: "Here", Artist: "Artist"}).Error; err != nil {
		t.Fatalf("seed index row: %v", err)
	}
	if err := core.SaveDownloadDedupEntry(song.Name, song.Artist, ""); err != nil {
		t.Fatalf("seed legacy dedup entry: %v", err)
	}
	t.Cleanup(func() { _ = core.ForgetDownloadedSong(song.Name, song.Artist, "Here.mp3") })

	index, err := core.LoadDownloadDedupSet()
	if err != nil {
		t.Fatalf("LoadDownloadDedupSet: %v", err)
	}

	resolveLegacyDedupForSong(index, song)

	if got := core.SongFileRelPath(song, index); got != "Here.mp3" {
		t.Fatalf("SongFileRelPath = %q, want %q", got, "Here.mp3")
	}
	reloaded, err := core.LoadDownloadDedupSet()
	if err != nil {
		t.Fatalf("LoadDownloadDedupSet after resolve: %v", err)
	}
	if got := core.SongFileRelPath(song, reloaded); got != "Here.mp3" {
		t.Fatalf("补回的文件路径需要写进数据库，got %q", got)
	}
}

func TestLocalMusicIndexSyncUpsertsAndSweeps(t *testing.T) {
	initCollectionDBForTest(t)

	downloadDir := t.TempDir()
	withLocalMusicDownloadDir(t, downloadDir)

	keepPath := filepath.Join(downloadDir, "Keep Me.mp3")
	dropPath := filepath.Join(downloadDir, "Drop Me.mp3")
	if err := os.WriteFile(keepPath, []byte("keep"), 0644); err != nil {
		t.Fatalf("write keep: %v", err)
	}
	if err := os.WriteFile(dropPath, []byte("drop"), 0644); err != nil {
		t.Fatalf("write drop: %v", err)
	}

	if err := syncLocalMusicIndex(); err != nil {
		t.Fatalf("first sync: %v", err)
	}

	var count int64
	db.Model(&LocalMusicIndex{}).Count(&count)
	if count != 2 {
		t.Fatalf("index count after first sync = %d, want 2", count)
	}

	// Remove one file; next sync should sweep its row.
	if err := os.Remove(dropPath); err != nil {
		t.Fatalf("remove drop: %v", err)
	}
	if err := syncLocalMusicIndex(); err != nil {
		t.Fatalf("second sync: %v", err)
	}

	db.Model(&LocalMusicIndex{}).Count(&count)
	if count != 1 {
		t.Fatalf("index count after sweep = %d, want 1", count)
	}

	dropID := encodeLocalMusicID("Drop Me.mp3")
	var dropCount int64
	db.Model(&LocalMusicIndex{}).Where("id = ?", dropID).Count(&dropCount)
	if dropCount != 0 {
		t.Fatalf("swept row still present for %s", dropID)
	}
}

func TestLoadTracksFromIndexDropsStaleRowsAndCorrectsTotal(t *testing.T) {
	initCollectionDBForTest(t)

	downloadDir := t.TempDir()
	withLocalMusicDownloadDir(t, downloadDir)

	keepPath := filepath.Join(downloadDir, "Keep Me.mp3")
	dropPath := filepath.Join(downloadDir, "Drop Me.mp3")
	if err := os.WriteFile(keepPath, []byte("keep"), 0644); err != nil {
		t.Fatalf("write keep: %v", err)
	}
	if err := os.WriteFile(dropPath, []byte("drop"), 0644); err != nil {
		t.Fatalf("write drop: %v", err)
	}
	if err := syncLocalMusicIndex(); err != nil {
		t.Fatalf("sync index: %v", err)
	}
	if err := os.Remove(dropPath); err != nil {
		t.Fatalf("remove drop: %v", err)
	}

	tracks, total, ok := loadTracksFromIndex(0, 10)
	if !ok || len(tracks) != 1 || total != 1 {
		t.Fatalf("loadTracksFromIndex = tracks=%d total=%d ok=%t, want 1/1/true", len(tracks), total, ok)
	}
	if tracks[0].ID != encodeLocalMusicID("Keep Me.mp3") {
		t.Fatalf("remaining track ID = %q, want Keep Me", tracks[0].ID)
	}

	var count int64
	if err := db.Model(&LocalMusicIndex{}).Count(&count).Error; err != nil {
		t.Fatalf("count index rows: %v", err)
	}
	if count != 1 {
		t.Fatalf("stale index row was not removed, count = %d", count)
	}
}

func TestLocalMusicSearchSongsMatchesAndExcludesDeleted(t *testing.T) {
	initCollectionDBForTest(t)

	downloadDir := t.TempDir()
	withLocalMusicDownloadDir(t, downloadDir)

	songPath := filepath.Join(downloadDir, "Hello World.mp3")
	if err := os.WriteFile(songPath, []byte("hello"), 0644); err != nil {
		t.Fatalf("write song: %v", err)
	}
	if err := syncLocalMusicIndex(); err != nil {
		t.Fatalf("sync: %v", err)
	}

	// Case-insensitive match on name.
	got := localMusicSearchSongs("hello", 50)
	if len(got) != 1 || got[0].Source != localMusicSource {
		t.Fatalf("search = %+v, want 1 local song", got)
	}

	// No keyword match -> empty.
	if res := localMusicSearchSongs("nonexistent-keyword", 50); len(res) != 0 {
		t.Fatalf("search for missing keyword = %+v, want empty", res)
	}

	// Delete the file on disk but leave the row; search must os.Stat-guard it out.
	if err := os.Remove(songPath); err != nil {
		t.Fatalf("remove song: %v", err)
	}
	if res := localMusicSearchSongs("hello", 50); len(res) != 0 {
		t.Fatalf("search after file delete = %+v, want empty (os.Stat guard)", res)
	}
	// And the guard should have removed the stale row.
	var count int64
	db.Model(&LocalMusicIndex{}).Count(&count)
	if count != 0 {
		t.Fatalf("stale row not removed by search guard, count = %d", count)
	}
}

func TestSearchRouteIncludesLocalSourceForSongType(t *testing.T) {
	initCollectionDBForTest(t)

	downloadDir := t.TempDir()
	withLocalMusicDownloadDir(t, downloadDir)
	if err := os.WriteFile(filepath.Join(downloadDir, "Local Hit.mp3"), []byte("x"), 0644); err != nil {
		t.Fatalf("write: %v", err)
	}
	if err := syncLocalMusicIndex(); err != nil {
		t.Fatalf("sync: %v", err)
	}

	songs := localMusicSearchSongs("Local Hit", 50)
	if len(songs) != 1 {
		t.Fatalf("index search = %d, want 1", len(songs))
	}

	if !containsLocalSource([]string{"netease", "local"}) {
		t.Fatal("containsLocalSource should detect local")
	}
	if containsLocalSource([]string{"netease", "qq"}) {
		t.Fatal("containsLocalSource should be false without local")
	}
}

func TestLocalCollectionSearchPlaylists(t *testing.T) {
	initCollectionDBForTest(t)

	cols := []Collection{
		{Name: "我的摇滚", Kind: collectionKindManual, ContentType: collectionContentPlaylist, Source: "local"},
		{Name: "爵士精选", Kind: collectionKindManual, ContentType: collectionContentPlaylist, Source: "local"},
	}
	if err := db.Create(&cols).Error; err != nil {
		t.Fatalf("create collections: %v", err)
	}

	got := localCollectionSearchPlaylists("摇滚")
	if len(got) != 1 || got[0].Name != "我的摇滚" || got[0].Source != "local" {
		t.Fatalf("search = %+v, want single 我的摇滚 local playlist", got)
	}

	if res := localCollectionSearchPlaylists("不存在"); len(res) != 0 {
		t.Fatalf("search miss = %+v, want empty", res)
	}
}

func TestLocalPlaylistSupportedInRenderIndex(t *testing.T) {
	if !containsStringValue(core.GetAllSourceNames(), "local") {
		t.Fatal("local missing from all sources")
	}
	// 歌单模式下 local 复选框应可用：renderIndex 显式开启 PlaylistSupported[local]。
	content, err := templateFS.ReadFile("templates/partials/search_box.html")
	if err != nil {
		t.Fatalf("read search_box: %v", err)
	}
	if !strings.Contains(string(content), "data-playlist-supported") {
		t.Fatal("search box missing data-playlist-supported wiring")
	}
}

func TestBatchAddSongsEndpoint(t *testing.T) {
	initCollectionDBForTest(t)

	col := Collection{Name: "Mix", Kind: collectionKindManual, ContentType: collectionContentPlaylist, Source: "local"}
	if err := db.Create(&col).Error; err != nil {
		t.Fatalf("create collection: %v", err)
	}

	router := newLocalMusicTestRouter()

	payload := map[string]any{
		"songs": []map[string]any{
			{"id": "111", "source": "netease", "name": "A"},
			{"id": "222", "source": "qq", "name": "B"},
			{"id": "", "source": "qq", "name": "bad"}, // failed: missing id
		},
	}
	body, _ := json.Marshal(payload)
	req := httptest.NewRequest(http.MethodPost, RoutePrefix+"/collections/"+collectionIDString(col.ID)+"/songs/batch", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("batch add status = %d, body=%s", rec.Code, rec.Body.String())
	}
	var resp struct {
		Added     int `json:"added"`
		Duplicate int `json:"duplicate"`
		Failed    int `json:"failed"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if resp.Added != 2 || resp.Failed != 1 {
		t.Fatalf("resp = %+v, want added=2 failed=1", resp)
	}

	// Re-adding the two valid songs -> duplicates.
	payload2 := map[string]any{"songs": []map[string]any{
		{"id": "111", "source": "netease", "name": "A"},
		{"id": "222", "source": "qq", "name": "B"},
	}}
	body2, _ := json.Marshal(payload2)
	req = httptest.NewRequest(http.MethodPost, RoutePrefix+"/collections/"+collectionIDString(col.ID)+"/songs/batch", bytes.NewReader(body2))
	req.Header.Set("Content-Type", "application/json")
	rec = httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode 2: %v", err)
	}
	if resp.Added != 0 || resp.Duplicate != 2 {
		t.Fatalf("resp 2 = %+v, want added=0 duplicate=2", resp)
	}
}

func TestBatchAddLocalMusicEndpoint(t *testing.T) {
	initCollectionDBForTest(t)

	downloadDir := t.TempDir()
	withLocalMusicDownloadDir(t, downloadDir)

	idA := writeLocalTrackForTest(t, downloadDir, "A.mp3")
	idB := writeLocalTrackForTest(t, downloadDir, "B.mp3")

	col := Collection{Name: "Fav", Kind: collectionKindManual, ContentType: collectionContentPlaylist, Source: "local"}
	if err := db.Create(&col).Error; err != nil {
		t.Fatalf("create collection: %v", err)
	}

	router := newLocalMusicTestRouter()

	body, _ := json.Marshal(map[string][]string{"ids": {idA, idB}})
	req := httptest.NewRequest(http.MethodPost, RoutePrefix+"/collections/"+collectionIDString(col.ID)+"/local_music/batch", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("batch add status = %d, body=%s", rec.Code, rec.Body.String())
	}
	var resp struct {
		Added     int `json:"added"`
		Duplicate int `json:"duplicate"`
		Failed    int `json:"failed"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode resp: %v", err)
	}
	if resp.Added != 2 || resp.Failed != 0 {
		t.Fatalf("first batch resp = %+v, want added=2 failed=0", resp)
	}

	// Re-adding the same ids should be all duplicates.
	req = httptest.NewRequest(http.MethodPost, RoutePrefix+"/collections/"+collectionIDString(col.ID)+"/local_music/batch", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rec = httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode resp 2: %v", err)
	}
	if resp.Added != 0 || resp.Duplicate != 2 {
		t.Fatalf("second batch resp = %+v, want added=0 duplicate=2", resp)
	}
}

func TestLocalRegisteredAsSearchSource(t *testing.T) {
	all := core.GetAllSourceNames()
	if !containsStringValue(all, "local") {
		t.Fatalf("GetAllSourceNames missing local: %v", all)
	}
	if core.GetSourceDescription("local") != "本地音乐" {
		t.Fatalf("GetSourceDescription(local) = %q", core.GetSourceDescription("local"))
	}
	if containsStringValue(core.GetDefaultSourceNames(), "local") {
		t.Fatal("local should be OFF by default")
	}
	if containsStringValue(core.GetPlaylistSourceNames(), "local") {
		t.Fatal("local should not be a playlist source")
	}
	if isSwitchSourceAllowed("local", "netease") {
		t.Fatal("switch-source must never target local")
	}
}

func writeLocalTrackForTest(t *testing.T, dir, name string) string {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, name), []byte("audio"), 0644); err != nil {
		t.Fatalf("write %s: %v", name, err)
	}
	return encodeLocalMusicID(name)
}

func collectionIDString(id uint) string {
	return url.PathEscape(uintToString(id))
}

func uintToString(id uint) string {
	if id == 0 {
		return "0"
	}
	digits := []byte{}
	for id > 0 {
		digits = append([]byte{byte('0' + id%10)}, digits...)
		id /= 10
	}
	return string(digits)
}

func containsStringValue(values []string, target string) bool {
	for _, v := range values {
		if v == target {
			return true
		}
	}
	return false
}
