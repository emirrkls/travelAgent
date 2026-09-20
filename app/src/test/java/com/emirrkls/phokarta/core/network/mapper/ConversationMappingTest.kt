package com.emirrkls.phokarta.core.network.mapper

import com.emirrkls.phokarta.core.model.ConversationEntryType
import com.emirrkls.phokarta.core.network.model.ConversationEntryDto
import com.emirrkls.phokarta.core.network.model.CursorPageDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMappingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `conversation page keeps roots and replies in server order with viewer capabilities`() {
        val page = json.decodeFromString<CursorPageDto<ConversationEntryDto>>(fixture()).toConversationPage()

        assertEquals(listOf("new-root", "old-root"), page.items.map { it.body })
        val question = page.items.first()
        assertEquals(ConversationEntryType.QUESTION, question.type)
        assertEquals(listOf("first answer", "second answer"), question.replies.map { it.body })
        assertTrue(question.replies.first().experienceAuthor)
        assertFalse(question.ownedByViewer)
        assertTrue(question.reportableByViewer)
        assertEquals("cursor-2", page.nextCursor)
    }

    @Test
    fun `unknown future type degrades safely`() {
        val page = json.decodeFromString<CursorPageDto<ConversationEntryDto>>(
            fixture().replaceFirst("QUESTION", "FUTURE_TYPE"),
        ).toConversationPage()

        assertEquals(ConversationEntryType.UNKNOWN, page.items.first().type)
    }

    private fun fixture() = """
        {
          "items": [{
            "id": "10000000-0000-4000-8000-000000000001",
            "experienceId": "20000000-0000-4000-8000-000000000001",
            "type": "QUESTION",
            "body": "new-root",
            "author": {"id":"30000000-0000-4000-8000-000000000001","username":"reader","displayName":"Reader"},
            "createdAt": "2026-09-20T12:00:00Z", "updatedAt": "2026-09-20T12:00:00Z",
            "edited": false, "experienceAuthor": false, "ownedByViewer": false, "reportableByViewer": true,
            "replies": [{
              "id":"40000000-0000-4000-8000-000000000001","experienceId":"20000000-0000-4000-8000-000000000001",
              "type":"REPLY","body":"first answer",
              "author":{"id":"50000000-0000-4000-8000-000000000001","username":"author","displayName":"Author"},
              "createdAt":"2026-09-20T12:01:00Z","updatedAt":"2026-09-20T12:01:00Z",
              "edited":false,"experienceAuthor":true,"ownedByViewer":false,"reportableByViewer":true
            }, {
              "id":"40000000-0000-4000-8000-000000000002","experienceId":"20000000-0000-4000-8000-000000000001",
              "type":"REPLY","body":"second answer",
              "author":{"id":"30000000-0000-4000-8000-000000000001","username":"reader","displayName":"Reader"},
              "createdAt":"2026-09-20T12:02:00Z","updatedAt":"2026-09-20T12:02:00Z",
              "edited":false,"experienceAuthor":false,"ownedByViewer":true,"reportableByViewer":false
            }]
          }, {
            "id": "10000000-0000-4000-8000-000000000002",
            "experienceId": "20000000-0000-4000-8000-000000000001",
            "type": "COMMENT", "body": "old-root",
            "author": {"id":"30000000-0000-4000-8000-000000000001","username":"reader","displayName":"Reader"},
            "createdAt": "2026-09-20T11:00:00Z", "updatedAt": "2026-09-20T11:00:00Z",
            "edited": false, "experienceAuthor": false, "ownedByViewer": true, "reportableByViewer": false
          }],
          "nextCursor": "cursor-2", "hasMore": true
        }
    """.trimIndent()
}
