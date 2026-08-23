package com.emirrkls.phokarta.backend.api.dto;

public record RelationshipStateResponse(
        boolean isFollowing,
        boolean followsYou,
        boolean isFriend
) {
    public static RelationshipStateResponse of(boolean isFollowing, boolean followsYou) {
        return new RelationshipStateResponse(isFollowing, followsYou, isFollowing && followsYou);
    }
}
