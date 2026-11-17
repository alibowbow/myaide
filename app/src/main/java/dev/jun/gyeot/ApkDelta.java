package dev.jun.gyeot;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** A bounded, streaming COPY/DATA decoder. It never installs or signs an APK. */
public final class ApkDelta {
    public static final long MAX_APK_BYTES = 512L * 1024 * 1024;
    public static final int MAX_OPERATIONS = 100000;
    private static final int BUFFER = 65536;
    private static final byte[] MAGIC = {'G','Y','E','O','T','D','1','\n'};
    private ApkDelta() {}
    public interface CancelCheck { boolean isCancelled(); }

    /** Caller verifies downloaded patch hash and final Android package/signing identity separately.
     * Output must not already exist. On failure only the newly created output is removed. */
    public static void apply(File base, File patch, File output, String expectedBaseSHA,
            String expectedTargetSHA, long expectedTargetSize, CancelCheck cancelled) throws IOException {
        Guard guard = new Guard(cancelled);
        byte[] baseHash = parseHash(expectedBaseSHA), targetHash = parseHash(expectedTargetSHA);
        if (expectedTargetSize < 1 || expectedTargetSize > MAX_APK_BYTES) throw new IOException("Invalid target size");
        if (!base.isFile() || base.length() < 1 || base.length() > MAX_APK_BYTES
                || !patch.isFile() || patch.length() < 1 || patch.length() > MAX_APK_BYTES)
            throw new IOException("Invalid delta inputs");
        File out = output.getCanonicalFile();
        if (out.equals(base.getCanonicalFile()) || out.equals(patch.getCanonicalFile()) || out.exists())
            throw new IOException("Delta output must be a new, separate file");
        guard.check();
        boolean created = false, success = false;
        try (RandomAccessFile source = new RandomAccessFile(base, "r");
             StrictGzip gzip = new StrictGzip(new FileInputStream(patch), guard,
                     expectedTargetSize + 17L * MAX_OPERATIONS + 81);
             DataInputStream input = new DataInputStream(gzip)) {
            long sourceLength = source.length();
            if (sourceLength < 1 || sourceLength > MAX_APK_BYTES) throw new IOException("Invalid open base size");
            byte[] buffer = new byte[BUFFER];
            MessageDigest digest = sha256();
            long unhashed = sourceLength;
            while (unhashed > 0) {
                guard.check(); int count = (int)Math.min(BUFFER, unhashed);
                source.readFully(buffer, 0, count); digest.update(buffer, 0, count); unhashed -= count;
            }
            if (source.length() != sourceLength) throw new IOException("Delta base changed");
            if (!MessageDigest.isEqual(baseHash, digest.digest())) throw new IOException("Delta base hash mismatch");
            byte[] magic = new byte[8], declaredBase = new byte[32], declaredTarget = new byte[32];
            input.readFully(magic); input.readFully(declaredBase); input.readFully(declaredTarget);
            long targetLength = input.readLong();
            if (!Arrays.equals(MAGIC, magic) || !MessageDigest.isEqual(baseHash, declaredBase)
                    || !MessageDigest.isEqual(targetHash, declaredTarget) || targetLength != expectedTargetSize)
                throw new IOException("Delta header mismatch");
            guard.check();
            if (!out.createNewFile()) throw new IOException("Delta output already exists");
            created = true;
            digest.reset();
            try (FileOutputStream destination = new FileOutputStream(out)) {
                long written = 0; int operations = 0;
                while (true) {
                    guard.check();
                    int opcode = input.readUnsignedByte();
                    if (opcode == 2) {
                        if (written != targetLength || input.read() != -1)
                            throw new IOException("Delta size or trailing data mismatch");
                        break;
                    }
                    if (++operations > MAX_OPERATIONS) throw new IOException("Too many delta operations");
                    if (opcode != 0 && opcode != 1) throw new IOException("Unknown delta operation");
                    long offset = opcode == 0 ? input.readLong() : 0;
                    long length = input.readLong();
                    if (length <= 0 || length > targetLength - written) throw new IOException("Invalid delta output range");
                    if (opcode == 0) {
                        if (offset < 0 || offset > sourceLength || length > sourceLength - offset)
                            throw new IOException("Invalid delta source range");
                        source.seek(offset);
                    }
                    long remaining = length;
                    while (remaining > 0) {
                        guard.check();
                        int chunk = (int)Math.min(BUFFER, remaining);
                        if (opcode == 0) source.readFully(buffer, 0, chunk);
                        else input.readFully(buffer, 0, chunk);
                        destination.write(buffer, 0, chunk); digest.update(buffer, 0, chunk);
                        remaining -= chunk;
                    }
                    written += length;
                }
                if (!MessageDigest.isEqual(targetHash, digest.digest())) throw new IOException("Delta target hash mismatch");
                guard.check(); destination.getFD().sync();
            }
            success = true;
        } finally {
            if (created && !success && !out.delete() && out.exists())
                throw new IOException("Could not remove incomplete delta output");
        }
    }

    private static byte[] parseHash(String value) throws IOException {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) throw new IOException("Invalid SHA-256");
        byte[] result = new byte[32];
        for (int i = 0; i < 32; i++) result[i] = (byte)Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    private static final class Guard {
        final CancelCheck cancelled;
        final long started = System.nanoTime();
        Guard(CancelCheck cancelled) { this.cancelled = cancelled; }
        void check() throws IOException {
            if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.isCancelled()))
                throw new InterruptedIOException("Delta cancelled");
            if (System.nanoTime() - started > 300_000_000_000L) throw new IOException("Delta deadline exceeded");
        }
    }

    /** Exactly one ordinary gzip member; rejects optional headers, trailing bytes and zip bombs. */
    private static final class StrictGzip extends InputStream {
        final InputStream raw; final Guard guard; final long limit;
        final Inflater inflater = new Inflater(true);
        final CRC32 crc = new CRC32();
        final byte[] compressed = new byte[BUFFER];
        int supplied, tailPosition; long expanded, compressedRead = 10; boolean finished;
        StrictGzip(InputStream raw, Guard guard, long limit) throws IOException {
            this.raw = raw; this.guard = guard; this.limit = limit;
            try {
                byte[] header = new byte[10]; new DataInputStream(raw).readFully(header);
                if ((header[0] & 255) != 31 || (header[1] & 255) != 139 || header[2] != 8 || header[3] != 0)
                    throw new IOException("Unsupported gzip header");
            } catch (IOException e) { inflater.end(); raw.close(); throw e; }
        }
        @Override public int read() throws IOException {
            byte[] single = new byte[1]; int n = read(single, 0, 1); return n == -1 ? -1 : single[0] & 255;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            guard.check();
            if (finished) return -1;
            try {
                while (true) {
                    guard.check();
                    if (inflater.finished()) {
                        tailPosition = supplied - inflater.getRemaining();
                        long checksum = trailerInt(), size = trailerInt();
                        if (checksum != crc.getValue() || size != (expanded & 0xffffffffL))
                            throw new IOException("Invalid gzip trailer");
                        if (tailPosition < supplied || raw.read() != -1) throw new IOException("Trailing gzip bytes");
                        finished = true; return -1;
                    }
                    if (inflater.needsInput()) {
                        supplied = raw.read(compressed);
                        if (supplied < 0) throw new EOFException("Truncated gzip data");
                        compressedRead += supplied;
                        if (compressedRead > MAX_APK_BYTES) throw new IOException("Compressed delta limit");
                        inflater.setInput(compressed, 0, supplied);
                    }
                    int n = inflater.inflate(bytes, offset, Math.min(length, BUFFER));
                    if (n > 0) {
                        if (n > limit - expanded) throw new IOException("Delta expansion limit");
                        expanded += n; crc.update(bytes, offset, n); return n;
                    }
                    if (inflater.needsDictionary()) throw new IOException("Unsupported gzip dictionary");
                    if (!inflater.finished() && !inflater.needsInput()) throw new IOException("Invalid gzip stream");
                }
            } catch (DataFormatException e) { throw new IOException("Invalid gzip data", e); }
        }
        private int trailerByte() throws IOException {
            if (tailPosition < supplied) return compressed[tailPosition++] & 255;
            int value = raw.read(); if (value < 0) throw new EOFException("Truncated gzip trailer"); return value;
        }
        private long trailerInt() throws IOException {
            long value = 0; for (int i = 0; i < 4; i++) value |= (long)trailerByte() << (8 * i); return value;
        }
        @Override public void close() throws IOException { inflater.end(); raw.close(); }
    }
}
