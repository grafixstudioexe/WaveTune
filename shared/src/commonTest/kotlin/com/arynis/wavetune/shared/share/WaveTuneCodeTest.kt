package com.arynis.wavetune.shared.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WaveTuneCodeTest {

    @Test
    fun `round trips video ids`() {
        for (id in listOf("dQw4w9WgXcQ", "9bZkp7q19f0", "M7lc1UVf-VE", "a_B-c3D4e5F")) {
            val code = assertNotNull(WaveTuneCode.encode(id))
            assertEquals(id, WaveTuneCode.decode(code))
            assertEquals(code, WaveTuneCode.normalize(code.lowercase().replace("-", "")))
        }
    }

    @Test
    fun `rejects bad ids and damaged checksums`() {
        assertNull(WaveTuneCode.encode("short"))
        assertNull(WaveTuneCode.encode("dQw4w9WgXc!"))
        val code = assertNotNull(WaveTuneCode.encode("dQw4w9WgXcQ"))
        val damaged = code.dropLast(1) + if (code.last() == '0') '1' else '0'
        assertNull(WaveTuneCode.decode(damaged))
        assertNull(WaveTuneCode.decode("KM-00000-00000-00000-00000"))
    }

    @Test
    fun `emits the WaveTune prefix and still reads legacy codes`() {
        val code = assertNotNull(WaveTuneCode.encode("dQw4w9WgXcQ"))
        assertEquals("WT", code.substringBefore('-'))
        val legacy = "KM" + code.removePrefix("WT")
        assertEquals("dQw4w9WgXcQ", WaveTuneCode.decode(legacy))
        assertEquals(code, WaveTuneCode.normalize(legacy))
    }
}
