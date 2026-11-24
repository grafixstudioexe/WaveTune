package com.arynis.wavetune.ui.shared

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arynis.wavetune.shared.library.AndroidMediaLibrary
import com.arynis.wavetune.shared.settings.AndroidSettingsStore
import com.arynis.wavetune.shared.ui.WaveTuneApp
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The shared Compose Multiplatform UI — the same [WaveTuneApp] the desktop build
 * runs — hosted on Android, wired to this device's real MediaStore library, the
 * app's real Media3 playback engine, the Room download/playlists tables and the
 * InnerTube home feed.
 */
@AndroidEntryPoint
class SharedShellActivity : ComponentActivity() {

    @Inject
    lateinit var playerController: Media3PlayerController

    @Inject
    lateinit var lyricsProvider: AndroidLyricsProvider

    @Inject
    lateinit var downloadsRepository: AndroidDownloadsRepository

    @Inject
    lateinit var playlistsRepository: AndroidPlaylistsRepository

    @Inject
    lateinit var homeFeedRepository: AndroidHomeFeedRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { enableEdgeToEdge() }

        val library = AndroidMediaLibrary(applicationContext)
        val settingsStore = AndroidSettingsStore(this)
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        setContent {
            var permissionGeneration by remember { mutableIntStateOf(0) }
            val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { permissionGeneration++ }

            LaunchedEffect(Unit) {
                if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    launcher.launch(permission)
                }
                playerController.setVolume(settingsStore.settings.value.volume)
            }
            LaunchedEffect(permissionGeneration) {
                library.refresh()
            }

            WaveTuneApp(
                settingsStore = settingsStore,
                library = library,
                player = playerController,
                platform = AndroidMediaLibrary.platformActions,
                lyrics = lyricsProvider,
                downloads = downloadsRepository,
                playlists = playlistsRepository,
                homeFeed = homeFeedRepository,
            )
        }
    }
}
