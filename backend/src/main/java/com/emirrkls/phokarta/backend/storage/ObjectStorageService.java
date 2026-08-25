package com.emirrkls.phokarta.backend.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

public interface ObjectStorageService {
    SignedRequest presignPut(String key, String contentType, long byteSize, Duration ttl);
    URI presignGet(String key, Duration ttl);
    StoredObject head(String key);
    void delete(String key);

    record SignedRequest(URI url, Map<String, String> requiredHeaders) {
        public SignedRequest {
            requiredHeaders = Map.copyOf(requiredHeaders);
        }
    }

    record StoredObject(long byteSize, String contentType, String etag) {
    }
}
