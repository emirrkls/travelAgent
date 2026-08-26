package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.UserPolicyAcceptance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface UserPolicyAcceptanceRepository extends JpaRepository<UserPolicyAcceptance, UUID> {

    boolean existsByUser_IdAndPolicyVersion(UUID userId, String policyVersion);

    Optional<UserPolicyAcceptance> findByUser_IdAndPolicyVersion(UUID userId, String policyVersion);

    Optional<UserPolicyAcceptance> findTopByUser_IdOrderByAcceptedAtDesc(UUID userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into user_policy_acceptances (id, user_id, policy_version, accepted_at)
            values (:id, :userId, :policyVersion, :acceptedAt)
            on conflict (user_id, policy_version) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("userId") UUID userId,
                       @Param("policyVersion") String policyVersion,
                       @Param("acceptedAt") OffsetDateTime acceptedAt);
}
