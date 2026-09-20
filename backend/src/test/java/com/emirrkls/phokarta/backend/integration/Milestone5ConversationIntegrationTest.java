package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.api.dto.CreateConversationEntryRequest;
import com.emirrkls.phokarta.backend.api.dto.CreateConversationReplyRequest;
import com.emirrkls.phokarta.backend.api.dto.CreateReportRequest;
import com.emirrkls.phokarta.backend.api.dto.UpdateConversationEntryRequest;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.model.ConversationEntryType;
import com.emirrkls.phokarta.backend.domain.model.ReportReason;
import com.emirrkls.phokarta.backend.domain.model.ReportTargetType;
import com.emirrkls.phokarta.backend.service.ExperienceConversationService;
import com.emirrkls.phokarta.backend.service.ProfileV2Service;
import com.emirrkls.phokarta.backend.service.ReportService;
import com.emirrkls.phokarta.backend.support.PolicyAcceptanceSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class Milestone5ConversationIntegrationTest {
    private static final UUID AUTHOR = UUID.fromString("11111111-1111-1111-1111-111111111551");
    private static final UUID PARTICIPANT = UUID.fromString("11111111-1111-1111-1111-111111111552");
    private static final UUID VIEWER = UUID.fromString("11111111-1111-1111-1111-111111111553");
    private static final UUID UNRELATED = UUID.fromString("11111111-1111-1111-1111-111111111554");
    private static final UUID PLACE = UUID.fromString("20000000-0000-0000-0000-000000000551");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    @Autowired JdbcTemplate jdbc;
    @Autowired ExperienceConversationService conversations;
    @Autowired ReportService reports;
    @Autowired ProfileV2Service profiles;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from users");
        jdbc.update("delete from places");
        user(AUTHOR, "m5_author", "PUBLIC");
        user(PARTICIPANT, "m5_participant", "PUBLIC");
        user(VIEWER, "m5_viewer", "PUBLIC");
        user(UNRELATED, "m5_unrelated", "PUBLIC");
        place();
        PolicyAcceptanceSupport.acceptCurrent(jdbc, AUTHOR);
        PolicyAcceptanceSupport.acceptCurrent(jdbc, PARTICIPANT);
        PolicyAcceptanceSupport.acceptCurrent(jdbc, VIEWER);
        PolicyAcceptanceSupport.acceptCurrent(jdbc, UNRELATED);
    }

    @Test
    void rootsRepliesEditingOwnershipIdempotencyAndRatingIsolationAreLocked() {
        UUID experience = experience(AUTHOR, "PUBLIC");
        double ratingBefore = rating(experience);
        UUID questionMutation = UUID.randomUUID();
        var questionRequest = new CreateConversationEntryRequest(
                questionMutation, ConversationEntryType.QUESTION, "Can I return without a car?");
        var question = conversations.createRoot(experience, PARTICIPANT, questionRequest);
        var duplicate = conversations.createRoot(experience, PARTICIPANT, questionRequest);
        var comment = conversations.createRoot(experience, VIEWER,
                new CreateConversationEntryRequest(UUID.randomUUID(), ConversationEntryType.COMMENT,
                        "It was calmer in the morning."));
        var answer = conversations.createReply(question.id(), AUTHOR,
                new CreateConversationReplyRequest(UUID.randomUUID(), "Buses ran until 22:00."));

        assertThat(duplicate.id()).isEqualTo(question.id());
        assertThat(answer.experienceAuthor()).isTrue();
        assertThat(conversations.visibleRootCount(experience, VIEWER)).isEqualTo(2);
        assertThat(conversations.read(experience, VIEWER, null, 20).items())
                .extracting(value -> value.type()).containsExactly(
                        ConversationEntryType.COMMENT, ConversationEntryType.QUESTION);
        assertThat(conversations.read(experience, VIEWER, null, 20).items().get(1).replies())
                .singleElement().satisfies(reply -> assertThat(reply.id()).isEqualTo(answer.id()));
        var firstPage = conversations.read(experience, VIEWER, null, 1);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.items()).singleElement()
                .satisfies(value -> assertThat(value.id()).isEqualTo(comment.id()));
        var secondPage = conversations.read(experience, VIEWER, firstPage.nextCursor(), 1);
        assertThat(secondPage.hasMore()).isFalse();
        assertThat(secondPage.items()).singleElement()
                .satisfies(value -> assertThat(value.id()).isEqualTo(question.id()));
        assertThatThrownBy(() -> conversations.createReply(answer.id(), VIEWER,
                new CreateConversationReplyRequest(UUID.randomUUID(), "Nested")))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("NESTED_REPLY_NOT_ALLOWED"));
        assertThatThrownBy(() -> conversations.createRoot(experience, PARTICIPANT,
                new CreateConversationEntryRequest(UUID.randomUUID(), ConversationEntryType.REPLY, "Invalid")))
                .isInstanceOf(ApiException.class);

        var edited = conversations.edit(question.id(), PARTICIPANT,
                new UpdateConversationEntryRequest("Is it easy to return without a car?"));
        assertThat(edited.edited()).isTrue();
        assertThatThrownBy(() -> conversations.edit(question.id(), AUTHOR,
                new UpdateConversationEntryRequest("Owner rewrite")))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(403));
        assertThatThrownBy(() -> conversations.delete(question.id(), AUTHOR))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(403));

        conversations.delete(answer.id(), AUTHOR);
        assertThat(conversations.visibleRootCount(experience, VIEWER)).isEqualTo(2);
        conversations.delete(question.id(), PARTICIPANT);
        conversations.delete(question.id(), PARTICIPANT);
        assertThat(conversations.visibleRootCount(experience, VIEWER)).isEqualTo(1);
        assertThat(rating(experience)).isEqualTo(ratingBefore);
        assertThat(count("select count(*) from experience_acknowledgements where source_experience_id = ?", experience))
                .isZero();
        assertThat(count("select count(*) from planned_experiences where experience_id = ?", experience))
                .isZero();
        assertThat(comment.type()).isEqualTo(ConversationEntryType.COMMENT);
    }

    @Test
    void privateProfileParticipationBlockFilteringAndReportPrivacyAreLocked() {
        UUID experience = experience(AUTHOR, "PUBLIC");
        jdbc.update("update users set profile_visibility = 'PRIVATE' where id = ?", PARTICIPANT);
        var privateComment = conversations.createRoot(experience, PARTICIPANT,
                new CreateConversationEntryRequest(UUID.randomUUID(), ConversationEntryType.COMMENT,
                        "Explicit public-conversation participation"));
        var authorQuestion = conversations.createRoot(experience, AUTHOR,
                new CreateConversationEntryRequest(UUID.randomUUID(), ConversationEntryType.QUESTION,
                        "What did you notice?"));
        var privateReply = conversations.createReply(authorQuestion.id(), PARTICIPANT,
                new CreateConversationReplyRequest(UUID.randomUUID(), "The morning was quiet."));

        assertThat(conversations.read(experience, VIEWER, null, 20).items())
                .extracting(value -> value.id()).contains(privateComment.id(), authorQuestion.id());
        assertThat(profiles.profile(PARTICIPANT, VIEWER).fullProfile()).isFalse();

        ReportService.SubmitResult report = reports.submit(VIEWER, new CreateReportRequest(
                ReportTargetType.CONVERSATION_ENTRY, privateReply.id(), ReportReason.SPAM, null));
        assertThat(report.created()).isTrue();
        assertThat(reports.submit(VIEWER, new CreateReportRequest(
                ReportTargetType.CONVERSATION_ENTRY, privateReply.id(), ReportReason.SPAM, null)).created())
                .isFalse();

        jdbc.update("insert into user_blocks (blocker_user_id, blocked_user_id, created_at) values (?, ?, now())",
                VIEWER, PARTICIPANT);
        var blockedView = conversations.read(experience, VIEWER, null, 20);
        assertThat(blockedView.items()).extracting(value -> value.id())
                .containsExactly(authorQuestion.id());
        assertThat(blockedView.items().get(0).replies()).isEmpty();
        assertThat(conversations.visibleRootCount(experience, VIEWER)).isEqualTo(1);
        assertThatThrownBy(() -> reports.submit(VIEWER, new CreateReportRequest(
                ReportTargetType.CONVERSATION_ENTRY, privateComment.id(), ReportReason.SPAM, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(404));
    }

    @Test
    void visibilityExperienceDeletionAndAccountDeletionNeverLeaveProductOrphans() {
        UUID experience = experience(AUTHOR, "PUBLIC");
        var root = conversations.createRoot(experience, PARTICIPANT,
                new CreateConversationEntryRequest(UUID.randomUUID(), ConversationEntryType.QUESTION, "Question"));
        var reply = conversations.createReply(root.id(), VIEWER,
                new CreateConversationReplyRequest(UUID.randomUUID(), "Reply"));

        jdbc.update("update visits set visibility = 'FRIENDS' where id = ?", experience);
        assertThatThrownBy(() -> conversations.read(experience, VIEWER, null, 20))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(404));
        assertThat(conversations.read(experience, AUTHOR, null, 20).items()).hasSize(1);
        jdbc.update("update visits set visibility = 'PRIVATE' where id = ?", experience);
        assertThatThrownBy(() -> conversations.read(experience, VIEWER, null, 20))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status().value()).isEqualTo(404));
        assertThat(conversations.read(experience, AUTHOR, null, 20).items()).hasSize(1);
        jdbc.update("update visits set visibility = 'PUBLIC' where id = ?", experience);

        jdbc.update("delete from users where id = ?", VIEWER);
        var afterReplyAuthorDeletion = conversations.read(experience, AUTHOR, null, 20);
        assertThat(afterReplyAuthorDeletion.items()).singleElement().satisfies(value -> {
            assertThat(value.id()).isEqualTo(root.id());
            assertThat(value.replies()).isEmpty();
        });
        assertThat(count("select count(*) from experience_conversation_entries where id = ?", reply.id()))
                .isZero();

        jdbc.update("delete from users where id = ?", PARTICIPANT);
        assertThat(conversations.visibleRootCount(experience, AUTHOR)).isZero();
        assertThat(count("select count(*) from experience_conversation_entries where experience_id = ?", experience))
                .isZero();

        UUID unrelated = experience(UNRELATED, "PUBLIC");
        conversations.createRoot(unrelated, UNRELATED,
                new CreateConversationEntryRequest(UUID.randomUUID(), ConversationEntryType.COMMENT, "Unrelated"));
        UUID sourceDeleted = experience(UNRELATED, "PUBLIC");
        conversations.createRoot(sourceDeleted, UNRELATED,
                new CreateConversationEntryRequest(UUID.randomUUID(), ConversationEntryType.COMMENT, "Source delete"));
        jdbc.update("delete from visits where id = ?", sourceDeleted);
        assertThat(count("select count(*) from experience_conversation_entries where experience_id = ?", sourceDeleted))
                .isZero();
        UUID authorOwnedExperience = experience(AUTHOR, "PUBLIC");
        conversations.createRoot(authorOwnedExperience, UNRELATED,
                new CreateConversationEntryRequest(UUID.randomUUID(), ConversationEntryType.COMMENT, "Cascade"));
        jdbc.update("delete from users where id = ?", AUTHOR);
        assertThat(count("select count(*) from experience_conversation_entries where experience_id = ?", experience))
                .isZero();
        assertThat(count("select count(*) from experience_conversation_entries where experience_id = ?",
                authorOwnedExperience)).isZero();
        assertThat(conversations.visibleRootCount(unrelated, UNRELATED)).isEqualTo(1);
    }

    private void user(UUID id, String username, String profileVisibility) {
        jdbc.update("""
                insert into users (
                    id, email, username, display_name, enabled, city_count, country_count,
                    followers_count, following_count, travel_taste, profile_visibility,
                    created_at, updated_at
                ) values (?, ?, ?, ?, true, 0, 0, 0, 0, '{}', ?, now(), now())
                """, id, username + "@example.test", username, username, profileVisibility);
    }

    private void place() {
        jdbc.update("""
                insert into places (
                    id, name, description, category, subcategories, location, city, region,
                    country, address, cover_image, photos, price_level, created_at, updated_at
                ) values (?, 'Conversation Place', '', 'BEACH', '{}',
                    ST_SetSRID(ST_MakePoint(29.0, 41.0), 4326), 'Istanbul', 'Marmara',
                    'Turkiye', '', '', '{}', 1, now(), now())
                """, PLACE);
    }

    private UUID experience(UUID authorId, String visibility) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into visits (
                    id, user_id, place_id, visited_at, overall_rating, public_review,
                    private_memory, photos, visibility, verification_status, created_at, updated_at
                ) values (?, ?, ?, current_date, 8, 'Story', 'private', '{}', ?,
                    'UNVERIFIED', now(), now())
                """, id, authorId, PLACE, visibility);
        return id;
    }

    private double rating(UUID experienceId) {
        return jdbc.queryForObject("select overall_rating from visits where id = ?", Double.class, experienceId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
