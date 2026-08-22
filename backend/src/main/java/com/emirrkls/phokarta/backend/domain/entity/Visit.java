package com.emirrkls.phokarta.backend.domain.entity;

import com.emirrkls.phokarta.backend.domain.model.VerificationStatus;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "visits")
public class Visit {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_id", nullable = false)
    private Place place;

    @Column(name = "visited_at", nullable = false)
    private LocalDate visitedAt;

    @Column(name = "overall_rating", nullable = false)
    private double overallRating;

    @Column(name = "public_review", nullable = false, length = 4000)
    private String publicReview;

    @Column(name = "private_memory", nullable = false, length = 4000)
    private String privateMemory;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private List<String> photos = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Visibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 30)
    private VerificationStatus verificationStatus;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Visit() {
    }

    public Visit(UUID id, User user, Place place, LocalDate visitedAt, double overallRating,
                 String publicReview, String privateMemory, List<String> photos,
                 Visibility visibility, VerificationStatus verificationStatus, OffsetDateTime now) {
        this.id = id;
        this.user = user;
        this.place = place;
        this.visitedAt = visitedAt;
        this.overallRating = overallRating;
        this.publicReview = publicReview;
        this.privateMemory = privateMemory;
        this.photos = new ArrayList<>(photos);
        this.visibility = visibility;
        this.verificationStatus = verificationStatus;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public Place getPlace() { return place; }
    public LocalDate getVisitedAt() { return visitedAt; }
    public double getOverallRating() { return overallRating; }
    public String getPublicReview() { return publicReview; }
    public String getPrivateMemory() { return privateMemory; }
    public List<String> getPhotos() { return List.copyOf(photos); }
    public Visibility getVisibility() { return visibility; }
    public VerificationStatus getVerificationStatus() { return verificationStatus; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
