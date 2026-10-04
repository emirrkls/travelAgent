package com.emirrkls.phokarta.backend.operations;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/** Filesystem only; strict independent reopen, bounded reads, no overwrite or symlink traversal. */
public final class PlacePilotPrivateArtifacts {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final ObjectMapper READER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private PlacePilotPrivateArtifacts() {}

    public record Read(JsonNode document, String sha256, long bytes) {}

    public static void directory(Path path) throws IOException {
        require(path.isAbsolute());
        Path absolute = path.normalize();
        for (Path p = absolute; p != null; p = p.getParent()) require(!Files.isSymbolicLink(p));
        require(Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS));
        if (Files.getFileStore(absolute).supportsFileAttributeView("posix")) {
            var mode = Files.getPosixFilePermissions(absolute);
            require(!mode.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)
                    && !mode.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)
                    && !mode.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ)
                    && !mode.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ));
        }
    }

    public static Read read(Path path, String expectedHash) throws IOException {
        directory(path.toAbsolutePath().normalize().getParent());
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS));
        require(Files.size(path) > 0 && Files.size(path) <= MAX_BYTES);
        byte[] bytes;
        try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(MAX_BYTES + 1);
        }
        require(bytes.length > 0 && bytes.length <= MAX_BYTES);
        String hash = hash(bytes);
        if (expectedHash != null) require(expectedHash.matches("[0-9a-f]{64}") && hash.equals(expectedHash));
        return new Read(READER.readTree(bytes), hash, bytes.length);
    }

    public static Read write(Path path, JsonNode document) throws IOException {
        directory(path.toAbsolutePath().normalize().getParent());
        require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS));
        byte[] bytes = READER.writerWithDefaultPrettyPrinter().writeValueAsBytes(document);
        require(bytes.length <= MAX_BYTES);
        Path temporary = Files.createTempFile(path.getParent(), ".private-", ".tmp");
        if (Files.getFileStore(temporary).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
        try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) out.write(buffer);
            out.force(true);
        }
        // Link publication is atomic and fails if an existing receipt exists (never replace).
        Files.createLink(path, temporary);
        Files.delete(temporary);
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            try (FileChannel dir = FileChannel.open(path.getParent(), StandardOpenOption.READ)) { dir.force(true); }
        }
        Read read = read(path, hash(bytes));
        require(READER.readTree(bytes).equals(read.document()));
        return read;
    }

    public static void fields(JsonNode value, Set<String> allowed) throws IOException {
        require(value.isObject());
        var actual = new java.util.HashSet<String>();
        value.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(allowed));
    }

    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static void require(boolean condition) throws IOException {
        if (!condition) throw new IOException("PRIVATE_OPERATIONS_EVIDENCE_INVALID");
    }
}
