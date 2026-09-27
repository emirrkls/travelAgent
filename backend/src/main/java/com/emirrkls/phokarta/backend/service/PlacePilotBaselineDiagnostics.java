package com.emirrkls.phokarta.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.time.Duration;

/** Files/stdout only, never a pilot run, gate or event. Contains no payload or credentials. */
public final class PlacePilotBaselineDiagnostics {
    private PlacePilotBaselineDiagnostics() {}

    public static ObjectNode document(PlacePilotHttpProbeService.ProbeSuite suite, Duration timeout) {
        ObjectNode document = suite.toDiagnosticJson();
        document.put("version", "preimport-http-baseline-diagnostics-v1");
        document.put("timeout_ms", timeout.toMillis());
        document.put("database_mutations", 0);
        return document;
    }

    public static void emit(ObjectNode document, Path output) {
        // A full equivalent operational record is emitted before throwing/exiting, including
        // successful surfaces. The established private one-shot log retains it after process exit.
        System.out.println("PREIMPORT_HTTP_BASELINE_DIAGNOSTICS=" + document);
        if (output != null) {
            try {
                byte[] bytes = document.toPrettyString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 131072) throw new IOException("bounded artifact limit");
                // Do not overwrite existing operator artifacts, follow symlinks or create parents.
                try (FileChannel file = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) file.write(buffer);
                    file.force(true);
                }
            } catch (IOException unsafe) {
                // Never print filesystem paths or exception messages. Full diagnostics already emitted.
                throw new IllegalStateException("PREIMPORT_BASELINE_ARTIFACT_WRITE_FAILED");
            }
        }
    }

    public static final class BaselineFailure extends IllegalStateException {
        private final JsonNode diagnostics;
        public BaselineFailure(ObjectNode diagnostics) {
            super("PREIMPORT_HTTP_BASELINE_UNHEALTHY: no canary writes were attempted");
            this.diagnostics = diagnostics.deepCopy();
        }
        public JsonNode diagnostics() { return diagnostics.deepCopy(); }
    }
}
