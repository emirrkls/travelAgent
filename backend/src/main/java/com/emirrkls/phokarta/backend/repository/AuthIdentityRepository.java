package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.AuthIdentity;
import com.emirrkls.phokarta.backend.domain.model.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AuthIdentityRepository extends JpaRepository<AuthIdentity, UUID> {
    Optional<AuthIdentity> findByProviderAndProviderSubject(AuthProvider provider,
                                                            String providerSubject);

    boolean existsByProviderAndProviderSubject(AuthProvider provider, String providerSubject);
}
