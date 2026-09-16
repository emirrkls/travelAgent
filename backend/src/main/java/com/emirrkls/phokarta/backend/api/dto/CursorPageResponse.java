package com.emirrkls.phokarta.backend.api.dto;

import java.util.List;

public record CursorPageResponse<T>(
        List<T> items,
        String nextCursor,
        boolean hasMore) {
}
