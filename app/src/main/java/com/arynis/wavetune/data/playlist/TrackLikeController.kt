package com.arynis.wavetune.data.playlist

import com.arynis.wavetune.data.generate.youtubeVideoIdOrNull
import com.arynis.wavetune.data.local.SettingsPreferences
import com.arynis.wavetune.data.ytmusic.YtMusicAuthManager
import com.arynis.wavetune.data.ytmusic.YtMusicLibraryManager
import com.arynis.wavetune.data.ytmusic.YtMusicPreferences
import com.arynis.wavetune.playback.PlayableTrack
import com.arynis.wavetune.playback.toGeneratedTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * The player's heart: a like lands in the local Liked Songs playlist, then is
 * mirrored to YouTube Music. Shared by the in-app player and the playback
 * service, so a heart on an external controller (the OriginOS music card)
 * does exactly what the in-app heart does.
 *
 * Nothing is pushed anywhere else: a like is this app's own and YouTube Music's,
 * and nothing more.
 */
@Singleton
class TrackLikeController @Inject constructor(
    private val likedSongsManager: LikedSongsManager,
    private val ytMusicLibraryManager: YtMusicLibraryManager,
    private val settingsPreferences: SettingsPreferences,
    private val ytAuthManager: YtMusicAuthManager,
    private val ytMusicPreferences: YtMusicPreferences,
) {
    val likedTrackKeys: StateFlow<Set<String>> get() = likedSongsManager.likedTrackKeys

    fun isLiked(track: PlayableTrack, likedKeys: Set<String> = likedTrackKeys.value): Boolean =
        track.toGeneratedTrack().key in likedKeys

    /** Throws when Liked Songs could not be updated; the mirrors never do. */
    suspend fun toggle(track: PlayableTrack): LikeChange {
        val liked = likedSongsManager.toggle(track.toGeneratedTrack())
        return mirror(track, liked)
    }

    /** Idempotent like for artwork double-tap: it never unlikes. */
    suspend fun like(track: PlayableTrack): LikeChange {
        likedSongsManager.like(track.toGeneratedTrack())
        return mirror(track, liked = true)
    }

    private suspend fun mirror(track: PlayableTrack, liked: Boolean): LikeChange {
        return LikeChange(liked = liked, youTubeMirrored = mirrorYouTubeLove(track, liked))
    }

    /**
     * YouTube Music mirror of the local like. The local state is the source of
     * truth and already saved, so a failed mirror is reported and left alone —
     * the next toggle can retry it.
     *
     * With no YouTube account (or while offline) the action is queued and the
     * next sync pass sends it, so a like is never silently dropped.
     */
    private suspend fun mirrorYouTubeLove(track: PlayableTrack, loved: Boolean): Boolean {
        if (!settingsPreferences.likedSongsYouTubeSync.first()) return true
        val generated = track.toGeneratedTrack()
        val videoId = generated.youtubeVideoIdOrNull() ?: return true
        // Without a session the like still belongs to the user's library;
        // remember it so the next sync pass can send it to YouTube instead
        // of losing it.
        if (!ytAuthManager.connection.value.isConnected) {
            runCatching { ytMusicPreferences.enqueueLikeMutation(videoId, loved) }
            return false
        }
        val mirrored = try {
            ytMusicLibraryManager.mirrorLikeToYouTube(generated, loved)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            android.util.Log.w("TrackLikeController", "YouTube like mirror failed", error)
            false
        }
        if (!mirrored) {
            runCatching { ytMusicPreferences.enqueueLikeMutation(videoId, loved) }
        }
        return mirrored
    }
}

data class LikeChange(val liked: Boolean, val youTubeMirrored: Boolean)
