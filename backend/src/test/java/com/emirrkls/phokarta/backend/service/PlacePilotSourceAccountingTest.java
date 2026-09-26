package com.emirrkls.phokarta.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class PlacePilotSourceAccountingTest {
    static List<String> failures() { return PlacePilotAccountingFixture.failures(); }

    @Test void validRejectedSourcesAreNotCandidateDecisions() {
        var fixture = PlacePilotAccountingFixture.small();
        var report = inspect(fixture);
        assertThat(report.path("passed").asBoolean()).isTrue();
        assertThat(report.path("source_rejected_before_grouping").asInt()).isEqualTo(1);
        assertThat(report.path("candidate_decisions").path("AUTO_REJECT").asInt()).isZero();
    }

    @ParameterizedTest @MethodSource("failures")
    void unsafeAccountingFailsClosed(String scenario) {
        var fixture = PlacePilotAccountingFixture.small();
        fixture.corrupt(scenario);
        assertThat(inspect(fixture).path("passed").asBoolean()).as(scenario).isFalse();
    }

    @Test void exactSealedPopulationSizesPassWithSyntheticIdentities() {
        var report = inspect(new PlacePilotAccountingFixture(18924, 17630, 16135, 71));
        assertThat(report.path("passed").asBoolean()).isTrue();
        assertThat(report.path("distinct_candidate_source_coverage").asInt()).isEqualTo(17630);
        assertThat(report.path("candidate_group_count").asInt()).isEqualTo(16135);
        assertThat(report.path("candidate_decisions").path("QUARANTINE").asInt()).isEqualTo(16064);
        assertThat(report.path("selected_count").asInt()).isEqualTo(71);
    }

    @Test void noRejectedSourcesStillPass() {
        assertThat(inspect(new PlacePilotAccountingFixture(2, 2, 2, 1))
                .path("passed").asBoolean()).isTrue();
    }

    @Test void exactSealedSelectionMayBeAStrictSubsetOfEligibleAutoCreate() {
        var report = inspect(new PlacePilotAccountingFixture(3, 2, 2, 2, 1));
        assertThat(report.path("passed").asBoolean()).isTrue();
        assertThat(report.path("selected_count").asInt()).isEqualTo(1);
        assertThat(report.path("candidate_decisions").path("AUTO_CREATE").asInt()).isEqualTo(2);
    }

    @Test void approvalCannotMintReceiptForRejectedMembershipOrInconsistentState() {
        var fixture = PlacePilotAccountingFixture.small();
        var first = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.manifest.path("candidates").get(0);
        first.putArray("source_record_ids").add(fixture.sources.getLast().id().toString());
        assertThatThrownBy(() -> PlacePilotSourceAccounting.approved(fixture.manifest, "b".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("membership");
        var inconsistent = PlacePilotAccountingFixture.small();
        ((com.fasterxml.jackson.databind.node.ObjectNode) inconsistent.manifest.path("source_records").get(0)
                .path("provenance")).put("source_record_state", "SOURCE_REJECTED")
                .put("source_rejection_reason", "forged usable state");
        assertThatThrownBy(() -> PlacePilotSourceAccounting.approved(inconsistent.manifest, "b".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity/state");
    }

    @Test void passWithoutVerifiedReceiptOrForDifferentRunIsRejectedBeforeDatabaseAccess() {
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var rollback = org.mockito.Mockito.mock(PlacePilotRollbackService.class);
        var transactions = org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        var gates = new PlacePilotCanaryGateService(jdbc, rollback, transactions);
        var fixture = PlacePilotAccountingFixture.small();
        assertThatThrownBy(() -> gates.record(fixture.approvedRun(), true, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("verified sealed accounting");
        assertThatThrownBy(() -> gates.record(java.util.UUID.randomUUID(), true, null, null, fixture.approved))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("verified sealed accounting");
        org.mockito.Mockito.verifyNoInteractions(jdbc, rollback, transactions);
    }

    static com.fasterxml.jackson.databind.node.ObjectNode inspect(PlacePilotAccountingFixture fixture) {
        return PlacePilotSourceAccounting.inspect(fixture.approved, fixture.declared,
                fixture.sources, fixture.candidates);
    }
}
