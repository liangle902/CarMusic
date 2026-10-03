package web

import (
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/guohuiyuan/go-music-dl/core"
	"github.com/guohuiyuan/music-lib/model"
	"gorm.io/gorm/clause"
)

func formatSizeForIndex(size int64) string {
	if size == 0 {
		return "-"
	}
	mb := float64(size) / 1024 / 1024
	return fmt.Sprintf("%.2f MB", mb)
} // LocalMusicIndex 是下载目录的搜索索引行。磁盘文件仍是唯一真相，
// 该表只用于加速对大量本地文件的关键词搜索。主键沿用 base64(相对路径)。
type LocalMusicIndex struct {
	ID        string    `gorm:"column:id;primaryKey"`
	RelPath   string    `gorm:"column:rel_path;uniqueIndex;not null"`
	Name      string    `gorm:"column:name;index"`
	Artist    string    `gorm:"column:artist;index"`
	Album     string    `gorm:"column:album;index"`
	Duration  int       `gorm:"column:duration"`
	Size      int64     `gorm:"column:size"`
	Ext       string    `gorm:"column:ext"`
	Cover     string    `gorm:"column:cover"`
	HasCover  bool      `gorm:"column:has_cover"`
	HasLyric  bool      `gorm:"column:has_lyric"`
	ModTime   time.Time `gorm:"column:mod_time"`
	ScannedAt time.Time `gorm:"column:scanned_at;index"`
}

func (LocalMusicIndex) TableName() string { return "local_music_index" }

func containsLocalSource(sources []string) bool {
	for _, s := range sources {
		if isLocalMusicSource(s) {
			return true
		}
	}
	return false
}

func localMusicIndexExtra(row *LocalMusicIndex) map[string]string {
	extra := map[string]string{
		"local_music": "true",
		"file_id":     row.ID,
		"rel_path":    row.RelPath,
		"ext":         row.Ext,
	}
	if row.HasCover {
		extra["cover"] = "true"
	}
	if row.HasLyric {
		extra["lyric"] = "true"
	}
	return extra
}

func localMusicTrackToIndexRow(track *localMusicTrack, scannedAt time.Time) LocalMusicIndex {
	hasCover := strings.TrimSpace(track.Cover) != ""
	hasLyric := false
	if track.Extra != nil {
		if track.Extra["cover"] == "true" {
			hasCover = true
		}
		hasLyric = track.Extra["lyric"] == "true"
	}
	return LocalMusicIndex{
		ID:        track.ID,
		RelPath:   track.RelPath,
		Name:      track.Name,
		Artist:    track.Artist,
		Album:     track.Album,
		Duration:  track.Duration,
		Size:      track.Size,
		Ext:       track.Ext,
		Cover:     track.Cover,
		HasCover:  hasCover,
		HasLyric:  hasLyric,
		ModTime:   track.modTime,
		ScannedAt: scannedAt,
	}
}

// syncLocalMusicIndex 全量扫描下载目录并把结果 upsert 进索引表，
// 同时清扫掉本轮未出现（文件已消失）的行。
func syncLocalMusicIndex() error {
	if db == nil {
		return nil
	}
	tracks, dir, exists, err := scanLocalMusicTracks()
	if err != nil {
		return err
	}
	if err := syncTracksToIndex(tracks); err != nil {
		return err
	}
	// 磁盘是唯一真相：扫完盘顺手对账下载去重索引，把文件已经不在本地的记录回收掉，
	// 否则用户在 NAS 后台删掉文件后再下载，仍然会被判定为「已下载」而跳过。
	if exists {
		reconcileDownloadDedupWithScan(tracks, dir)
	}
	storeLocalMusicScanSnapshot(localMusicScanSnapshot{
		Dir:       dir,
		Tracks:    cloneLocalMusicTrackSlice(tracks),
		Exists:    exists,
		ScannedAt: time.Now(),
	})
	return nil
}

// syncLocalMusicIndexAsync 在后台跑一次全量同步，不阻塞调用方（启动时用）。
func syncLocalMusicIndexAsync() {
	go func() {
		_ = syncLocalMusicIndex()
	}()
}

// reconcileDownloadDedupWithScan 用一次全量扫盘结果对账下载去重索引。keep 之外的
// 歌说明磁盘上已经没有文件，对应记录会被回收；路径有变化的会被更新。
//
// 只在扫描到至少一个音频文件时才动去重索引：下载目录被整个清空（或挂载点掉了）
// 时宁可保守，避免一次性清光下载去重记录。
func reconcileDownloadDedupWithScan(tracks []*localMusicTrack, outDir string) {
	if len(tracks) == 0 {
		return
	}
	keep := make(map[string]string, len(tracks))
	for _, track := range tracks {
		if track == nil {
			continue
		}
		keep[core.SongKey(&model.Song{Name: track.Name, Artist: track.Artist})] = track.RelPath
	}
	_, _ = core.PruneDownloadDedup(outDir, keep)
}

// resolveLegacyDedupForSong 处理没有记录文件路径的旧去重记录：拿歌名+歌手去本地曲库
// 索引反查，确认真实文件还在不在。查不到就回收记录（这次下载不再被跳过），查到就
// 把路径补回去，后续判定可以直接走文件校验。
func resolveLegacyDedupForSong(index core.DownloadDedupIndex, song *model.Song) {
	if index == nil || song == nil {
		return
	}
	key := core.SongKey(song)
	relPath, exists := index[key]
	if !exists || strings.TrimSpace(relPath) != "" {
		return
	}

	if row, _, err := findLocalMusicMatch(song.Name, song.Artist); err != nil {
		return
	} else if row != nil &&
		strings.EqualFold(strings.TrimSpace(row.Name), strings.TrimSpace(song.Name)) &&
		strings.EqualFold(strings.TrimSpace(row.Artist), strings.TrimSpace(song.Artist)) {
		index[key] = row.RelPath
		_ = core.SaveDownloadDedupEntry(song.Name, song.Artist, row.RelPath)
		return
	}

	tracks, _, dirExists, err, _, _ := scanLocalMusicTracksCached(true)
	if err != nil || !dirExists {
		return
	}

	var match *localMusicTrack
	for _, track := range tracks {
		if track == nil {
			continue
		}
		if strings.EqualFold(strings.TrimSpace(track.Name), strings.TrimSpace(song.Name)) &&
			strings.EqualFold(strings.TrimSpace(track.Artist), strings.TrimSpace(song.Artist)) {
			match = track
			break
		}
	}
	if match == nil {
		_ = core.ForgetDownloadedSong(song.Name, song.Artist, "")
		delete(index, key)
		return
	}

	index[key] = match.RelPath
	_ = core.SaveDownloadDedupEntry(song.Name, song.Artist, match.RelPath)
}

// loadTracksFromIndex 从 SQLite 索引表分页读取本地音乐，不走文件系统 IO。
// 远快于 scanLocalMusicTracks（跳过逐文件读 ID3 标签）。
func loadTracksFromIndex(offset int, limit int) ([]*localMusicTrack, int, bool) {
	if db == nil {
		return nil, 0, false
	}

	var total int64
	if err := db.Model(&LocalMusicIndex{}).Count(&total).Error; err != nil || total == 0 {
		return nil, 0, false
	}

	var rows []LocalMusicIndex
	if limit <= 0 {
		limit = 200
	}
	if err := db.Order("mod_time DESC").Offset(offset).Limit(limit).Find(&rows).Error; err != nil {
		return nil, 0, false
	}

	rootAbs, _ := filepath.Abs(localMusicDownloadDir())
	tracks := make([]*localMusicTrack, 0, len(rows))
	missingIDs := make([]string, 0)
	for i := range rows {
		row := &rows[i]
		// 快速校验文件是否还在磁盘上
		absPath := filepath.Join(rootAbs, filepath.FromSlash(row.RelPath))
		if info, statErr := os.Stat(absPath); statErr != nil || info.IsDir() {
			missingIDs = append(missingIDs, row.ID)
			continue
		}
		cover := row.Cover
		if cover == "" && row.HasCover {
			cover = RoutePrefix + "/local_music/cover?id=" + url.QueryEscape(row.ID)
		}
		tracks = append(tracks, &localMusicTrack{
			ID:       row.ID,
			Source:   localMusicSource,
			Name:     row.Name,
			Artist:   row.Artist,
			Album:    row.Album,
			Cover:    cover,
			Duration: row.Duration,
			Filename: filepath.Base(row.RelPath),
			RelPath:  row.RelPath,
			Ext:      row.Ext,
			Size:     row.Size,
			SizeText: formatSizeForIndex(row.Size),
			Extra:    localMusicIndexExtra(row),
		})
	}
	if len(missingIDs) > 0 {
		if err := db.Where("id IN ?", missingIDs).Delete(&LocalMusicIndex{}).Error; err != nil {
			return nil, 0, false
		}
		total -= int64(len(missingIDs))
	}

	if len(tracks) == 0 {
		return nil, 0, false
	}
	return tracks, int(total), true
}

// syncTracksToIndex writes one completed scan and removes rows that did not
// appear in that scan, including the case where the directory is now empty.
func syncTracksToIndex(tracks []*localMusicTrack) error {
	database := db
	if database == nil {
		return nil
	}
	runStart := time.Now()
	rows := make([]LocalMusicIndex, 0, len(tracks))
	for _, t := range tracks {
		if t == nil {
			continue
		}
		rows = append(rows, localMusicTrackToIndexRow(t, runStart))
	}
	if len(rows) > 0 {
		if err := database.Clauses(clause.OnConflict{
			Columns: []clause.Column{{Name: "id"}},
			DoUpdates: clause.AssignmentColumns([]string{
				"rel_path", "name", "artist", "album", "duration", "size",
				"ext", "cover", "has_cover", "has_lyric", "mod_time", "scanned_at",
			}),
		}).CreateInBatches(rows, 200).Error; err != nil {
			return err
		}
	}
	return database.Where("scanned_at < ?", runStart).Delete(&LocalMusicIndex{}).Error
}

// findLocalMusicMatch finds a real file among plausible index candidates.
// An index can temporarily retain rows for files moved outside the download
// directory, so checking only the first row makes otherwise valid matches fail.
func findLocalMusicMatch(name string, artist string) (*LocalMusicIndex, string, error) {
	if db == nil {
		return nil, "", nil
	}
	rootAbs, err := filepath.Abs(localMusicDownloadDir())
	if err != nil {
		return nil, "", err
	}

	seenIDs := make(map[string]struct{})
	staleIDs := make([]string, 0)
	findExisting := func(rows []LocalMusicIndex) (*LocalMusicIndex, string) {
		for i := range rows {
			row := rows[i]
			if _, seen := seenIDs[row.ID]; seen {
				continue
			}
			seenIDs[row.ID] = struct{}{}

			absPath := filepath.Join(rootAbs, filepath.FromSlash(row.RelPath))
			if info, statErr := os.Stat(absPath); statErr != nil || info.IsDir() {
				staleIDs = append(staleIDs, row.ID)
				continue
			}
			return &row, absPath
		}
		return nil, ""
	}
	lookup := func(query string, args ...interface{}) (*LocalMusicIndex, string, error) {
		var rows []LocalMusicIndex
		if err := db.Where(query, args...).Order("mod_time DESC").Limit(20).Find(&rows).Error; err != nil {
			return nil, "", err
		}
		row, absPath := findExisting(rows)
		return row, absPath, nil
	}
	cleanupStale := func() {
		if len(staleIDs) > 0 {
			_ = db.Where("id IN ?", staleIDs).Delete(&LocalMusicIndex{}).Error
		}
	}

	if artist != "" {
		row, absPath, err := lookup("name = ? AND artist = ?", name, artist)
		if err != nil || row != nil {
			cleanupStale()
			return row, absPath, err
		}
		// A title alone is not enough when the search result has an artist.
		// Keep the fuzzy-title fallback within that same artist to avoid
		// redirecting one artist's song to a different local recording.
		row, absPath, err = lookup("name LIKE ? AND artist = ?", "%"+name+"%", artist)
		cleanupStale()
		return row, absPath, err
	}
	row, absPath, err := lookup("name = ?", name)
	if err != nil || row != nil {
		cleanupStale()
		return row, absPath, err
	}
	row, absPath, err = lookup("name LIKE ?", "%"+name+"%")
	cleanupStale()
	return row, absPath, err
}

// upsertLocalMusicIndexRow 针对单个文件做定向 upsert（上传后用）。
func upsertLocalMusicIndexRow(track *localMusicTrack) {
	if db == nil || track == nil {
		return
	}
	row := localMusicTrackToIndexRow(track, time.Now())
	_ = db.Clauses(clause.OnConflict{
		Columns: []clause.Column{{Name: "id"}},
		DoUpdates: clause.AssignmentColumns([]string{
			"rel_path", "name", "artist", "album", "duration", "size",
			"ext", "cover", "has_cover", "has_lyric", "mod_time", "scanned_at",
		}),
	}).Create(&row).Error
}

// deleteLocalMusicIndexRow 删除单个索引行（删除文件后用）。
func deleteLocalMusicIndexRow(id string) {
	if db == nil || strings.TrimSpace(id) == "" {
		return
	}
	_ = db.Delete(&LocalMusicIndex{}, "id = ?", id).Error
}

// localMusicSearchSongs 在索引表里按关键词搜索本地歌曲。对返回的每行做
// os.Stat 校验，已不在磁盘上的（删除/移动）一律剔除，保证已删除本地音乐
// 不会出现在搜索结果里。
func localMusicSearchSongs(keyword string, limit int) []model.Song {
	keyword = strings.TrimSpace(keyword)
	if keyword == "" || db == nil {
		return nil
	}
	if limit <= 0 {
		limit = 200
	}

	like := "%" + keyword + "%"
	var rows []LocalMusicIndex
	if err := db.Where("name LIKE ? OR artist LIKE ? OR album LIKE ?", like, like, like).
		Order("mod_time DESC").
		Limit(limit).
		Find(&rows).Error; err != nil {
		return nil
	}

	rootAbs, err := filepath.Abs(localMusicDownloadDir())
	if err != nil {
		rootAbs = ""
	}

	songs := make([]model.Song, 0, len(rows))
	for i := range rows {
		row := &rows[i]
		if rootAbs != "" {
			absPath := filepath.Join(rootAbs, filepath.FromSlash(row.RelPath))
			if info, statErr := os.Stat(absPath); statErr != nil || info.IsDir() {
				deleteLocalMusicIndexRow(row.ID)
				continue
			}
		}
		cover := row.Cover
		if cover == "" && row.HasCover {
			cover = RoutePrefix + "/local_music/cover?id=" + url.QueryEscape(row.ID)
		}
		songs = append(songs, model.Song{
			ID:       row.ID,
			Source:   localMusicSource,
			Name:     row.Name,
			Artist:   row.Artist,
			Album:    row.Album,
			Cover:    cover,
			Duration: row.Duration,
			Extra:    localMusicIndexExtra(row),
		})
	}
	return songs
}

// localCollectionSearchPlaylists 在本地歌单（Collection）里按名称/描述/创建者搜索，
// 返回 model.Playlist 卡片（Source=local），用于"歌单搜索 + 勾选 local"。
func localCollectionSearchPlaylists(keyword string) []model.Playlist {
	keyword = strings.TrimSpace(keyword)
	if keyword == "" || db == nil {
		return nil
	}
	like := "%" + keyword + "%"
	var collections []Collection
	if err := db.Where("name LIKE ? OR description LIKE ? OR creator LIKE ?", like, like, like).
		Order("id DESC").
		Find(&collections).Error; err != nil {
		return nil
	}
	playlists := make([]model.Playlist, 0, len(collections))
	for _, collection := range collections {
		playlists = append(playlists, collection.playlistCard())
	}
	return playlists
}
