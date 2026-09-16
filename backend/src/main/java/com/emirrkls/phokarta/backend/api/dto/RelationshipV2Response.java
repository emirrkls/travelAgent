package com.emirrkls.phokarta.backend.api.dto;

public record RelationshipV2Response(
        State state,
        boolean followsYou,
        boolean canFollow,
        boolean canCancelRequest) {
    public enum State {
        NONE,
        REQUEST_PENDING,
        FOLLOWING,
        FRIENDS,
        UNAVAILABLE
    }
}
