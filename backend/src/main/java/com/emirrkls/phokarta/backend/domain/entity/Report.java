package com.emirrkls.phokarta.backend.domain.entity;

import com.emirrkls.phokarta.backend.domain.model.ReportReason;
import com.emirrkls.phokarta.backend.domain.model.ReportStatus;
import com.emirrkls.phokarta.backend.domain.model.ReportTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "reports")
public class Report {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_user_id")
    private User reporter;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private ReportTargetType targetType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_user_id")
    private User targetUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_visit_id")
    private Visit targetVisit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReportReason reason;

    @Column(length = 2000)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReportStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Report() {
    }

    public Report(UUID id, User reporter, ReportTargetType targetType, User targetUser,
                  Visit targetVisit, ReportReason reason, String details, OffsetDateTime now) {
        this.id = id;
        this.reporter = reporter;
        this.targetType = targetType;
        this.targetUser = targetUser;
        this.targetVisit = targetVisit;
        this.reason = reason;
        this.details = details;
        this.status = ReportStatus.OPEN;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public User getReporter() { return reporter; }
    public ReportTargetType getTargetType() { return targetType; }
    public User getTargetUser() { return targetUser; }
    public Visit getTargetVisit() { return targetVisit; }
    public ReportReason getReason() { return reason; }
    public String getDetails() { return details; }
    public ReportStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
