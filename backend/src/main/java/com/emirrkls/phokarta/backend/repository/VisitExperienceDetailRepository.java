package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.VisitExperienceDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VisitExperienceDetailRepository extends JpaRepository<VisitExperienceDetail, UUID> {
}
