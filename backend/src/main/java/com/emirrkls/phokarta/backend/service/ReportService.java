package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.CreateReportRequest;
import com.emirrkls.phokarta.backend.api.dto.ReportResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.Report;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.domain.model.ReportTargetType;
import com.emirrkls.phokarta.backend.observability.ApplicationMetrics;
import com.emirrkls.phokarta.backend.repository.ReportRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import com.emirrkls.phokarta.backend.repository.VisitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReportService {
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ReportRepository reports;
    private final UserRepository users;
    private final VisitRepository visits;
    private final ViewerAccessPolicy access;
    private final ApplicationMetrics metrics;

    public ReportService(ReportRepository reports, UserRepository users, VisitRepository visits,
                         ViewerAccessPolicy access, ApplicationMetrics metrics) {
        this.reports = reports;
        this.users = users;
        this.visits = visits;
        this.access = access;
        this.metrics = metrics;
    }

    @Transactional
    public SubmitResult submit(UUID reporterId, CreateReportRequest request) {
        User reporter = users.findById(reporterId)
                .orElseThrow(() -> ApiException.notFound("User", reporterId));
        String details = normalizeDetails(request.details());
        return switch (request.targetType()) {
            case USER -> submitUser(reporter, request.targetId(), request, details);
            case VISIT -> submitVisit(reporter, request.targetId(), request, details);
        };
    }

    private SubmitResult submitUser(User reporter, UUID targetUserId,
                                    CreateReportRequest request, String details) {
        if (reporter.getId().equals(targetUserId)) {
            throw ApiException.badRequest("CANNOT_REPORT_SELF", "You cannot report yourself");
        }
        User target = users.findById(targetUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "REPORT_TARGET_NOT_FOUND", "Report target was not found"));
        Optional<Report> existing = reports.findOpenUserReport(reporter.getId(), targetUserId);
        if (existing.isPresent()) {
            return SubmitResult.duplicate(toResponse(existing.get()));
        }
        Report saved = reports.save(new Report(UUID.randomUUID(), reporter, ReportTargetType.USER,
                target, null, request.reason(), details, OffsetDateTime.now(ZoneOffset.UTC)));
        metrics.reportCreated(ReportTargetType.USER.name(), request.reason().name());
        log.info("report created reportId={} targetType={} reason={}",
                saved.getId(), saved.getTargetType(), saved.getReason());
        return SubmitResult.created(toResponse(saved));
    }

    private SubmitResult submitVisit(User reporter, UUID visitId,
                                     CreateReportRequest request, String details) {
        Visit visit = visits.findDetailedById(visitId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "REPORT_TARGET_NOT_FOUND", "Report target was not found"));
        UUID ownerId = visit.getUser().getId();
        if (ownerId.equals(reporter.getId())) {
            throw ApiException.badRequest("CANNOT_REPORT_SELF", "You cannot report your own visit");
        }
        if (!access.canReportVisit(visit, reporter.getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "REPORT_TARGET_NOT_FOUND", "Report target was not found");
        }
        Optional<Report> existing = reports.findOpenVisitReport(reporter.getId(), visitId);
        if (existing.isPresent()) {
            return SubmitResult.duplicate(toResponse(existing.get()));
        }
        Report saved = reports.save(new Report(UUID.randomUUID(), reporter, ReportTargetType.VISIT,
                visit.getUser(), visit, request.reason(), details, OffsetDateTime.now(ZoneOffset.UTC)));
        metrics.reportCreated(ReportTargetType.VISIT.name(), request.reason().name());
        log.info("report created reportId={} targetType={} reason={}",
                saved.getId(), saved.getTargetType(), saved.getReason());
        return SubmitResult.created(toResponse(saved));
    }

    private static String normalizeDetails(String details) {
        if (details == null) {
            return null;
        }
        String trimmed = details.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ReportResponse toResponse(Report report) {
        return new ReportResponse(report.getId(), report.getTargetType(), report.getReason(),
                report.getStatus(), report.getCreatedAt());
    }

    public record SubmitResult(ReportResponse response, boolean created) {
        static SubmitResult created(ReportResponse response) {
            return new SubmitResult(response, true);
        }

        static SubmitResult duplicate(ReportResponse response) {
            return new SubmitResult(response, false);
        }
    }
}
