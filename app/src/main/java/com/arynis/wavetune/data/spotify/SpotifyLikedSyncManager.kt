package com.arynis.wavetune.data.spotify

import android.util.Log
import com.arynis.wavetune.data.playlist.ExternalTrackRow
import com.arynis.wavetune.data.playlist.LikedSongsManager
import com.arynis.wavetune.data.playlist.SpotifyPlaylistImporter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps WaveTune's Liked Songs in step with a connected Spotify account.
 *
 * Every so often the account's liked list is read, only the rows not seen
 * before are matched to playable YouTube Music tracks, and those are merged
 * into the built-in Liked Songs playlist. The merge is additive and
 * deduped, so it can never remove local likes or duplicate an existing one.
 *
 * Matching is deliberately incremental: a first pass may search the whole
 * library, but later passes only resolve genuinely new likes.
 */
@Singleton
class SpotifyLikedSyncManager @Inject constructor(
    private val spotifyPreferences: SpotifyPreferences,
    private val spotifyPlaylistImporter: SpotifyPlaylistImporter,
    private val likedSongsManager: LikedSongsManager,
    private val applicationScope: CoroutineScope,
) {
    @Volatile private var started = false
    @Volatile private var running = false

    fun start() {
        if (started) return
        started = true
        applicationScope.launch {
            while (true) {
                runCatching { syncNow() }
                    .onFailure { error ->
                        if (error !is CancellationException) {
                            Log.w(TAG, "Spotify liked songs auto-sync failed", error)
                        }
                    }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * One auto-sync pass. Returns false when skipped (not connected / auto
     * sync off / already running / too soon since the last pass).
     *
     * @param force run now regardless of the last-run timestamp; used when the
     *              user turns the toggle on.
     */
    suspend fun syncNow(force: Boolean = false): Boolean {
        if (running) return false
        if (!spotifyPreferences.likedSongsAutoSync.first()) return false
        if (!spotifyPreferences.isConnected.first()) return false

        val now = System.currentTimeMillis()
        val last = spotifyPreferences.lastLikedSongsSyncAt.first()
        if (!force && last > 0L && now - last < MIN_INTERVAL_MS) return false

        running = true
        try {
            val rows = spotifyPlaylistImporter.fetchLikedSongs().rows
            if (rows.isEmpty()) {
                spotifyPreferences.setLastLikedSongsSyncAt(now)
                return true
            }

            val known = spotifyPreferences.syncedLikedSongKeys.first()
            val newRows = rows.filter { rowKey(it) !in known }
            if (newRows.isNotEmpty()) {
                val matched = spotifyPlaylistImporter.matchLikedRows(newRows)
                if (matched.tracks.isNotEmpty()) {
                    likedSongsManager.mergeTracks(matched.tracks)
                    val skipped = (matched.totalRows - matched.matchedCount).coerceAtLeast(0)
                    Log.i(TAG, "Imported ${matched.matchedCount} new Spotify likes ($skipped unmatched)")
                }
            }
            // Record every row, matched or not: a row that cannot be matched
            // once (regional gap, removed upload) will not suddenly match
            // later, and re-searching it every pass would be pure waste.
            spotifyPreferences.setSyncedLikedSongKeys(rows.mapTo(mutableSetOf(), ::rowKey))
            spotifyPreferences.setLastLikedSongsSyncAt(now)
            return true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Log.w(TAG, "Spotify liked songs auto-sync pass failed", error)
            return false
        } finally {
            running = false
        }
    }

    private fun rowKey(row: ExternalTrackRow): String =
        "${row.title.trim().lowercase()}|${row.artist.trim().lowercase()}"

    private companion object {
        const val TAG = "SpotifyLikedSync"
        /** How often the app checks for new likes while running. */
        const val CHECK_INTERVAL_MS = 30 * 60_000L
        /** Minimum time between full library reads. */
        const val MIN_INTERVAL_MS = 6 * 60 * 60_000L
    }
}
