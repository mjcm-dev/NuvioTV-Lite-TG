// TG-ONLY-FILE: Telegram module — keep whole file on upstream merge
package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TgUnreadableSourceErrorTest {

    @Test
    fun `tg source ending with no frame and no error shows feedback`() {
        assertTrue(
            shouldShowUnreadableSourceError(
                isTelegramSource = true,
                hasRenderedFirstFrame = false,
                hasFatalError = false
            )
        )
    }

    @Test
    fun `rendered frames keep the silent path`() {
        assertFalse(
            shouldShowUnreadableSourceError(
                isTelegramSource = true,
                hasRenderedFirstFrame = true,
                hasFatalError = false
            )
        )
    }

    @Test
    fun `existing error is not replaced`() {
        assertFalse(
            shouldShowUnreadableSourceError(
                isTelegramSource = true,
                hasRenderedFirstFrame = false,
                hasFatalError = true
            )
        )
    }

    @Test
    fun `non-tg sources keep the silent path`() {
        assertFalse(
            shouldShowUnreadableSourceError(
                isTelegramSource = false,
                hasRenderedFirstFrame = false,
                hasFatalError = false
            )
        )
    }
}
