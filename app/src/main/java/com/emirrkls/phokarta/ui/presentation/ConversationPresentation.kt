package com.emirrkls.phokarta.ui.presentation

import com.emirrkls.phokarta.core.model.ConversationEntryType
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ConversationComposerMode { EXPANDED, COMPACT }

enum class ConversationAuthorBadge { AUTHOR_ANSWER, AUTHOR }

data class ConversationCountPresentation(
    val visual: String,
    val accessibilityLabel: String,
)

object ConversationPresentation {
    fun timestamp(
        createdAt: String,
        now: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale,
    ): String {
        val created = runCatching { OffsetDateTime.parse(createdAt).toInstant() }
            .recoverCatching { Instant.parse(createdAt) }
            .getOrElse { return if (locale.language == "tr") "Az önce" else "Just now" }
        val elapsedSeconds = Duration.between(created, now).seconds.coerceAtLeast(0)
        val createdDate = created.atZone(zoneId).toLocalDate()
        val today = now.atZone(zoneId).toLocalDate()
        val turkish = locale.language == "tr"

        return when {
            elapsedSeconds < 60 -> if (turkish) "Az önce" else "Just now"
            elapsedSeconds < 3_600 -> {
                val minutes = elapsedSeconds / 60
                if (turkish) "$minutes dk" else "${minutes}m"
            }
            createdDate == today -> {
                val hours = elapsedSeconds / 3_600
                if (turkish) "$hours sa" else "${hours}h"
            }
            createdDate == today.minusDays(1) -> if (turkish) "Dün" else "Yesterday"
            createdDate.year == today.year -> createdDate.format(
                DateTimeFormatter.ofPattern(if (turkish) "d MMM" else "MMM d", locale),
            )
            else -> createdDate.format(
                DateTimeFormatter.ofPattern(if (turkish) "d MMM yyyy" else "MMM d, yyyy", locale),
            )
        }
    }

    fun metadata(
        typeLabel: String?,
        timestamp: String,
        edited: Boolean,
        editedLabel: String,
    ): String = buildList {
        typeLabel?.takeIf(String::isNotBlank)?.let(::add)
        add(timestamp)
        if (edited) add(editedLabel)
    }.joinToString(" · ")

    fun authorBadge(
        experienceAuthor: Boolean,
        rootType: ConversationEntryType,
        isReply: Boolean,
    ): ConversationAuthorBadge? = when {
        !experienceAuthor -> null
        isReply && rootType == ConversationEntryType.QUESTION -> ConversationAuthorBadge.AUTHOR_ANSWER
        else -> ConversationAuthorBadge.AUTHOR
    }

    fun composerMode(
        visibleRootCount: Int,
        expansionRequested: Boolean,
        draft: String,
    ): ConversationComposerMode = if (
        visibleRootCount == 0 || expansionRequested || draft.isNotEmpty()
    ) {
        ConversationComposerMode.EXPANDED
    } else {
        ConversationComposerMode.COMPACT
    }

    fun count(count: Long, locale: Locale): ConversationCountPresentation? {
        if (count <= 0) return null
        val label = when {
            locale.language == "tr" && count == 1L -> "1 soru veya yorum"
            locale.language == "tr" -> "$count soru ve yorum"
            count == 1L -> "1 question or comment"
            else -> "$count questions and comments"
        }
        return ConversationCountPresentation(count.toString(), label)
    }
}
