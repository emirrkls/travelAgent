package com.emirrkls.phokarta.backend.storage;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class S3ObjectStorageService implements ObjectStorageService {
    private final String bucket;
    private final S3Client client;
    private final S3Presigner presigner;

    public S3ObjectStorageService(String bucket, S3Client client, S3Presigner presigner) {
        this.bucket = bucket;
        this.client = client;
        this.presigner = presigner;
    }

    @Override
    public SignedRequest presignPut(String key, String contentType, long byteSize, Duration ttl) {
        try {
            PutObjectRequest objectRequest = PutObjectRequest.builder()
                    .bucket(bucket).key(key).contentType(contentType).contentLength(byteSize).build();
            var signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(ttl).putObjectRequest(objectRequest).build());
            Map<String, String> headers = new LinkedHashMap<>();
            signed.signedHeaders().forEach((name, values) -> {
                if (!"host".equalsIgnoreCase(name) && !values.isEmpty()) {
                    headers.put(name, values.getFirst());
                }
            });
            return new SignedRequest(signed.url().toURI(), headers);
        } catch (Exception ex) {
            throw new ObjectStorageException("Unable to create upload authorization", ex);
        }
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        try {
            var signed = presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(ttl)
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .build());
            return signed.url().toURI();
        } catch (Exception ex) {
            throw new ObjectStorageException("Unable to create read authorization", ex);
        }
    }

    @Override
    public StoredObject head(String key) {
        try {
            var result = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return new StoredObject(result.contentLength(), result.contentType(), result.eTag());
        } catch (NoSuchKeyException ex) {
            return null;
        } catch (Exception ex) {
            if (ex instanceof software.amazon.awssdk.services.s3.model.S3Exception s3
                    && s3.statusCode() == 404) {
                return null;
            }
            throw new ObjectStorageException("Unable to inspect stored object", ex);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException ex) {
            // S3 DeleteObject is normally idempotent; treat an explicit miss as success.
        } catch (Exception ex) {
            if (ex instanceof software.amazon.awssdk.services.s3.model.S3Exception s3
                    && s3.statusCode() == 404) {
                return;
            }
            throw new ObjectStorageException("Unable to delete stored object", ex);
        }
    }
}
