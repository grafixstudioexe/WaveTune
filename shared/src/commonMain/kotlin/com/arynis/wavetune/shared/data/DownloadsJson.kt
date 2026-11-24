package com.arynis.wavetune.shared.data

import kotlinx.serialization.json.Json

/** JSON codec for the desktop downloads index sidecar. */
internal val DownloadsJson: Json = Json { ignoreUnknownKeys = true; prettyPrint = true }
