package com.emirrkls.phokarta.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emirrkls.phokarta.core.auth.AuthenticatedUser
import com.emirrkls.phokarta.core.auth.SessionManager
import com.emirrkls.phokarta.core.auth.TokenStore
import com.emirrkls.phokarta.core.data.RoomVisitDraftRepository
import com.emirrkls.phokarta.core.database.TravelDatabase
import com.emirrkls.phokarta.core.database.entity.MutationTypeValue
import com.emirrkls.phokarta.core.database.entity.VisitDraftEntity
import com.emirrkls.phokarta.core.model.RatingDimension
import com.emirrkls.phokarta.core.model.Visibility
import com.emirrkls.phokarta.core.model.Visit
import com.emirrkls.phokarta.core.model.CompanionCode
import com.emirrkls.phokarta.core.model.DimensionStateCode
import com.emirrkls.phokarta.core.model.ExperienceTitleSource
import com.emirrkls.phokarta.core.model.OverallFeelingCode
import com.emirrkls.phokarta.core.model.PracticalSignalCode
import com.emirrkls.phokarta.core.model.PrimaryExperienceCode
import com.emirrkls.phokarta.core.model.TimeOfDayCode
import com.emirrkls.phokarta.core.model.VibeCode
import com.emirrkls.phokarta.core.model.Experience
import com.emirrkls.phokarta.core.model.ExperienceAuthor
import com.emirrkls.phokarta.core.model.ExperienceClassification
import com.emirrkls.phokarta.core.model.ExperienceFamily
import com.emirrkls.phokarta.core.model.ExperienceFeeling
import com.emirrkls.phokarta.core.model.ExperiencePlace
import com.emirrkls.phokarta.core.model.ExperiencePrimary
import com.emirrkls.phokarta.core.model.ExperienceVisibility
import com.emirrkls.phokarta.core.model.FeelingProvenance
import com.emirrkls.phokarta.core.model.ConversationAuthor
import com.emirrkls.phokarta.core.model.ConversationEntry
import com.emirrkls.phokarta.core.model.ConversationEntryType
import com.emirrkls.phokarta.core.model.ConversationSyncState
import com.emirrkls.phokarta.feature.rating.VisitDraft
import com.emirrkls.phokarta.core.time.EpochClock
import com.emirrkls.phokarta.core.media.MediaFileMutationLock
import com.emirrkls.phokarta.core.media.VisitMediaStore
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineMutationRepositoryInstrumentedTest {
    private lateinit var database: TravelDatabase
    private lateinit var session: SessionManager
    private lateinit var scheduler: RecordingScheduler
    private lateinit var draftRepository: RoomVisitDraftRepository
    private lateinit var repository: RoomOfflineMutationRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, TravelDatabase::class.java)
            .allowMainThreadQueries().build()
        session = SessionManager(TokenStore(context.getSharedPreferences("mutation-test", Context.MODE_PRIVATE)))
        scheduler = RecordingScheduler()
        draftRepository = RoomVisitDraftRepository(
            database.visitDraftDao(), session, EpochClock { 5_000L },
            VisitMediaStore(context), MediaFileMutationLock(),
        )
        repository = RoomOfflineMutationRepository(
            database, database.pendingMutationDao(), database.visitDraftDao(),
            draftRepository, database.savedPlaceDao(),
            session, EpochClock { 1_000L }, scheduler,
            VisitMediaStore(context),
            database.experienceMilestoneDao(),
        )
        login(USER_A)
    }

    @After fun tearDown() = database.close()

    @Test fun draftToPendingVisitIsAtomicAndPayloadIsExact() = runTest {
        database.visitDraftDao().upsertDraft(VisitDraftEntity(
            USER_A, PLACE, 9f, "draft", "secret", 20_000, "FRIENDS", false, 10, 10,
        ))
        val visit = Visit("local", USER_A, PLACE, LocalDate.of(2026, 8, 20), 9.0,
            mapOf(RatingDimension.SEA to 9.5), "review", "private memory",
            photos = listOf("https://example.test/photo.jpg"), visibility = Visibility.FRIENDS)

        val mutationId = repository.commitVisit(visit)
        val pending = database.pendingMutationDao().getVisit(mutationId)!!

        assertNull(database.visitDraftDao().getDraft(USER_A, PLACE))
        assertEquals("private memory", pending.payload.privateMemory)
        assertEquals("FRIENDS", pending.payload.visibility)
        assertEquals(9.5, pending.dimensions.single().score, 0.0)
        assertEquals("https://example.test/photo.jpg", pending.photos.single().legacyUrl)
        assertEquals(mutationId, repository.observePendingVisits().first().single().mutationId)
        assertEquals(1, scheduler.calls)
    }

    @Test fun savedRapidToggleCoalescesToOneLatestGeneration() = runTest {
        assertTrue(repository.toggleSaved(PLACE))
        assertFalse(repository.toggleSaved(PLACE))
        assertTrue(repository.toggleSaved(PLACE))

        val rows = database.pendingMutationDao().observeForUser(USER_A).first()
            .filter { it.type == MutationTypeValue.SET_SAVED_STATE }
        assertEquals(1, rows.size)
        assertEquals(true, rows.single().desiredSaved)
        assertEquals(3, rows.single().generation)
        assertTrue(database.savedPlaceDao().getSavedPlace(USER_A, PLACE) != null)
    }

    @Test fun nativeV2DraftCommitsToExplicitVersionedPayloadWithStableCodes() = runTest {
        val draft = VisitDraft(
            visitDate = LocalDate.of(2026, 9, 16),
            visibility = Visibility.FRIENDS,
            primaryExperience = PrimaryExperienceCode.GUN_BATIMI,
            overallFeeling = OverallFeelingCode.BAYILDIM,
            semanticDimensions = mapOf("SCENERY" to DimensionStateCode.VERY_GOOD),
            companion = CompanionCode.PARTNER,
            timeOfDay = TimeOfDayCode.EVENING,
            vibes = setOf(VibeCode.SCENIC, VibeCode.CALM),
            practicalSignals = setOf(PracticalSignalCode.FREE, PracticalSignalCode.ARRIVE_EARLY),
            title = "Golden hour",
            titleSource = ExperienceTitleSource.CUSTOM,
            story = "story",
            tip = "arrive early",
            privateMemory = "owner only",
        )

        val mutationId = repository.commitExperienceV2(PLACE, draft)
        val pending = database.pendingMutationDao().getExperienceV2(mutationId)!!

        assertEquals(2, pending.mutation.payloadVersion)
        assertEquals(MutationTypeValue.PUBLISH_EXPERIENCE_V2, pending.mutation.type)
        assertEquals("GUN_BATIMI", pending.payload.primaryExperienceCode)
        assertEquals("BAYILDIM", pending.payload.overallFeelingCode)
        assertEquals("CALM,SCENIC", pending.payload.vibeCodes)
        assertEquals("ARRIVE_EARLY,FREE", pending.payload.practicalSignalCodes)
        assertEquals("VERY_GOOD", pending.dimensions.single().semanticStateCode)
        assertEquals(mutationId, repository.observePendingVisits().first().single().mutationId)
    }

    @Test fun multiplePendingVisitsForSamePlaceRemainDistinct() = runTest {
        val visit = Visit(
            "local", USER_A, PLACE, LocalDate.of(2026, 8, 20), 9.0,
            emptyMap(), "review", "", visibility = Visibility.PUBLIC,
        )

        val first = repository.commitVisit(visit)
        val second = repository.commitVisit(visit.copy(review = "another review"))

        val rows = database.pendingMutationDao().observeForUser(USER_A).first()
            .filter { it.type == MutationTypeValue.PUBLISH_VISIT }
        assertEquals(2, rows.size)
        assertEquals(setOf(first, second), rows.map { it.mutationId }.toSet())
        assertEquals(setOf(first, second), rows.map { it.resourceKey }.toSet())
    }

    @Test fun logoutAndAccountSwitchRetainButHideOriginalQueue() = runTest {
        repository.toggleSaved(PLACE)
        session.clearSession()
        login(USER_B)
        assertTrue(database.pendingMutationDao().eligible(USER_B, 20).isEmpty())
        assertEquals(1, database.pendingMutationDao().eligible(USER_A, 20).size)
    }

    @Test fun plannedExperienceRapidToggleIsDurableCoalescedAndAccountScoped() = runTest {
        val experience = experience()
        assertTrue(repository.togglePlannedExperience(experience))
        assertTrue(database.experienceMilestoneDao().plan(USER_A, EXPERIENCE) != null)

        val first = database.pendingMutationDao().observeForUser(USER_A).first()
            .single { it.type == MutationTypeValue.SET_PLANNED_EXPERIENCE_STATE }
        assertEquals(true, first.desiredSaved)
        assertEquals(1, first.generation)

        assertFalse(repository.togglePlannedExperience(experience))
        assertNull(database.experienceMilestoneDao().plan(USER_A, EXPERIENCE))
        val coalesced = database.pendingMutationDao().observeForUser(USER_A).first()
            .single { it.type == MutationTypeValue.SET_PLANNED_EXPERIENCE_STATE }
        assertEquals(first.mutationId, coalesced.mutationId)
        assertEquals(false, coalesced.desiredSaved)
        assertEquals(2, coalesced.generation)

        repository.togglePlannedExperience(experience)
        session.clearSession()
        login(USER_B)
        assertTrue(database.experienceMilestoneDao().observePlans(USER_B).first().isEmpty())
        assertEquals(EXPERIENCE, database.experienceMilestoneDao().observePlans(USER_A).first().single().experienceId)
    }

    @Test fun acknowledgementIsDurableDuplicateSafeAndUsesItsFinalIdForSync() = runTest {
        val experience = experience()
        val first = repository.acknowledgeExperience(experience)
        val duplicate = repository.acknowledgeExperience(experience)

        assertEquals(first, duplicate)
        val local = database.experienceMilestoneDao().acknowledgementForSource(USER_A, EXPERIENCE)!!
        assertEquals(first, local.id)
        assertEquals(PLACE, local.placeId)
        assertEquals("GUN_BATIMI", local.primaryExperienceCode)
        val mutation = database.pendingMutationDao().observeForUser(USER_A).first()
            .single { it.type == MutationTypeValue.ACKNOWLEDGE_EXPERIENCE }
        assertEquals(first, mutation.mutationId)
        assertEquals(EXPERIENCE, mutation.resourceKey)

        session.clearSession()
        login(USER_B)
        assertTrue(database.experienceMilestoneDao().observeUnconvertedAcknowledgements(USER_B).first().isEmpty())
        assertEquals(first, database.experienceMilestoneDao()
            .observeUnconvertedAcknowledgements(USER_A).first().single().id)
    }

    @Test fun conversationRootIsOptimisticDurableAndAccountScoped() = runTest {
        val mutationId = repository.createConversationRoot(
            experience(), ConversationEntryType.QUESTION, "  Is it quiet in the morning?  ",
        )

        val local = database.conversationDao().entry(USER_A, mutationId)!!
        val payload = database.conversationDao().payload(mutationId)!!
        assertEquals("Is it quiet in the morning?", local.body)
        assertEquals("QUESTION", local.type)
        assertEquals(ConversationSyncState.PENDING.name, local.syncState)
        assertEquals(mutationId, local.clientMutationId)
        assertEquals(EXPERIENCE, payload.experienceId)
        assertEquals(MutationTypeValue.CREATE_CONVERSATION_ROOT,
            database.pendingMutationDao().get(mutationId)!!.type)

        session.clearSession()
        login(USER_B)
        assertTrue(database.conversationDao().entries(USER_B, EXPERIENCE).isEmpty())
        assertEquals(1, database.conversationDao().entries(USER_A, EXPERIENCE).size)
    }

    @Test fun conversationReplyRequiresSyncedRootAndOwnedEntriesQueueEditAndDelete() = runTest {
        val pendingId = repository.createConversationRoot(
            experience(), ConversationEntryType.COMMENT, "Local comment",
        )
        val pendingRoot = conversationEntry(pendingId, ConversationSyncState.PENDING)
        val replyFailure = runCatching {
            repository.createConversationReply(experience(), pendingRoot, "Too early")
        }
        assertTrue(replyFailure.isFailure)

        val synced = conversationEntry("server-root", ConversationSyncState.SYNCED)
        database.conversationDao().upsertEntry(com.emirrkls.phokarta.core.database.entity.ConversationEntryEntity(
            ownerUserId = USER_A, id = synced.id, experienceId = EXPERIENCE, parentEntryId = null,
            type = synced.type.name, body = synced.body, authorId = USER_A, authorUsername = "ada",
            authorDisplayName = "Ada", authorAvatarUrl = null,
            createdAt = synced.createdAt, updatedAt = synced.updatedAt, edited = false,
            experienceAuthor = false, ownedByViewer = true, reportableByViewer = false,
            syncState = ConversationSyncState.SYNCED.name, clientMutationId = null,
        ))

        val replyId = repository.createConversationReply(experience(), synced, "A reply")
        assertEquals("server-root", database.conversationDao().entry(USER_A, replyId)!!.parentEntryId)
        repository.editConversationEntry(synced, "Edited comment")
        val edited = database.conversationDao().entry(USER_A, synced.id)!!
        assertEquals("Edited comment", edited.body)
        assertEquals(ConversationSyncState.PENDING.name, edited.syncState)
        assertTrue(scheduler.calls >= 3)
    }

    private fun conversationEntry(id: String, syncState: ConversationSyncState) = ConversationEntry(
        id = id, experienceId = EXPERIENCE, type = ConversationEntryType.COMMENT, body = "Comment",
        author = ConversationAuthor(USER_A, "ada", "Ada", null),
        createdAt = "2026-09-20T10:00:00Z", updatedAt = "2026-09-20T10:00:00Z",
        edited = false, experienceAuthor = false, ownedByViewer = true,
        reportableByViewer = false, syncState = syncState,
    )

    private fun experience() = Experience(
        id = EXPERIENCE,
        classification = ExperienceClassification.NATIVE_V2,
        author = ExperienceAuthor(USER_B, "author", "Author", null),
        place = ExperiencePlace(PLACE, "Milestone Place", "BEACH", "Istanbul", "Marmara", "Turkiye", ""),
        experiencedAt = LocalDate.of(2026, 9, 17),
        title = "Sunset",
        titleSource = ExperienceTitleSource.CUSTOM,
        titlePersisted = true,
        story = "Story",
        tip = null,
        feeling = ExperienceFeeling(OverallFeelingCode.GUZELDI, FeelingProvenance.EXPLICIT, 8.0),
        primaryExperience = ExperiencePrimary(
            PrimaryExperienceCode.GUN_BATIMI, true, ExperienceFamily.SCENERY_AND_MOMENT, null,
        ),
        companion = CompanionCode.PARTNER,
        timeOfDay = TimeOfDayCode.EVENING,
        vibes = emptyList(),
        practicalSignals = emptyList(),
        dimensions = emptyList(),
        media = emptyList(),
        visibility = ExperienceVisibility.PUBLIC,
        taxonomyVersion = 1,
    )

    private fun login(id: String) = session.setAuthenticated(
        AuthenticatedUser(id, "$id@test.local", id, id, "", ""), "access", "refresh",
    )

    private class RecordingScheduler : MutationSyncScheduler {
        var calls = 0
        override fun schedule() { calls++ }
    }

    companion object {
        const val USER_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val USER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val PLACE = "20000000-0000-0000-0000-000000000003"
        const val EXPERIENCE = "30000000-0000-0000-0000-000000000003"
    }
}
