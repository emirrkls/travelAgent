package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.CreateReportRequest;
import com.emirrkls.phokarta.backend.api.dto.ReportResponse;
import com.emirrkls.phokarta.backend.security.SafetyRateLimiter;
import com.emirrkls.phokarta.backend.security.SecurityUtils;
import com.emirrkls.phokarta.backend.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports")
@SecurityRequirement(name = "bearerAuth")
public class ReportController {
    private final ReportService reports;
    private final SafetyRateLimiter rateLimiter;

    public ReportController(ReportService reports, SafetyRateLimiter rateLimiter) {
        this.reports = reports;
        this.rateLimiter = rateLimiter;
    }

    @Operation(summary = "Submit an abuse report",
            description = "Authenticated. Reports a USER or VISIT. Duplicate OPEN reports "
                    + "return the existing report. Does not hide content or notify the target.")
    @PostMapping
    public ResponseEntity<ReportResponse> create(@Valid @RequestBody CreateReportRequest request) {
        var userId = SecurityUtils.requireCurrentUserId();
        rateLimiter.checkReport(userId);
        ReportService.SubmitResult result = reports.submit(userId, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.response());
    }
}
