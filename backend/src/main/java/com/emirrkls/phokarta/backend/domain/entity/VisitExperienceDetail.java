package com.emirrkls.phokarta.backend.domain.entity;

import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.CompanionCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.FeelingSource;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.OverallFeelingCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PracticalSignalCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.PrimaryExperienceCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.TimeOfDayCode;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.TitleSource;
import com.emirrkls.phokarta.backend.domain.model.ExperienceTaxonomy.VibeCode;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "visit_experience_details")
public class VisitExperienceDetail {
    @Id
    @Column(name = "visit_id")
    private UUID visitId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Enumerated(EnumType.STRING)
    @Column(name = "primary_experience_code", nullable = false, length = 60)
    private PrimaryExperienceCode primaryExperienceCode;

    @Column(name = "raw_experience_label", length = 120)
    private String rawExperienceLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "overall_feeling_code", nullable = false, length = 40)
    private OverallFeelingCode overallFeelingCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "feeling_source", nullable = false, length = 30)
    private FeelingSource feelingSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "companion_code", length = 20)
    private CompanionCode companionCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_of_day_code", length = 20)
    private TimeOfDayCode timeOfDayCode;

    @Column(nullable = false, length = 240)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "title_source", nullable = false, length = 20)
    private TitleSource titleSource;

    @Column(nullable = false, length = 4000)
    private String story;

    @Column(length = 1000)
    private String tip;

    @Column(name = "taxonomy_version", nullable = false)
    private int taxonomyVersion;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "visit_experience_vibes", joinColumns = @JoinColumn(name = "visit_id"))
    @OrderColumn(name = "position")
    @Column(name = "vibe_code", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private List<VibeCode> vibes = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "visit_experience_practical_signals", joinColumns = @JoinColumn(name = "visit_id"))
    @OrderColumn(name = "position")
    @Column(name = "practical_signal_code", nullable = false, length = 40)
    @Enumerated(EnumType.STRING)
    private List<PracticalSignalCode> practicalSignals = new ArrayList<>();

    protected VisitExperienceDetail() {}

    public VisitExperienceDetail(
            Visit visit,
            PrimaryExperienceCode primaryExperienceCode,
            String rawExperienceLabel,
            OverallFeelingCode overallFeelingCode,
            FeelingSource feelingSource,
            CompanionCode companionCode,
            TimeOfDayCode timeOfDayCode,
            String title,
            TitleSource titleSource,
            String story,
            String tip,
            int taxonomyVersion,
            List<VibeCode> vibes,
            List<PracticalSignalCode> practicalSignals,
            OffsetDateTime now) {
        this.visit = visit;
        this.visitId = visit.getId();
        this.primaryExperienceCode = primaryExperienceCode;
        this.rawExperienceLabel = rawExperienceLabel;
        this.overallFeelingCode = overallFeelingCode;
        this.feelingSource = feelingSource;
        this.companionCode = companionCode;
        this.timeOfDayCode = timeOfDayCode;
        this.title = title;
        this.titleSource = titleSource;
        this.story = story;
        this.tip = tip;
        this.taxonomyVersion = taxonomyVersion;
        this.vibes = new ArrayList<>(vibes);
        this.practicalSignals = new ArrayList<>(practicalSignals);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getVisitId() { return visitId; }
    public PrimaryExperienceCode getPrimaryExperienceCode() { return primaryExperienceCode; }
    public String getRawExperienceLabel() { return rawExperienceLabel; }
    public OverallFeelingCode getOverallFeelingCode() { return overallFeelingCode; }
    public FeelingSource getFeelingSource() { return feelingSource; }
    public CompanionCode getCompanionCode() { return companionCode; }
    public TimeOfDayCode getTimeOfDayCode() { return timeOfDayCode; }
    public String getTitle() { return title; }
    public TitleSource getTitleSource() { return titleSource; }
    public String getStory() { return story; }
    public String getTip() { return tip; }
    public int getTaxonomyVersion() { return taxonomyVersion; }
    public List<VibeCode> getVibes() { return List.copyOf(vibes); }
    public List<PracticalSignalCode> getPracticalSignals() { return List.copyOf(practicalSignals); }
}
