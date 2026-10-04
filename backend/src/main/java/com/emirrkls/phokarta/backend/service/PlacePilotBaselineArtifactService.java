package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Private filesystem-only preimport gate. Never creates a database run, receipt, gate or event. */
@Service
public class PlacePilotBaselineArtifactService {
    static final int MAX_BYTES = 131072;
    static final String FILENAME = "PREIMPORT_HTTP_BASELINE_DIAGNOSTICS.json";
    static final String VERSION = "preimport-http-baseline-diagnostics-v1";
    static final Set<String> SURFACES = Set.of("health", "search", "map_nearby", "map_bounds", "place_detail");
    private final ObjectMapper reader;
    private final FileAccess files;

    @Autowired
    public PlacePilotBaselineArtifactService(ObjectMapper mapper) {
        this(mapper, new FileAccess());
    }

    PlacePilotBaselineArtifactService(ObjectMapper mapper, FileAccess files) {
        this.reader = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.files = files;
    }

    public VerifiedArtifact persistAndVerify(
            Path privateDirectory, PlacePilotHttpProbeService.ProbeSuite suite,
            Identity identity, Duration timeout
    ) {
        ObjectNode document = document(suite, identity, timeout);
        // Preserve the existing detailed FAIL record even if the filesystem gate itself fails.
        if (!suite.passed()) PlacePilotBaselineDiagnostics.emit(document, null);
        try {
            verifyDocument(document, identity, suite.passed());
            byte[] bytes = document.toPrettyString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) throw new IOException();
            files.requirePrivateDirectory(privateDirectory);
            Path root = privateDirectory.toAbsolutePath().normalize();
            Path runDirectory = root.resolve(identity.runId().toString());
            files.createPrivateDirectory(runDirectory, true);
            Path executionDirectory = runDirectory.resolve(identity.executionId().toString());
            files.createPrivateDirectory(executionDirectory, false);
            Path finalized = executionDirectory.resolve(FILENAME);
            Path temporary = files.createTemporary(executionDirectory);
            // A failed write/force/close can leave only a private temp, never the final filename.
            try (WriteHandle output = files.openTemporary(temporary)) {
                output.write(bytes);
                output.force();
            }
            files.finalizeAtomic(temporary, finalized);
            files.forceDirectory(executionDirectory);
            // Independent reopen of the finalized filesystem object, not the written JsonNode.
            byte[] persisted = files.readFinal(finalized);
            JsonNode readBack = reader.readTree(persisted);
            verifyDocument(readBack, identity, suite.passed());
            String actualHash = sha256(persisted);
            if (!actualHash.equals(sha256(bytes)) || !readBack.equals(reader.readTree(bytes))) throw new IOException();
            VerifiedArtifact result = new VerifiedArtifact(identity, finalized, actualHash,
                    persisted.length, suite.passed() ? "PASS" : "FAIL");
            // This record refers only to finalized, independently verified content.
            if (suite.passed()) PlacePilotBaselineDiagnostics.emit((ObjectNode) readBack, null);
            System.out.println("PREIMPORT_HTTP_BASELINE_ARTIFACT_VERIFIED=" + result.toJson());
            return result;
        } catch (IOException | RuntimeException unsafe) {
            // No path, input value, payload, environment or filesystem exception text leaks.
            throw new IllegalStateException("PREIMPORT_BASELINE_ARTIFACT_GATE_FAILED");
        }
    }

    static ObjectNode document(PlacePilotHttpProbeService.ProbeSuite suite, Identity identity, Duration timeout) {
        ObjectNode document = PlacePilotBaselineDiagnostics.document(suite, timeout);
        document.put("baseline_execution_id", identity.executionId().toString());
        document.put("run_id", identity.runId().toString());
        document.put("manifest_hash", identity.manifestHash());
        document.put("envelope_sha256", identity.envelopeSha256());
        document.put("validation_method", identity.validationMethod());
        document.put("started_at", identity.startedAt().toString());
        document.put("completed_at", identity.completedAt().toString());
        return document;
    }

    private void verifyDocument(JsonNode document, Identity expected, boolean expectedPass) throws IOException {
        require(document != null && document.isObject());
        require(VERSION.equals(text(document, "version")));
        require(expected.executionId().toString().equals(text(document, "baseline_execution_id")));
        require(expected.runId().toString().equals(text(document, "run_id")));
        require(expected.manifestHash().equals(text(document, "manifest_hash")));
        require(expected.envelopeSha256().equals(text(document, "envelope_sha256")));
        require(expected.validationMethod().equals(text(document, "validation_method")));
        require(expected.startedAt().toString().equals(text(document, "started_at")));
        require(expected.completedAt().toString().equals(text(document, "completed_at")));
        require(!expected.startedAt().isAfter(expected.completedAt()));
        require(document.path("timeout_ms").isIntegralNumber() && document.path("timeout_ms").asLong() == 5000);
        require(document.path("database_mutations").isIntegralNumber() && document.path("database_mutations").asInt() == 0);
        require(document.path("request_count").isIntegralNumber() && document.path("request_count").asInt() == 25);
        require(document.path("passed").isBoolean() && document.path("passed").asBoolean() == expectedPass);
        require((expectedPass ? "PASS" : "FAIL").equals(text(document, "overall")));
        JsonNode surfaces = document.path("surfaces");
        require(surfaces.isObject() && fields(surfaces).equals(SURFACES));
        LinkedHashMap<String, PlacePilotHttpProbeService.SurfaceResult> reconstructed = new LinkedHashMap<>();
        Set<String> probeFields = Set.of("surface", "sample_index", "method", "path_template", "started_at",
                "duration_ms", "http_status", "timeout", "transport_result", "response_validation", "failure_category");
        for (String name : SURFACES) {
            JsonNode surface = surfaces.path(name);
            JsonNode probes = surface.path("probes");
            require(probes.isArray() && probes.size() == 5);
            List<PlacePilotHttpProbeService.ProbeDiagnostic> raw = new ArrayList<>();
            Set<Integer> sampleIds = new HashSet<>();
            for (JsonNode probe : probes) {
                require(probe.isObject() && fields(probe).equals(probeFields));
                require(name.equals(text(probe, "surface")) && "GET".equals(text(probe, "method")));
                require(PlacePilotHttpProbeService.pathTemplate(name).equals(text(probe, "path_template")));
                require(probe.path("sample_index").isIntegralNumber());
                int index = probe.path("sample_index").asInt();
                require(index >= 1 && index <= 5 && sampleIds.add(index));
                Instant started = Instant.parse(text(probe, "started_at"));
                double duration = probe.path("duration_ms").asDouble(Double.NaN);
                require(probe.path("duration_ms").isNumber() && Double.isFinite(duration) && duration >= 0);
                require(probe.path("timeout").isBoolean());
                boolean timedOut = probe.path("timeout").asBoolean();
                JsonNode statusNode = probe.path("http_status");
                require(statusNode.isNull() || statusNode.isIntegralNumber());
                Integer status = statusNode.isNull() ? null : statusNode.asInt();
                require(status == null || status >= 100 && status <= 599);
                JsonNode failureNode = probe.path("failure_category");
                require(failureNode.isNull() || failureNode.isTextual());
                String failure = failureNode.isNull() ? null : failureNode.asText();
                String transport = text(probe, "transport_result");
                String validation = text(probe, "response_validation");
                require(transport.matches("[A-Z][A-Z0-9_]{0,63}") && validation.matches("[A-Z][A-Z0-9_]{0,63}"));
                require(failure == null || failure.matches("[A-Z][A-Z0-9_]{0,63}"));
                if (failure == null) {
                    require(status != null && status >= 200 && status < 300 && !timedOut
                            && "RESPONSE_RECEIVED".equals(transport) && "VALID".equals(validation));
                }
                if (expectedPass) require(failure == null && !timedOut);
                raw.add(new PlacePilotHttpProbeService.ProbeDiagnostic(name, index, "GET",
                        PlacePilotHttpProbeService.pathTemplate(name), started, duration, status,
                        timedOut, transport, validation, failure));
            }
            // Preserve sample order as well as uniqueness/exhaustiveness.
            require(raw.stream().map(PlacePilotHttpProbeService.ProbeDiagnostic::sampleIndex).toList()
                    .equals(List.of(1, 2, 3, 4, 5)));
            var rebuilt = PlacePilotHttpProbeService.SurfaceResult.from(raw);
            ObjectNode summary = ((ObjectNode) surface).deepCopy();
            summary.remove("probes");
            require(reader.readTree(summary.toString()).equals(reader.readTree(rebuilt.toJson().toString())));
            reconstructed.put(name, rebuilt);
        }
        var rebuiltSuite = PlacePilotHttpProbeService.ProbeSuite.from(reconstructed).toDiagnosticJson();
        require(rebuiltSuite.path("request_count").equals(document.path("request_count")));
        require(rebuiltSuite.path("error_count").equals(document.path("error_count")));
        require(rebuiltSuite.path("error_rate").equals(document.path("error_rate")));
        require(rebuiltSuite.path("passed").equals(document.path("passed")));
        require(rebuiltSuite.path("overall").equals(document.path("overall")));
        require(rebuiltSuite.path("failing_surfaces").equals(document.path("failing_surfaces")));
    }

    private static Set<String> fields(JsonNode object) {
        Set<String> result = new HashSet<>();
        object.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static String text(JsonNode object, String key) throws IOException {
        require(object.path(key).isTextual());
        return object.path(key).asText();
    }

    private static void require(boolean valid) throws IOException { if (!valid) throw new IOException(); }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE");
        }
    }

    public record Identity(UUID executionId, UUID runId, String manifestHash, String envelopeSha256,
                           String validationMethod, Instant startedAt, Instant completedAt) {
        public Identity {
            if (executionId == null || runId == null || manifestHash == null || envelopeSha256 == null
                    || !manifestHash.matches("[0-9a-f]{64}") || !envelopeSha256.matches("[0-9a-f]{64}")
                    || validationMethod == null
                    || !java.util.Set.of(PlacePilotV3Policy.V2, PlacePilotV3Policy.V3).contains(validationMethod)
                    || startedAt == null || completedAt == null || startedAt.isAfter(completedAt)) {
                throw new IllegalArgumentException("PREIMPORT_BASELINE_ARTIFACT_IDENTITY_INVALID");
            }
        }
    }

    public record VerifiedArtifact(Identity identity, Path path, String sha256, int byteCount, String overall) {
        public ObjectNode toJson() {
            ObjectNode value = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            value.put("version", VERSION).put("baseline_execution_id", identity.executionId().toString())
                    .put("run_id", identity.runId().toString()).put("manifest_hash", identity.manifestHash())
                    .put("envelope_sha256", identity.envelopeSha256()).put("validation_method", identity.validationMethod())
                    .put("artifact_relative_path", identity.runId() + "/" + identity.executionId() + "/" + FILENAME)
                    .put("artifact_sha256", sha256).put("byte_count", byteCount)
                    .put("overall", overall).put("surface_count", 5).put("probe_count", 25)
                    .put("read_back_verified", true);
            return value;
        }
    }

    interface WriteHandle extends AutoCloseable {
        void write(byte[] bytes) throws IOException;
        void force() throws IOException;
        @Override void close() throws IOException;
    }

    /** Package-private fault-injection seam, not an alternative operational entry point. */
    static class FileAccess {
        void requirePrivateDirectory(Path directory) throws IOException {
            require(directory != null && directory.isAbsolute() && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS));
            if (Files.getFileAttributeView(directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
                var permissions = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
                require(PosixFilePermissions.fromString("rwx------").containsAll(permissions));
            }
        }

        void createPrivateDirectory(Path directory, boolean allowExisting) throws IOException {
            try {
                if (Files.getFileAttributeView(directory.getParent(), PosixFileAttributeView.class) != null) {
                    Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createDirectory(directory); // Windows test roots inherit their private owner ACL.
                }
                forceDirectory(directory.getParent());
            } catch (FileAlreadyExistsException existing) {
                if (!allowExisting) throw existing;
            }
            requirePrivateDirectory(directory);
        }

        Path createTemporary(Path directory) throws IOException {
            if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) {
                return Files.createTempFile(directory, ".baseline-", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            }
            return Files.createTempFile(directory, ".baseline-", ".tmp");
        }

        WriteHandle openTemporary(Path path) throws IOException {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            return new WriteHandle() {
                public void write(byte[] bytes) throws IOException {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                }
                public void force() throws IOException { channel.force(true); }
                public void close() throws IOException { channel.close(); }
            };
        }

        void finalizeAtomic(Path temporary, Path finalized) throws IOException {
            require(!Files.exists(finalized, LinkOption.NOFOLLOW_LINKS));
            // No non-atomic fallback. Both paths are in the freshly created private execution dir.
            Files.move(temporary, finalized, StandardCopyOption.ATOMIC_MOVE);
        }

        void forceDirectory(Path directory) throws IOException {
            if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) {
                try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) {
                    parent.force(true);
                }
            }
        }

        byte[] readFinal(Path path) throws IOException {
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= MAX_BYTES);
            if (Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
                require(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                        .equals(PosixFilePermissions.fromString("rw-------")));
            }
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                require(bytes.length <= MAX_BYTES);
                return bytes;
            }
        }
    }
}
