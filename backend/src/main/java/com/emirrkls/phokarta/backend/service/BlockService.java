package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.api.dto.BlockedUserResponse;
import com.emirrkls.phokarta.backend.api.dto.PageResponse;
import com.emirrkls.phokarta.backend.api.error.ApiException;
import com.emirrkls.phokarta.backend.domain.entity.User;
import com.emirrkls.phokarta.backend.domain.entity.UserBlock;
import com.emirrkls.phokarta.backend.observability.ApplicationMetrics;
import com.emirrkls.phokarta.backend.repository.UserBlockRepository;
import com.emirrkls.phokarta.backend.repository.UserFollowRepository;
import com.emirrkls.phokarta.backend.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class BlockService {
    private final UserRepository users;
    private final UserBlockRepository blocks;
    private final UserFollowRepository follows;
    private final ApplicationMetrics metrics;

    public BlockService(UserRepository users, UserBlockRepository blocks,
                        UserFollowRepository follows, ApplicationMetrics metrics) {
        this.users = users;
        this.blocks = blocks;
        this.follows = follows;
        this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public boolean isBlockedEitherDirection(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        return blocks.existsEitherDirection(a, b);
    }

    @Transactional
    public void block(UUID blockerId, UUID targetId) {
        if (blockerId.equals(targetId)) {
            throw ApiException.badRequest("CANNOT_BLOCK_SELF", "You cannot block yourself");
        }
        if (!users.existsById(targetId)) {
            throw ApiException.notFound("User", targetId);
        }
        int inserted = blocks.insertIfAbsent(blockerId, targetId, OffsetDateTime.now(ZoneOffset.UTC));
        follows.deleteEdgesBetween(blockerId, targetId);
        metrics.blockOperation("block", inserted > 0 ? "created" : "idempotent");
    }

    @Transactional
    public void unblock(UUID blockerId, UUID targetId) {
        if (blockerId.equals(targetId)) {
            throw ApiException.badRequest("CANNOT_BLOCK_SELF", "You cannot unblock yourself");
        }
        if (!users.existsById(targetId)) {
            throw ApiException.notFound("User", targetId);
        }
        int removed = blocks.deleteBlock(blockerId, targetId);
        metrics.blockOperation("unblock", removed > 0 ? "removed" : "idempotent");
    }

    @Transactional(readOnly = true)
    public PageResponse<BlockedUserResponse> listBlocked(UUID blockerId, int page, int size) {
        Page<UserBlock> result = blocks.findBlockedBy(blockerId, PageRequest.of(page, size));
        return PageResponse.from(result, row -> {
            User blocked = row.getBlocked();
            return new BlockedUserResponse(
                    blocked.getId(),
                    blocked.getUsername(),
                    blocked.getDisplayName(),
                    blocked.getAvatarUrl(),
                    row.getCreatedAt());
        });
    }
}
