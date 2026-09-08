// TG-ONLY-FILE: Telegram module — keep whole file on upstream merge
package com.nuvio.tv.core.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramCredentialsTest {

    @Test
    fun `api id accepts positive numbers with whitespace`() {
        assertEquals(39840467, TelegramCredentialsValidator.parseApiId("  39840467 "))
    }

    @Test
    fun `api id rejects blank non-numeric and non-positive`() {
        assertNull(TelegramCredentialsValidator.parseApiId(""))
        assertNull(TelegramCredentialsValidator.parseApiId("   "))
        assertNull(TelegramCredentialsValidator.parseApiId(null))
        assertNull(TelegramCredentialsValidator.parseApiId("abc123"))
        assertNull(TelegramCredentialsValidator.parseApiId("0"))
        assertNull(TelegramCredentialsValidator.parseApiId("-5"))
    }

    @Test
    fun `api hash accepts 32 hex chars case-insensitive`() {
        assertTrue(TelegramCredentialsValidator.isValidApiHash("2d1d4b2229e96952515afa0855daba4f"))
        assertTrue(TelegramCredentialsValidator.isValidApiHash("2D1D4B2229E96952515AFA0855DABA4F  "))
    }

    @Test
    fun `api hash rejects wrong length and non-hex`() {
        assertFalse(TelegramCredentialsValidator.isValidApiHash(""))
        assertFalse(TelegramCredentialsValidator.isValidApiHash(null))
        assertFalse(TelegramCredentialsValidator.isValidApiHash("2d1d4b2229e96952515afa0855daba4"))
        assertFalse(TelegramCredentialsValidator.isValidApiHash("2d1d4b2229e96952515afa0855daba4ff"))
        assertFalse(TelegramCredentialsValidator.isValidApiHash("zd1d4b2229e96952515afa0855daba4f"))
    }
}
