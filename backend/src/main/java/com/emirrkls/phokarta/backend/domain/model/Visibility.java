package com.emirrkls.phokarta.backend.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Visit (and Collection) audience.
 *
 * <ul>
 *   <li>{@link #PUBLIC} — community-readable and friend-readable</li>
 *   <li>{@link #FRIENDS} — mutual-friend-readable only; excluded from community</li>
 *   <li>{@link #PRIVATE} — owner-only</li>
 * </ul>
 */
public enum Visibility {
    PRIVATE,
    FRIENDS,
    PUBLIC;

    /** Visits mutual friends may see on friend-scoped surfaces. */
    public static final Set<Visibility> FRIEND_READABLE = EnumSet.of(PUBLIC, FRIENDS);

    /** Visits that contribute to community discovery and community feeds. */
    public static final Set<Visibility> COMMUNITY_READABLE = EnumSet.of(PUBLIC);

    public boolean isFriendReadable() {
        return this == PUBLIC || this == FRIENDS;
    }

    public boolean isCommunityReadable() {
        return this == PUBLIC;
    }
}
