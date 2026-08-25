package com.emirrkls.phokarta.backend.service;

import com.emirrkls.phokarta.backend.config.MediaProperties;
import com.emirrkls.phokarta.backend.domain.entity.MediaAsset;
import com.emirrkls.phokarta.backend.repository.MediaAssetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Owns the short database transactions on either side of the external object delete.
 */
@Service
public class MediaCleanupClaims {
    private final MediaAssetRepository assets;
    private final MediaProperties properties;

    public MediaCleanupClaims(MediaAssetRepository assets, MediaProperties properties) {
        this.assets = assets;
        this.properties = properties;
    }

    @Transactional
    public List<Target> claimExpired(OffsetDateTime now) {
        List<MediaAsset> claimed = assets.findCleanupCandidates(
                now, now.minus(properties.cleanupInterval()), properties.cleanupBatchSize());
        claimed.forEach(asset -> asset.markDeleting(now));
        assets.flush();
        return claimed.stream()
                .map(asset -> new Target(asset.getId(), asset.getStorageKey()))
                .toList();
    }

    @Transactional
    public boolean completeDeletion(UUID id) {
        return assets.deleteDeletingById(id) == 1;
    }

    public record Target(UUID id, String storageKey) {
    }
}
