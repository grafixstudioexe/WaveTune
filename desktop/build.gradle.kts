import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.materialIconsExtended)
    // Coil 3 is used by the shared Artwork composable; the desktop app owns
    // its image loader setup, so it needs the dependency on its own classpath.
    implementation(libs.coil.compose3)
    implementation(libs.coil.network.okhttp3)
    implementation(libs.kotlinx.coroutines.swing)
    // LRCLIB responses are parsed with kotlinx.serialization; `:shared` keeps
    // its serialization dependency internal, so the desktop module declares it.
    implementation(libs.kotlinx.serialization.json)
    // Jam room socket (same OkHttp protocol Android uses).
    implementation(libs.okhttp.core)
    // Real local-file decoding for the desktop player: MP3 (mp3spi/jlayer)
    // and Ogg Vorbis (vorbisspi) SPI providers on top of javax.sound.sampled.
    implementation(libs.mp3spi)
    implementation(libs.vorbisspi)
    // Real tag reading (title/artist/album/track/duration) for local files.
    implementation(libs.jaudiotagger)
    // libmpv binding for streaming playback (Opus/AAC/HLS), SimpMusic-style.
    implementation(libs.jna)
    // SimpMusic's desktop streaming method: NewPipe-fork extractors that
    // resolve real googlevideo URLs (signature + n-challenge decoding).
    implementation(libs.pipepipe.extractor)
    implementation(libs.brave.extractor)
    // QR generation for the Android → desktop YouTube session pairing.
    implementation(libs.zxing.core)
    // OS-level media keys (play/pause, next, previous) through a native hook.
    implementation(libs.jnativehook)

    configurations.all {
        // PipePipe bundles protobuf-java (full) while BravePipe brings
        // protobuf-javalite into the same com.google.protobuf namespace;
        // dropping the full variant avoids duplicate classes at runtime.
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.arynis.wavetune.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )
            packageName = "WaveTune"
            packageVersion = "1.0.0"
            description = "WaveTune - local music and YouTube playback for desktop"
            vendor = "ARYNIS Arc"
            copyright = "(c) 2026 ARYNIS Arc - GPL-3.0"

            // The trimmed jlink runtime omits these by default. HTTP clients
            // power lyrics/providers/Last.fm, the embedded callback server
            // authorizes Last.fm, EC crypto is needed for HTTPS handshakes,
            // java.util.prefs stores Desktop settings, and java.xml parses
            // provider responses.
            modules(
                "java.net.http",
                "jdk.httpserver",
                "jdk.crypto.ec",
                "java.prefs",
                "java.xml",
            )

            macOS {
                bundleID = "com.arynis.wavetune"
                dockName = "WaveTune"
            }
            windows {
                menuGroup = "WaveTune"
                // Separate identity from WaveTune so the two installs coexist.
                upgradeUuid = "8c4a2f61-3d7b-4e9a-b2c8-5f1d6e7a9c04"
                shortcut = true
            }
            linux {
                packageName = "wavetune"
                // The plugin prefixes this with `vendor`, so keep only the email here.
                debMaintainer = "noreply@wavetune.app"
                menuGroup = "Audio"
                appCategory = "AudioVideo"
            }
        }
    }
}
