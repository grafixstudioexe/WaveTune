package com.arynis.wavetune.shared.jam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JamDefaultsTest {
    @Test
    fun `Android and desktop use the same Jam endpoint`() {
        assertEquals("wss://wavetune.onrender.com/ws", JamDefaults.SERVER_ENDPOINT)
    }

    @Test
    fun `invite links normalize room codes consistently`() {
        assertEquals(
            "https://wavetune.onrender.com/?code=AB12CD",
            JamDefaults.inviteUrl(" ab12cd "),
        )
        assertNull(JamDefaults.inviteUrl("x"))
    }
}
