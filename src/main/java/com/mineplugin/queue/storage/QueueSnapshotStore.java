package com.mineplugin.queue.storage;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** One owner, versioned/checksummed snapshot, forced temporary file and atomic replacement. */
public final class QueueSnapshotStore implements AutoCloseable {
    private static final int MAGIC = 0x53515132, VERSION = 1;
    public static final int MAX_ENTRIES = 100_000;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final Path file;
    private final FileChannel ownership;
    private final FileLock lock;

    public QueueSnapshotStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        file = directory.resolve("queue-state.bin");
        ownership = FileChannel.open(directory.resolve("queue-state.lock"), CREATE, WRITE);
        FileLock acquired;
        try {
            acquired = ownership.tryLock();
            if (acquired == null) throw new IOException("Queue snapshot already owned by another instance");
        } catch (IOException | RuntimeException error) {
            ownership.close();
            throw new IOException("Cannot acquire queue snapshot ownership", error);
        }
        lock = acquired;
    }

    public List<QueueEntry> read() throws IOException {
        if (!Files.exists(file)) return List.of();
        if (Files.size(file) > MAX_BYTES) throw new IOException("Queue snapshot exceeds size limit");
        byte[] bytes;
        // Bounded even if a file grows between the size check and open.
        try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES || bytes.length < 44) throw new IOException("Invalid snapshot size");
        byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(digest(payload), Arrays.copyOfRange(bytes, payload.length, bytes.length)))
            throw new IOException("Queue snapshot checksum mismatch");
        try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("Unsupported queue snapshot schema");
            int count = input.readInt();
            if (count < 0 || count > MAX_ENTRIES) throw new IOException("Invalid snapshot entry count");
            List<QueueEntry> entries = new ArrayList<>(count);
            Set<UUID> ids = new HashSet<>(); Set<Long> sequences = new HashSet<>();
            for (int i = 0; i < count; i++) {
                var entry = new QueueEntry(new UUID(input.readLong(), input.readLong()), input.readUTF(),
                        input.readLong(), input.readLong(), input.readLong());
                validate(entry);
                if (!ids.add(entry.uuid()) || !sequences.add(entry.sequence())) throw new IOException("Duplicate snapshot identity/order");
                entries.add(entry);
            }
            if (input.available() != 0) throw new IOException("Trailing snapshot data");
            return List.copyOf(entries);
        }
    }

    public void write(List<QueueEntry> entries) throws IOException {
        if (entries.size() > MAX_ENTRIES) throw new IOException("Queue exceeds persistence entry limit");
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC); output.writeInt(VERSION); output.writeInt(entries.size());
            Set<UUID> ids = new HashSet<>(); Set<Long> sequences = new HashSet<>();
            for (var entry : entries) {
                validate(entry);
                if (!ids.add(entry.uuid()) || !sequences.add(entry.sequence())) throw new IOException("Duplicate snapshot identity/order");
                output.writeLong(entry.uuid().getMostSignificantBits()); output.writeLong(entry.uuid().getLeastSignificantBits());
                output.writeUTF(entry.name()); output.writeLong(entry.sequence());
                output.writeLong(entry.firstJoinedAt()); output.writeLong(entry.lastPingAt());
            }
        }
        if (bytes.size() + 32 > MAX_BYTES) throw new IOException("Queue snapshot exceeds size limit");
        byte[] payload = bytes.toByteArray();
        Path temporary = Files.createTempFile(file.getParent(), "queue-state-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, WRITE)) {
                writeFully(channel, ByteBuffer.wrap(payload)); writeFully(channel, ByteBuffer.wrap(digest(payload)));
                channel.force(true);
            }
            // No non-atomic fallback: a failed replacement must leave the previous snapshot intact.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // Directory fsync is supported on the deployment target (Linux), best effort elsewhere.
            try (var directory = FileChannel.open(file.getParent(), READ)) { directory.force(true); }
            catch (IOException | UnsupportedOperationException ignored) { }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) channel.write(bytes);
    }
    private static byte[] digest(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void validate(QueueEntry entry) throws IOException {
        if (entry.uuid() == null || entry.name() == null || (entry.name().isBlank() || entry.name().length() > 64 || entry.name().chars().anyMatch(Character::isISOControl))
                || entry.sequence() <= 0 || entry.sequence() > Long.MAX_VALUE / 2 || entry.firstJoinedAt() < 0
                || entry.lastPingAt() < entry.firstJoinedAt()) throw new IOException("Invalid queue snapshot entry");
    }
    @Override public void close() throws IOException {
        try { lock.release(); } finally { ownership.close(); }
    }
}
