package com.emirrkls.phokarta.backend.config;

import com.emirrkls.phokarta.backend.storage.ObjectStorageException;
import com.emirrkls.phokarta.backend.storage.ObjectStorageService;
import com.emirrkls.phokarta.backend.storage.S3ObjectStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(MediaProperties.class)
public class MediaStorageConfig {
    @Bean
    @ConditionalOnProperty(name = "phokarta.media.enabled", havingValue = "true")
    ObjectStorageService s3ObjectStorage(MediaProperties properties) {
        var clientBuilder = S3Client.builder()
                .region(Region.of(properties.region()))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyle()).build());
        var presignerBuilder = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.pathStyle()).build());
        if (hasText(properties.endpoint())) {
            URI endpoint = endpoint(properties.endpoint());
            clientBuilder.endpointOverride(endpoint);
            presignerBuilder.endpointOverride(endpoint);
        }
        if (hasText(properties.accessKey()) || hasText(properties.secretKey())) {
            if (!hasText(properties.accessKey()) || !hasText(properties.secretKey())) {
                throw new IllegalStateException("Both media access key and secret key must be configured");
            }
            var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(
                    properties.accessKey(), properties.secretKey()));
            clientBuilder.credentialsProvider(credentials);
            presignerBuilder.credentialsProvider(credentials);
        }
        return new S3ObjectStorageService(properties.bucket(), clientBuilder.build(),
                presignerBuilder.build());
    }

    @Bean
    @ConditionalOnProperty(name = "phokarta.media.enabled", havingValue = "false",
            matchIfMissing = true)
    ObjectStorageService disabledObjectStorage() {
        return new ObjectStorageService() {
            private ObjectStorageException disabled() {
                return new ObjectStorageException("Media storage is disabled", null);
            }
            @Override public SignedRequest presignPut(String key, String type, long size, Duration ttl) {
                throw disabled();
            }
            @Override public URI presignGet(String key, Duration ttl) { throw disabled(); }
            @Override public StoredObject head(String key) { throw disabled(); }
            @Override public void delete(String key) { throw disabled(); }
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static URI endpoint(String value) {
        try {
            URI endpoint = URI.create(value.trim());
            if (!endpoint.isAbsolute() || endpoint.getHost() == null
                    || (!"http".equalsIgnoreCase(endpoint.getScheme())
                    && !"https".equalsIgnoreCase(endpoint.getScheme()))) {
                throw new IllegalArgumentException();
            }
            return endpoint;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Media endpoint must be a valid absolute HTTP(S) URI", ex);
        }
    }
}
