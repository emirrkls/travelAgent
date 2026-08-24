package com.emirrkls.phokarta.backend.integration;

import com.emirrkls.phokarta.backend.api.dto.CreateVisitRequest;
import com.emirrkls.phokarta.backend.api.dto.VisitOwnerResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.service.VisitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("dev")
@Testcontainers
class VisitIdempotencyIntegrationTest {
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLACE = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    @Autowired VisitService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanMutations() {
        jdbc.update("delete from visits where client_mutation_id is not null");
    }

    @Test
    void lostResponseRetryReturnsSameVisitAndOneRow() {
        UUID mutation = UUID.randomUUID();
        VisitOwnerResponse first = service.create(USER, request(mutation, 8.5));
        VisitOwnerResponse retry = service.create(USER, request(mutation, 8.5));

        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(count(mutation)).isEqualTo(1);
    }

    @Test
    void conflictingPayloadIsRejected() {
        UUID mutation = UUID.randomUUID();
        service.create(USER, request(mutation, 8.5));

        assertThatThrownBy(() -> service.create(USER, request(mutation, 2.0)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.status().value()).isEqualTo(409));
        assertThat(count(mutation)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateDeliveryCreatesOneVisit() throws Exception {
        UUID mutation = UUID.randomUUID();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.create(USER, request(mutation, 8.5)));
            var second = executor.submit(() -> service.create(USER, request(mutation, 8.5)));
            assertThat(first.get().id()).isEqualTo(second.get().id());
        } finally {
            executor.shutdownNow();
        }
        assertThat(count(mutation)).isEqualTo(1);
    }

    private int count(UUID mutation) {
        return jdbc.queryForObject("select count(*) from visits where user_id = ? and client_mutation_id = ?",
                Integer.class, USER, mutation);
    }

    private CreateVisitRequest request(UUID mutation, double score) {
        return new CreateVisitRequest(mutation, PLACE, LocalDate.of(2026, 8, 20), score,
                List.of(), "idempotent review", "private", List.of(), Visibility.PRIVATE);
    }
}
