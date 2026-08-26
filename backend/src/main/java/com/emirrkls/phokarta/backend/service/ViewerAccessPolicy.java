package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.domain.entity.Collection;
import com.emirrkls.phokarta.backend.domain.entity.Visit;
import com.emirrkls.phokarta.backend.domain.model.Visibility;
import com.emirrkls.phokarta.backend.repository.UserFollowRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Central viewer/author relationship checks. List surfaces still filter in SQL;
 * this policy is for single-resource authorization (profile, Visit, media, collection)
 * and report eligibility.
 */
@Component
public class ViewerAccessPolicy {
    private final BlockService blocks;
    private final UserFollowRepository follows;

    public ViewerAccessPolicy(BlockService blocks, UserFollowRepository follows) {
        this.blocks = blocks;
        this.follows = follows;
    }

    public boolean isBlockSeparated(UUID viewerId, UUID otherUserId) {
        return blocks.isBlockedEitherDirection(viewerId, otherUserId);
    }

    /**
     * Direct Visit fetch. Blocked authenticated viewers are denied even for PUBLIC.
     * Anonymous PUBLIC remains visible because no viewer identity exists.
     */
    public boolean canViewVisit(Visit visit, UUID viewerId) {
        UUID ownerId = visit.getUser().getId();
        if (viewerId != null && ownerId.equals(viewerId)) {
            return true;
        }
        if (viewerId != null && blocks.isBlockedEitherDirection(viewerId, ownerId)) {
            return false;
        }
        return switch (visit.getVisibility()) {
            case PUBLIC -> true;
            case FRIENDS -> viewerId != null && follows.areFriends(viewerId, ownerId);
            case PRIVATE -> false;
        };
    }

    public boolean canViewProfile(UUID targetId, UUID viewerId) {
        if (viewerId == null || viewerId.equals(targetId)) {
            return true;
        }
        return !blocks.isBlockedEitherDirection(viewerId, targetId);
    }

    public boolean canViewCollection(Collection collection, UUID viewerId) {
        UUID ownerId = collection.getUser().getId();
        if (viewerId != null && ownerId.equals(viewerId)) {
            return true;
        }
        if (viewerId != null && blocks.isBlockedEitherDirection(viewerId, ownerId)) {
            return false;
        }
        return switch (collection.getVisibility()) {
            case PUBLIC -> true;
            case PRIVATE -> false;
            case FRIENDS -> viewerId != null && follows.areFriends(viewerId, ownerId);
        };
    }

    /**
     * Reporting a Visit does not require friendship for PUBLIC, and block does not
     * prevent reporting PUBLIC content. FRIENDS still requires current visibility.
     * PRIVATE is owner-only and therefore not reportable by others.
     */
    public boolean canReportVisit(Visit visit, UUID reporterId) {
        UUID ownerId = visit.getUser().getId();
        if (ownerId.equals(reporterId)) {
            return false;
        }
        return switch (visit.getVisibility()) {
            case PUBLIC -> true;
            case FRIENDS -> follows.areFriends(reporterId, ownerId);
            case PRIVATE -> false;
        };
    }

    public boolean canFollow(UUID followerId, UUID targetId) {
        return !blocks.isBlockedEitherDirection(followerId, targetId);
    }
}
