package com.emirrkls.phokarta.ui.presentation

import com.emirrkls.phokarta.core.model.ConversationEntryType
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationPresentationTest {
    private val now = Instant.parse("2026-09-22T12:00:00Z")

    @Test
    fun `timestamp formats just now minutes hours and yesterday in English`() {
        assertEquals("Just now", timestamp("2026-09-22T11:59:30Z", Locale.US))
        assertEquals("5m", timestamp("2026-09-22T11:55:00Z", Locale.US))
        assertEquals("2h", timestamp("2026-09-22T10:00:00Z", Locale.US))
        assertEquals("Yesterday", timestamp("2026-09-21T18:00:00Z", Locale.US))
    }

    @Test
    fun `timestamp formats just now minutes hours and yesterday in Turkish`() {
        val turkish = Locale.forLanguageTag("tr-TR")
        assertEquals("Az önce", timestamp("2026-09-22T11:59:30Z", turkish))
        assertEquals("5 dk", timestamp("2026-09-22T11:55:00Z", turkish))
        assertEquals("2 sa", timestamp("2026-09-22T10:00:00Z", turkish))
        assertEquals("Dün", timestamp("2026-09-21T18:00:00Z", turkish))
    }

    @Test
    fun `timestamp formats older current-year and old-year dates`() {
        assertEquals("Aug 21", timestamp("2026-08-21T10:00:00Z", Locale.US))
        assertEquals("Aug 21, 2025", timestamp("2025-08-21T10:00:00Z", Locale.US))
        assertEquals("21 Ağu", timestamp("2026-08-21T10:00:00Z", Locale.forLanguageTag("tr-TR")))
        assertEquals("21 Ağu 2025", timestamp("2025-08-21T10:00:00Z", Locale.forLanguageTag("tr-TR")))
    }

    @Test
    fun `root and reply metadata preserve type timestamp and edited state`() {
        assertEquals(
            "Question · 2h · Edited",
            ConversationPresentation.metadata("Question", "2h", true, "Edited"),
        )
        assertEquals(
            "Yorum · 21 Eyl",
            ConversationPresentation.metadata("Yorum", "21 Eyl", false, "Düzenlendi"),
        )
        assertEquals(
            "1h · Edited",
            ConversationPresentation.metadata(null, "1h", true, "Edited"),
        )
    }

    @Test
    fun `author answer presentation remains separate from timestamp metadata`() {
        assertEquals(
            ConversationAuthorBadge.AUTHOR_ANSWER,
            ConversationPresentation.authorBadge(true, ConversationEntryType.QUESTION, isReply = true),
        )
        assertEquals(
            ConversationAuthorBadge.AUTHOR,
            ConversationPresentation.authorBadge(true, ConversationEntryType.COMMENT, isReply = true),
        )
        assertNull(ConversationPresentation.authorBadge(false, ConversationEntryType.QUESTION, isReply = true))
    }

    @Test
    fun `empty is expanded while populated defaults compact and expands on request`() {
        assertEquals(
            ConversationComposerMode.EXPANDED,
            ConversationPresentation.composerMode(0, expansionRequested = false, draft = ""),
        )
        assertEquals(
            ConversationComposerMode.COMPACT,
            ConversationPresentation.composerMode(2, expansionRequested = false, draft = ""),
        )
        assertEquals(
            ConversationComposerMode.EXPANDED,
            ConversationPresentation.composerMode(2, expansionRequested = true, draft = ""),
        )
    }

    @Test
    fun `unsent draft keeps populated composer expanded`() {
        val draft = "Do buses still run late?"
        assertEquals(
            ConversationComposerMode.EXPANDED,
            ConversationPresentation.composerMode(2, expansionRequested = false, draft = draft),
        )
        assertEquals("Do buses still run late?", draft)
    }

    @Test
    fun `count omits zero and localizes singular and plural accessibility labels`() {
        assertNull(ConversationPresentation.count(0, Locale.US))
        assertEquals(
            ConversationCountPresentation("1", "1 question or comment"),
            ConversationPresentation.count(1, Locale.US),
        )
        assertEquals(
            ConversationCountPresentation("2", "2 questions and comments"),
            ConversationPresentation.count(2, Locale.US),
        )
        assertEquals(
            ConversationCountPresentation("1", "1 soru veya yorum"),
            ConversationPresentation.count(1, Locale.forLanguageTag("tr-TR")),
        )
        assertEquals(
            ConversationCountPresentation("2", "2 soru ve yorum"),
            ConversationPresentation.count(2, Locale.forLanguageTag("tr-TR")),
        )
    }

    private fun timestamp(value: String, locale: Locale): String = ConversationPresentation.timestamp(
        createdAt = value,
        now = now,
        zoneId = ZoneOffset.UTC,
        locale = locale,
    )
}
