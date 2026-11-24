package com.arynis.wavetune.util

/**
 * Custom URI schemes used for cross-device links.
 *
 * `wavetune://` is the WaveTune scheme. `wavetune://` is kept as a legacy
 * alias so links produced by the upstream app (and any already-shared URL)
 * still open here instead of dying in the browser.
 */
const val APP_LINK_SCHEME = "wavetune"

const val LEGACY_APP_LINK_SCHEME = "wavetune"

/** True when [this] is either the WaveTune scheme or its legacy alias. */
fun String?.isAppLinkScheme(): Boolean =
    equals(APP_LINK_SCHEME, ignoreCase = true) || equals(LEGACY_APP_LINK_SCHEME, ignoreCase = true)
