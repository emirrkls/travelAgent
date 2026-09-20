package com.emirrkls.phokarta.backend.api.controller;

import com.emirrkls.phokarta.backend.api.dto.ConversationEntryResponse;
import com.emirrkls.phokarta.backend.api.dto.CreateConversationEntryRequest;
import com.emirrkls.phokarta.backend.api.dto.CreateConversationReplyRequest;
import com.emirrkls.phokarta.backend.api.dto.CursorPageResponse;
import com.emirrkls.phokarta.backend.api.dto.UpdateConversationEntryRequest;
import com.emirrkls.phokarta.backend.security.SecurityUtils;
import com.emirrkls.phokarta.backend.service.ExperienceConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v2")
public class ExperienceConversationV2Controller {
    private final ExperienceConversationService conversations;

    public ExperienceConversationV2Controller(ExperienceConversationService conversations) {
        this.conversations = conversations;
    }

    @Operation(summary = "Read Questions, Comments, and first-level Replies for an Experience")
    @GetMapping("/experiences/{experienceId}/conversation")
    public CursorPageResponse<ConversationEntryResponse> read(
            @PathVariable UUID experienceId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return conversations.read(experienceId, SecurityUtils.currentUserId().orElse(null), cursor, size);
    }

    @Operation(summary = "Create a Question or Comment root")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/experiences/{experienceId}/conversation")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationEntryResponse createRoot(
            @PathVariable UUID experienceId,
            @Valid @RequestBody CreateConversationEntryRequest request) {
        return conversations.createRoot(experienceId, SecurityUtils.requireCurrentUserId(), request);
    }

    @Operation(summary = "Create a first-level Reply")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/conversation/{rootId}/replies")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationEntryResponse createReply(
            @PathVariable UUID rootId,
            @Valid @RequestBody CreateConversationReplyRequest request) {
        return conversations.createReply(rootId, SecurityUtils.requireCurrentUserId(), request);
    }

    @Operation(summary = "Edit the current user's conversation entry")
    @SecurityRequirement(name = "bearerAuth")
    @PatchMapping("/conversation/{entryId}")
    public ConversationEntryResponse edit(
            @PathVariable UUID entryId,
            @Valid @RequestBody UpdateConversationEntryRequest request) {
        return conversations.edit(entryId, SecurityUtils.requireCurrentUserId(), request);
    }

    @Operation(summary = "Delete the current user's conversation entry")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/conversation/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID entryId) {
        conversations.delete(entryId, SecurityUtils.requireCurrentUserId());
    }
}
