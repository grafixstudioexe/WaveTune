package com.arynis.wavetune.data.ytmusic

import android.util.Log
import com.arynis.wavetune.data.generate.distinctSongs
import com.arynis.wavetune.data.generate.youtubeVideoIdOrNull
import com.arynis.wavetune.data.generate.isLocalFile
import com.arynis.wavetune.data.local.SettingsPreferences
import com.arynis.wavetune.data.music.StreamApi
import com.arynis.wavetune.data.music.YouTubeMusicTrack
import com.arynis.wavetune.data.music.YtOwnedPlaylist
import com.arynis.wavetune.data.playlist.PlaylistImportManager
import com.arynis.wavetune.data.playlist.PlaylistRepository
import com.arynis.wavetune.data.playlist.SavedPlaylist
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface YtSyncState {
    /** Connected + enabled, waiting for the next change/interval. */
    data object Idle : YtSyncState

    data class Running(val current: Int, val total: Int, val label: String) : YtSyncState

    data class Completed(
        val atMillis: Long,
        val syncedPlaylists: Int,
        val failedPlaylists: Int,
        val unmatchedTracks: Int,
    ) : YtSyncState

    data class Failed(val message: String) : YtSyncState
}

/**
 * Keeps every WaveTune playlist mirrored to the user's YouTube Music account
 * ("sync here → there, 24/7") — plus a two-way Liked Songs link:
 *   - likes made in WaveTune are marked as likes on YouTube Music, and
 *   - the account's Liked Music is pulled into WaveTune's Liked Songs so the
 *     heart shows what the account already has (full library on first sign-in,
 *     then incremental top-of-list passes).
 *
 * Other account playlists are imported selectively — the user picks which
 * ones become live library entries — but everything saved in WaveTune is
 * pushed up automatically.
 *
 * Reconcile model (idempotent full-diff per playlist — safe to run any number
 * of times, on any trigger):
 *   1. Ensure a remote counterpart exists (mapping table in DataStore;
 *      created PRIVATE so nothing goes public without consent).
 *   2. Match each local track to a videoId via InnerTube search (cached).
 *   3. Read back the owned playlist with setVideoIds.
 *   4. Remove entries no longer desired; append missing ones (batched ≤50).
 *   5. Rename the remote when the local title changed.
 * Local deletions propagate too: orphaned mappings delete their remote.
 *
 * Triggers: app start, every playlist mutation (debounced), and a periodic
 * timer — single-flight mutex keeps overlapping runs harmless.
 */
@Singleton
class YtMusicSyncManager @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val innerTube: StreamApi,
    private val ytAuth: YtMusicAuthManager,
    private val preferences: YtMusicPreferences,
    private val libraryManager: YtMusicLibraryManager,
    private val importManager: PlaylistImportManager,
    private val settingsPreferences: SettingsPreferences,
    private val applicationScope: CoroutineScope,
) {
    private val _state = MutableStateFlow<YtSyncState>(YtSyncState.Idle)
    val state: StateFlow<YtSyncState> = _state.asStateFlow()

    private val negativeMatchCache = ConcurrentHashMap<String, Long>()
    private val likedSyncMutex = Mutex()
    @Volatile private var started = false
    @Volatile private var sessionExpired = false
    @Volatile private var likedSyncInFlight = false

    fun start() {
        if (started) return
        started = true

        // Every playlist mutation eventually mirrors up. Debounced so bulk
        // operations (imports, restores) produce ONE sync pass, not hundreds.
        applicationScope.launch {
            playlistRepository.changes
                .debounce(DEBOUNCE_MS)
                .collect { runCatching { syncNow("change") } }
        }

        applicationScope.launch {
            delay(INITIAL_DELAY_MS)
            runCatching { syncNow("startup") }
        }

        // Standing heartbeat — catches anything missed while offline and
        // repairs drift made directly in YT Music's own apps.
        applicationScope.launch {
            while (true) {
                delay(PERIODIC_INTERVAL_MS)
                runCatching { syncNow("periodic") }
            }
        }
    }

    /**
     * One full reconcile pass. Returns false when skipped (not connected /
     * sync disabled / already running) or when it failed outright.
     */
    suspend fun syncNow(reason: String = "manual"): Boolean = preferences.playlistSyncMutex.withLock {
        val conn = ytAuth.connection.value
        if (!conn.isConnected) {
            sessionExpired = false
            _state.value = YtSyncState.Idle
            return false
        }
        if (!preferences.isSyncActive()) {
            // "Two-way Playlist Sync" only covers playlist mirrors. Liked
            // Songs has its own switch, so a signed-in account still imports
            // likes and drains queued like/unlike actions while playlists
            // stay untouched.
            maybePullLikedSongs(reason)
            _state.value = YtSyncState.Idle
            return false
        }

        // Proactively refresh credentials from system CookieManager if available or expired
        if (sessionExpired) {
            val refreshed = ytAuth.refreshCookiesFromCookieManager()
            if (!refreshed) {
                _state.value = YtSyncState.Failed("YouTube Music session expired. Please sign in again.")
                return false
            }
            sessionExpired = false
        } else {
            ytAuth.refreshCookiesFromCookieManager()
        }

        // Liked Songs sync runs beside the playlist pass instead of holding
        // its mutex: a first full library read can page through thousands of
        // songs, and playlist edits must still mirror while it does.
        maybePullLikedSongs(reason)

        try {
            // "Local songs" holds files from this device only; never mirrored.
            val allPlaylists = playlistRepository.getAll()
                .filterNot { it.remotePlaylistId != null }
                .filterNot { it.mode == com.arynis.wavetune.data.playlist.LOCAL_SONGS_MODE }
                // The account's "Liked Music" is where this app's likes already go: the
                // direct like marks the song there, and the dedicated liked pull below
                // imports it. Mirroring the local list as well would leave the account
                // with two copies of the same likes, and would rewrite every one of
                // them on every pass.
                .filterNot { it.mode == com.arynis.wavetune.data.playlist.LIKED_SONGS_MODE }
            val syncedIds = preferences.syncedPlaylistIds.first()
            val playlists = if (syncedIds != null) allPlaylists.filter { it.id in syncedIds } else allPlaylists

            if (playlists.isEmpty()) {
                _state.value = YtSyncState.Completed(System.currentTimeMillis(), 0, 0, 0)
                preferences.setLastSyncAt(System.currentTimeMillis())
                libraryManager.refresh()
                return true
            }

            val mappings = preferences.mappings().toMutableMap()
            var unmatchedTotal = 0
            var failed = 0

            // Deletion propagation: mappings whose local playlist vanished
            // mean "deleted in WaveTune" — delete the remote mirror too.
            val liveIds = allPlaylists.mapTo(mutableSetOf()) { it.id }
            val orphans = mappings.keys.filterNot { it in liveIds }
            var removedAnyMapping = false
            for (orphanId in orphans) {
                val removedMapping = mappings.remove(orphanId)
                val remoteId = removedMapping?.remotePlaylistId
                removedAnyMapping = true
                if (remoteId != null && removedMapping?.deleteRemoteWithLocal == true) {
                    runCatching { innerTube.deleteRemotePlaylist(remoteId) }
                        .onFailure { Log.w(TAG, "Orphan remote delete failed ($remoteId)", it) }
                }
            }
            // One persistence pass for ALL orphan removals instead of one
            // serialized full-map write per deleted playlist.
            if (removedAnyMapping) preferences.setMappings(mappings)

            var authFailure = false
            playlists.forEachIndexed { index, playlist ->
                if (authFailure) return@forEachIndexed
                _state.value = YtSyncState.Running(index + 1, playlists.size, playlist.title)
                try {
                    unmatchedTotal += reconcile(playlist, mappings)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                    Log.w(TAG, "YT sync failed for \"${playlist.title}\"", e)
                    val isAuthError = (e is StreamApi.InnerTubeHttpException && (e.responseCode == 401 || e.responseCode == 403)) ||
                        (e.cause is StreamApi.InnerTubeHttpException && ((e.cause as StreamApi.InnerTubeHttpException).responseCode == 401 || (e.cause as StreamApi.InnerTubeHttpException).responseCode == 403))
                    if (isAuthError) {
                        authFailure = true
                    }
                }
                delay(WRITE_PACE_MS)
            }

            if (authFailure) {
                val recovered = ytAuth.refreshCookiesFromCookieManager()
                if (!recovered) {
                    sessionExpired = true
                    _state.value = YtSyncState.Failed("YouTube Music session expired. Please sign in again.")
                    return false
                }
            }

            val now = System.currentTimeMillis()
            preferences.setLastSyncAt(now)
            _state.value = YtSyncState.Completed(now, playlists.size - failed, failed, unmatchedTotal)
            libraryManager.refresh()
            Log.d(TAG, "YT sync ($reason): ${playlists.size - failed}/${playlists.size} ok, $unmatchedTotal unmatched")
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "YT sync pass failed", e)
            _state.value = YtSyncState.Failed(e.message ?: "Sync failed")
            return false
        }
    }

    /**
     * Kicks a Liked Songs pull onto the application scope so the playlist
     * pass is never blocked by a first-time library read. Single-flight:
     * the 60-second heartbeat cannot stack overlapping pulls.
     */
    private fun maybePullLikedSongs(reason: String) {
        if (likedSyncInFlight) return
        likedSyncInFlight = true
        applicationScope.launch {
            try {
                pullLikedSongs(reason)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.w(TAG, "Liked Songs pull failed", error)
            } finally {
                likedSyncInFlight = false
            }
        }
    }

    /**
     * Pulls the connected account's Liked Music into WaveTune's Liked Songs.
     *
     * The first pass (and one per day afterwards) reads the whole list so a
     * fresh sign-in brings the complete library. Later passes only read the
     * newest page, which is enough to pick up likes made in YouTube Music's
     * own apps. Pages are merged as they load, so a large library appears
     * progressively instead of after the whole read.
     */
    private suspend fun pullLikedSongs(reason: String): Boolean = likedSyncMutex.withLock {
        if (!settingsPreferences.likedSongsYouTubeSync.first()) return@withLock false
        if (!ytAuth.connection.value.isConnected) return@withLock false

        // Send queued offline likes before reading the account's list back, so
        // the pull below already sees them on YouTube. This also runs on the
        // throttled path: a heartbeat with nothing to pull still drains the
        // queue instead of leaving it until the next full read.
        flushPendingLikes()

        val now = System.currentTimeMillis()
        val lastPull = preferences.likedSongsLastPullAt.first()
        val lastFullPull = preferences.likedSongsLastFullPullAt.first()
        val needsFull = lastFullPull == 0L || now - lastFullPull >= LIKED_FULL_PULL_INTERVAL_MS

        if (!needsFull && now - lastPull < LIKED_PULL_INTERVAL_MS) {
            return@withLock false
        }

        val mergedVideoIds = mutableSetOf<String>()
        val result = coroutineScope {
            val pages = Channel<List<YouTubeMusicTrack>>(Channel.UNLIMITED)
            val mergeJob = launch(Dispatchers.IO) {
                for (page in pages) {
                    runCatching { importManager.mergeYtLikedTracks(page) }
                        .onFailure { Log.w(TAG, "Liked Songs page merge failed", it) }
                }
            }
            val fetched = try {
                innerTube.fetchPlaylist(
                    playlistIdOrUrl = "LM",
                    maxTracks = if (needsFull) null else LIKED_INCREMENTAL_LIMIT,
                    progressive = needsFull,
                ) { page ->
                    // Pages are cumulative; hand the merge job only songs it
                    // has not seen yet so a large library stays O(n).
                    val fresh = page.filter { mergedVideoIds.add(it.videoId) }
                    if (fresh.isNotEmpty()) pages.trySend(fresh)
                }
            } finally {
                pages.close()
            }
            mergeJob.join()
            fetched
        } ?: run {
            Log.w(TAG, "Liked Music unavailable; retrying next pass")
            return@withLock false
        }

        // Defensive: a single-page response may not have gone through the
        // page callback on every code path.
        val remaining = result.tracks.filter { mergedVideoIds.add(it.videoId) }
        if (remaining.isNotEmpty()) {
            runCatching { importManager.mergeYtLikedTracks(remaining) }
                .onFailure { Log.w(TAG, "Liked Songs final merge failed", it) }
        }

        preferences.setLikedSongsLastPullAt(now)
        if (needsFull && result.isComplete) preferences.setLikedSongsLastFullPullAt(now)
        if (mergedVideoIds.isNotEmpty()) {
            Log.d(TAG, "Liked Music pull ($reason): ${mergedVideoIds.size} tracks (full=$needsFull)")
        }
        true
    }

    /**
     * Sends likes/unlikes that were queued while the account was unreachable.
     * Stops on the first failure and leaves the rest queued for the next pass,
     * so a flaky connection cannot burn through the whole backlog.
     */
    private suspend fun flushPendingLikes() {
        val pending = runCatching { preferences.currentPendingLikeMutations() }
            .getOrNull().orEmpty()
        if (pending.isEmpty()) return
        var sent = 0
        for ((videoId, liked) in pending) {
            if (sent >= LIKED_MIRROR_BATCH) break
            if (!ytAuth.connection.value.isConnected) break
            val mirrored = runCatching {
                if (liked) innerTube.likeVideo(videoId) else innerTube.removeVideoLike(videoId)
            }.getOrDefault(false)
            if (!mirrored) break
            runCatching { preferences.removeLikeMutation(videoId) }
            sent++
            delay(LIKED_MIRROR_PACE_MS)
        }
    }

    /** Reconciles one playlist against its remote mirror; returns unmatched track count. */
    private suspend fun reconcile(playlist: SavedPlaylist, allMappings: MutableMap<Long, YtPlaylistMapping>): Int {
        var mapping = allMappings[playlist.id]
        var remoteId = mapping?.remotePlaylistId

        var remote: YtOwnedPlaylist? = null
        if (remoteId != null) {
            try {
                remote = innerTube.fetchOwnedPlaylist(remoteId)
            } catch (e: StreamApi.InnerTubeHttpException) {
                if (e.responseCode == 404) {
                    Log.w(TAG, "Linked remote playlist $remoteId for \"${playlist.title}\" was deleted on YouTube; recreating mirror")
                    remoteId = null
                    mapping = null
                    allMappings.remove(playlist.id)
                    preferences.setMappings(allMappings)
                } else {
                    throw e
                }
            }
            if (remoteId != null && remote == null) {
                // fetchOwnedPlaylist() answers null for "deleted", "private",
                // AND "network/rate-limit failure" alike — it swallows the
                // HTTP exception internally. Recreating on that null (the old
                // fetchPlaylist() double-check returned null on a flaky
                // connection too) orphaned the user's real remote playlist
                // and stacked a duplicate mirror, once per flaky pass. An
                // unreadable remote is not a deleted one: skip this pass and
                // retry when the read succeeds.
                throw IllegalStateException("Could not read linked YouTube Music playlist: $remoteId; retrying next pass")
            }
        }
        var mutatedRemote = false
        if (remoteId == null) {
            remoteId = innerTube.createRemotePlaylist(playlist.title)
                ?: throw IllegalStateException("Could not create YouTube Music playlist")
            mapping = YtPlaylistMapping(remoteId, playlist.title)
            allMappings[playlist.id] = mapping
            preferences.setMappings(allMappings)
            mutatedRemote = true
        } else if (remoteId != null && mapping?.remoteTitle != playlist.title && playlist.title.isNotBlank()) {
            innerTube.renameRemotePlaylist(remoteId, playlist.title)
            mapping = (mapping ?: YtPlaylistMapping(remoteId, "")).copy(remoteTitle = playlist.title)
            allMappings[playlist.id] = mapping
            preferences.setMappings(allMappings)
        }
        remoteId ?: throw IllegalStateException("Unreachable")

        // Resolve every local track to a videoId (order-preserving).
        // Bounded parallel matching: a 200-track playlist used to chain up to
        // 200 sequential InnerTube searches (~2.5s cap each) while holding the
        // single sync mutex; six-way parallelism cuts that ~6x with identical
        // results.
        if (negativeMatchCache.size > MAX_NEGATIVE_CACHE_ENTRIES) {
            val t = System.currentTimeMillis()
            negativeMatchCache.entries.removeIf { it.value < t }
        }
        val now = System.currentTimeMillis()
        val matchLimiter = Semaphore(MATCH_CONCURRENCY)
        val resolvedVideoIds = coroutineScope {
            playlist.tracks.map { track ->
                async {
                    track.youtubeVideoIdOrNull()?.let { return@async it }
                    // Imported files stay local-only (no fuzzy YouTube match).
                    if (track.isLocalFile()) return@async null
                    matchLimiter.withPermit {
                        val cacheKey = track.key
                        val negativeUntil = negativeMatchCache[cacheKey] ?: 0L
                        if (now < negativeUntil) {
                            null
                        } else {
                            innerTube.findBestMatchOrNull(track.name, track.artist)?.videoId.also { resolved ->
                                if (resolved == null) negativeMatchCache[cacheKey] = now + NEGATIVE_MATCH_TTL_MS
                                else negativeMatchCache.remove(cacheKey)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        val desiredVideoIds = mutableListOf<String>()
        var unmatched = 0
        resolvedVideoIds.forEachIndexed { index, videoId ->
            if (videoId != null) desiredVideoIds += videoId
            else if (!playlist.tracks[index].isLocalFile()) unmatched++
        }
        // Push guard: only tracks carrying an explicit YouTube identity
        // (watch URL / bare videoId on the track itself) may be uploaded to
        // the user's YouTube playlist. Fuzzy search guesses stay local-only:
        // pushing a guess is how songs the user never added end up in their
        // YouTube playlist. Unresolvable tracks already count as unmatched;
        // fuzzy-but-unpushable ids are counted too so the report stays honest.
        val exactVideoIds = playlist.tracks.mapNotNull { it.youtubeVideoIdOrNull() }.toSet()

        // Fresh read-back only when we mutated the remote this pass (create /
        // rename); otherwise the top verification fetch is already current.
        val currentRemote = if (mutatedRemote) {
            innerTube.fetchOwnedPlaylist(remoteId)
        } else {
            remote
        } ?: innerTube.fetchOwnedPlaylist(remoteId)
        ?: throw IllegalStateException("Could not read complete YouTube Music playlist")
        if (!currentRemote.complete) {
            // A truncated remote view (a continuation page failed) makes present
            // tracks look "removed remotely" and they would be deleted. Abort this
            // pass and retry next time when a complete read succeeds.
            throw IllegalStateException("YouTube Music playlist read was incomplete; retrying")
        }
        val remoteItems = currentRemote.items
        val remoteVideoIds = remoteItems.map { it.videoId }
        val baseline = mapping?.lastSyncedVideoIds.orEmpty().toSet()
        val localSet = desiredVideoIds.toSet()
        val remoteSet = remoteVideoIds.toSet()

        // Three-way merge against the previous successful baseline. Additions
        // from either app flow to the other; a removal on either side remains
        // a removal instead of being resurrected by the unchanged copy.
        val removedLocally = baseline - localSet
        val removedRemotely = baseline - remoteSet
        val finalSet = if (baseline.isEmpty()) {
            // A newly linked mirror has no shared history yet; WaveTune is
            // authoritative for that first pass so stale remote entries do
            // not get imported as random local songs.
            localSet
        } else {
            (baseline - removedLocally - removedRemotely) +
                (localSet - baseline) + (remoteSet - baseline)
        }
        // Distinct: a duplicated videoId locally must collapse to one entry —
        // otherwise the duplicate is pushed to YouTube and amplified remote.
        val finalVideoIds = buildList {
            desiredVideoIds.filterTo(this) { it in finalSet }
            remoteVideoIds.filterTo(this) { it in finalSet && it !in this }
        }.distinct()

        val toRemove = remoteItems
            .filter { it.videoId !in finalSet }
            .map { item ->
                checkNotNull(item.setVideoId) { "Missing YouTube Music removal token" } to item.videoId
            }
        val toAdd = finalVideoIds.filter { it !in remoteSet && it in exactVideoIds }
        unmatched += finalVideoIds.count { it !in remoteSet && it !in exactVideoIds }
        // Only ids that are (or will be, after this pass) actually on the remote
        // may enter the baseline. Fuzzy/unpushable ids stay out, otherwise they
        // are remembered as "synced" while absent from the remote and get read
        // as remote removals on the next pass — deleting the local original.
        val recordedBaseline = run {
            val onRemote = remoteSet + toAdd
            finalVideoIds.filter { it in onRemote }
        }

        if (toRemove.isNotEmpty()) {
            check(innerTube.removeVideosFromRemotePlaylist(remoteId, toRemove)) {
                "YouTube Music rejected playlist removals"
            }
        }
        if (toAdd.isNotEmpty()) {
            check(innerTube.addVideosToRemotePlaylist(remoteId, toAdd)) {
                "YouTube Music rejected playlist additions"
            }
        }

        // Pull account-side additions/removals into the local copy. Unmatched
        // local tracks are preserved because they have no reliable video ID.
        if (finalVideoIds != desiredVideoIds) {
            val remoteMetadata = checkNotNull(innerTube.fetchPlaylist(remoteId)) {
                "Could not read YouTube Music track metadata"
            }.tracks
                .associateBy { it.videoId }
            val localByVideoId = resolvedVideoIds.mapIndexedNotNull { index, videoId ->
                videoId?.let { it to playlist.tracks[index] }
            }.toMap()
            // Keys of songs already represented with a KEPT resolved videoId. A
            // videoId-less local copy with the same key is the classic
            // heart-like (Qobuz/local, url="") vs YT-copy duplicate — drop it
            // here so the merge heals instead of preserving both copies.
            // (Only kept videoIds count: a removed copy must not swallow the
            // surviving videoId-less original.)
            val resolvedKeys = localByVideoId
                .filterKeys { it in finalSet }
                .values.mapTo(mutableSetOf()) { it.key }
            val mergedTracks = buildList {
                val seenVideoIds = mutableSetOf<String>()
                playlist.tracks.forEachIndexed { index, track ->
                    val videoId = resolvedVideoIds[index]
                    if (videoId == null) {
                        // An imported file is never a duplicate of its YouTube copy.
                        if (track.isLocalFile() || track.key !in resolvedKeys) add(track)
                    } else if (videoId in finalSet && seenVideoIds.add(videoId)) {
                        // The merge resolved this row's id: stamp it so the
                        // persisted copy plays directly instead of searching.
                        add(
                            if (track.youtubeVideoIdOrNull() == null) {
                                track.copy(url = "https://music.youtube.com/watch?v=$videoId")
                            } else {
                                track
                            },
                        )
                    }
                }
                val represented = resolvedVideoIds.filterNotNull().toSet()
                finalVideoIds.filterNot { it in represented }.forEach { videoId ->
                    val track = localByVideoId[videoId]
                        ?: remoteMetadata[videoId]?.toGeneratedTrack()
                    add(checkNotNull(track) { "Missing YouTube Music track metadata" })
                }
            }.distinctSongs()
            checkNotNull(playlistRepository.replaceTracksForSync(playlist.id, mergedTracks, playlist.tracks)) {
                "Playlist changed during sync; retry on the next pass"
            }
        }

        allMappings[playlist.id] = (allMappings[playlist.id] ?: YtPlaylistMapping(remoteId, playlist.title))
            .copy(
                remoteTitle = playlist.title,
                lastSyncAtMillis = now,
                lastSyncedVideoIds = recordedBaseline,
            )
        preferences.setMappings(allMappings)
        return unmatched
    }

    private companion object {
        const val TAG = "YtMusicSyncManager"
        const val DEBOUNCE_MS = 750L
        const val INITIAL_DELAY_MS = 2_000L
        const val PERIODIC_INTERVAL_MS = 60_000L
        const val WRITE_PACE_MS = 350L
        const val NEGATIVE_MATCH_TTL_MS = 6 * 60 * 60_000L
        const val MATCH_CONCURRENCY = 6
        const val MAX_NEGATIVE_CACHE_ENTRIES = 2048

        /** How often the newest Liked Music page is checked. */
        const val LIKED_PULL_INTERVAL_MS = 15 * 60_000L
        /** How often the whole liked library is re-read (also after sign-in). */
        const val LIKED_FULL_PULL_INTERVAL_MS = 24 * 60 * 60_000L
        const val LIKED_INCREMENTAL_LIMIT = 200
        const val LIKED_MIRROR_BATCH = 50
        const val LIKED_MIRROR_PACE_MS = 150L
    }
}
