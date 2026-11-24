package com.arynis.wavetune.util

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.arynis.wavetune.shared.jam.JamDefaults
import com.arynis.wavetune.shared.share.WaveTuneCode

/**
 * Cross-device track links.
 *
 * A shared track always points at a web share page, never at a raw custom
 * scheme URL: the page opens the app on the receiving device and still shows
 * the track (plus a YouTube Music fallback) when the app is not installed.
 * The query keys match the desktop share surface, so a link created on any
 * platform is identical.
 *
 * TODO(ARYNIS): the share page currently lives on the upstream host. Deploy
 * the same static page on the WaveTune domain and change [SITE].
 */
object ShareLinks {

    // TODO(ARYNIS): point this at the WaveTune share page once it is hosted.
    const val SITE = "https://wavetune.pages.dev/share.html"

    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{6,24}$")

    /** Page link for [videoId], or null when the track has no stable identity. */
    fun trackUrl(
        videoId: String?,
        title: String,
        artist: String,
        album: String? = null,
        artworkUrl: String? = null,
    ): String? {
        val id = videoId?.trim().orEmpty()
        if (!videoIdPattern.matches(id)) return null
        val builder = Uri.parse(SITE).buildUpon().appendQueryParameter("v", id)
        title.trim().takeIf { it.isNotEmpty() }?.let { builder.appendQueryParameter("title", it) }
        artist.trim().takeIf { it.isNotEmpty() }?.let { builder.appendQueryParameter("artist", it) }
        album?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.appendQueryParameter("album", it) }
        // artworkUrl is intentionally omitted to keep share links short, clean and fast.
        // Both the app and web landing page automatically derive artwork from videoId.
        return builder.build().toString()
    }

    /** Reversible human code for a track (see `WaveTuneCode`). */
    fun trackCode(videoId: String?): String? = WaveTuneCode.encode(videoId)

    /**
     * Scannable code destination. The code remains explicit in the URL; `v`
     * is included as a fallback for older share pages and app builds.
     */
    fun trackCodeUrl(
        code: String?,
        title: String,
        artist: String,
        album: String? = null,
    ): String? {
        val normalized = WaveTuneCode.normalize(code) ?: return null
        val videoId = WaveTuneCode.decode(normalized) ?: return null
        val builder = Uri.parse(SITE).buildUpon()
            .appendQueryParameter("code", normalized)
            .appendQueryParameter("v", videoId)
        title.trim().takeIf { it.isNotEmpty() }?.let { builder.appendQueryParameter("title", it) }
        artist.trim().takeIf { it.isNotEmpty() }?.let { builder.appendQueryParameter("artist", it) }
        album?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.appendQueryParameter("album", it) }
        return builder.build().toString()
    }

    /** Canonical invite URL used by Jam copy, share and QR actions. */
    fun jamUrl(roomCode: String): String? = JamDefaults.inviteUrl(roomCode)

    /** Share-card image URL for a code, rendered by the Jam host. */
    fun codeImageUrl(code: String): String =
        "https://jam.wavetune.app/code/${code.trim().uppercase()}.png"

    /** Link plus the WaveTune code, so the receiver can use either surface. */
    fun trackMessage(url: String, videoId: String?): String {
        val code = trackCode(videoId)
        return if (code == null) url else "$url\n\nWaveTune code: $code"
    }

    /** Opens the system share sheet with [url] as plain text. */
    fun send(context: Context, url: String, subject: String? = null) {
        val shareTitle = subject?.trim().takeUnless { it.isNullOrEmpty() } ?: "WaveTune track"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_TITLE, shareTitle)
            putExtra(Intent.EXTRA_SUBJECT, shareTitle)
            // ClipData gives Android 13+ sharesheets and desktop bridges a
            // stable preview payload without changing the plain-text fallback.
            clipData = ClipData.newPlainText(shareTitle, url)
        }
        val chooser = Intent.createChooser(intent, shareTitle)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
