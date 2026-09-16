package com.emirrkls.phokarta.backend.repository;

import com.emirrkls.phokarta.backend.domain.entity.VisitExperienceDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface VisitExperienceDetailRepository extends JpaRepository<VisitExperienceDetail, UUID> {
    interface FeelingAggregateRow {
        String getFeelingCode();
        long getContributionCount();
    }

    @Query(value = """
            select coalesce(detail.overall_feeling_code,
                       case
                           when visit.overall_rating >= 9 then 'BAYILDIM'
                           when visit.overall_rating >= 7 then 'GUZELDI'
                           when visit.overall_rating >= 5 then 'EH_ISTE'
                           when visit.overall_rating >= 3 then 'BEKLENTIMI_KARSILAMADI'
                           else 'BIR_DAHA_TERCIH_ETMEM'
                       end) as "feelingCode",
                   count(*) as "contributionCount"
            from visits visit
            left join visit_experience_details detail on detail.visit_id = visit.id
            where visit.place_id = :placeId
              and visit.visibility in ('PUBLIC', 'FRIENDS')
            group by 1
            order by 1
            """, nativeQuery = true)
    List<FeelingAggregateRow> aggregateFeelings(@Param("placeId") UUID placeId);

    interface PracticalSignalAggregateRow {
        String getSignalCode();
        long getContributionCount();
    }

    @Query(value = """
            select signal.practical_signal_code as "signalCode",
                   count(distinct signal.visit_id) as "contributionCount"
            from visit_experience_practical_signals signal
            join visits visit on visit.id = signal.visit_id
            where visit.place_id = :placeId
              and visit.visibility in ('PUBLIC', 'FRIENDS')
            group by signal.practical_signal_code
            order by signal.practical_signal_code
            """, nativeQuery = true)
    List<PracticalSignalAggregateRow> aggregatePracticalSignals(@Param("placeId") UUID placeId);
}
