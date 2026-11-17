package dev.jun.gyeot;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Exact legacy-root migration. No permission requests, deletion, reference writes or path I/O. */
final class MigrationCore {
    static final String SOURCE_ROOT = "primary:Download/들퍼레";
    static final String TARGET_ROOT = "primary:들퍼레";

    /** A backend-attested, non-symlink regular file; identity must survive reopen and detect replacement. */
    record Stamp(String relativePath, String reference, String identity, long size, long modified) {
        Stamp {
            safePath(relativePath); token(reference, "file reference"); token(identity, "file identity");
            if (size < 0 || modified < 0) throw new IllegalArgumentException("파일 크기·수정 시각을 확인할 수 없어요.");
        }
    }

    /** The ONLY writable handle: exclusively created and already open, never reopened for writing. */
    interface NewFile extends AutoCloseable {
        Stamp stamp();
        OutputStream stream();
        /** Flush/fsync descriptor and new directory entries up to the pinned root; report all failures. */
        void sync() throws IOException;
        @Override void close() throws IOException;
    }

    /**
     * Trusted adapter contract: roots are pinned to the primary volume and exact IDs below.
     * All metadata/opens must reject symlinks, replaced ancestors/mounts and alternate providers.
     * readSource/readTarget must bind the descriptor to the supplied identity. createExclusive
     * must atomically CREATE_NEW, never truncate, and return a handle only for the requested path.
     * Missing target returns null; denied/uncertain enumeration MUST throw. Nested parents may be
     * created only under the target root, with exclusive creation and no-follow verification.
     * requireAccess rechecks current OS access plus the narrow migration-source read exception.
     */
    interface Backend {
        String sourceRootId();
        String targetRootId();
        String scopeIdentity();
        void requireAccess() throws IOException;
        Stamp source(String relativePath) throws IOException;
        Stamp target(String relativePath) throws IOException;
        InputStream readSource(Stamp source) throws IOException;
        InputStream readTarget(Stamp target) throws IOException;
        /** Fsync the verified target descriptor and parent entries; NO byte writes, creation or deletion. */
        void syncTarget(Stamp target) throws IOException;
        NewFile createExclusive(String relativePath) throws IOException;
        /** Metadata-only verifier; live adapters may reopen scoped handles after copy handles close. */
        default void checkVerifiedTarget(String expectedScope, Stamp expected) throws IOException {
            requireAccess();
            if(!expectedScope.equals(scopeIdentity())) throw new IOException("이전 저장소 식별이 달라 참조 연결을 보류해요.");
            same(expected,target(expected.relativePath())); requireAccess();
            if(!expectedScope.equals(scopeIdentity())) throw new IOException("이전 저장소 식별이 바뀌었어요.");
        }
    }

    record Limits(long maxFileBytes, long timeoutMillis) {
        Limits {
            if (maxFileBytes < 0 || timeoutMillis < 1 || timeoutMillis > TimeUnit.HOURS.toMillis(24))
                throw new IllegalArgumentException("잘못된 이전 제한이에요.");
        }
        static Limits defaults() { return new Limits(Long.MAX_VALUE, TimeUnit.MINUTES.toMillis(30)); }
    }

    /** Not a deletion/ownership token. Created only after durable VERIFIED and fresh byte checks. */
    static final class VerifiedReceipt {
        private final MigrationLedger.Entry entry;
        private VerifiedReceipt(MigrationLedger.Entry entry) { this.entry = entry; }
        String recordId() { return entry.id(); }
        String sourceReference() { return entry.source().reference(); }
        String targetReference() { return entry.target().reference(); }
        String sourceRelativePath() { return entry.source().relativePath(); }
        String targetRelativePath() { return entry.targetPath(); }
        long size() { return entry.source().size(); }
        String sha256() { return entry.sha256(); }
        String scopeIdentity() { return entry.scopeIdentity(); }
    }

    private final MigrationLedger ledger;
    private final Backend backend;
    private final Limits limits;
    MigrationCore(MigrationLedger ledger, Backend backend) { this(ledger, backend, Limits.defaults()); }
    MigrationCore(MigrationLedger ledger, Backend backend, Limits limits) {
        this.ledger = Objects.requireNonNull(ledger); this.backend = Objects.requireNonNull(backend);
        this.limits = Objects.requireNonNull(limits);
    }

    /** Explicit caller action for one path. Existing uncertain records are reconciled read-only. */
    VerifiedReceipt runFile(String relativePath, BooleanSupplier cancelled) throws IOException {
        synchronized (MigrationLedger.LOCK) {
            safePath(relativePath); Budget budget = new Budget(cancelled); guard(null, budget);
            String scope = backend.scopeIdentity(); Stamp source = source(relativePath, budget);
            List<MigrationLedger.Entry> records = ledger.entries(); MigrationLedger.Entry entry = null;
            for (MigrationLedger.Entry item : records) if (relativePath.equals(item.source().relativePath())) {
                if (entry != null || !scope.equals(item.scopeIdentity())) throw held("이전 기록의 저장소가 달라요");
                entry = item;
            }
            if (entry == null) {
                if (records.size() >= MigrationLedger.MAX_RECORDS) throw held("이전 기록이 2,000개에 도달했어요. 남은 파일은 아직 이전하지 않았어요");
                String id = UUID.randomUUID().toString(), targetPath = relativePath;
                if (target(targetPath, budget) != null) {
                    targetPath = collisionPath(relativePath, id);
                    if (target(targetPath, budget) != null) throw held("충돌 회피 이름도 이미 있어요");
                }
                guard(scope, budget);
                entry = new MigrationLedger.Entry(id, MigrationLedger.State.PREPARED, scope, source, targetPath, null, "");
                ledger.write(entry);
            }
            same(entry.source(), source); guard(entry.scopeIdentity(), budget);
            if (entry.state().ordinal() >= MigrationLedger.State.CREATING.ordinal()) return verify(entry, budget);
            String digest = hash(entry.source(), true, budget);
            if (entry.state() == MigrationLedger.State.HASHED && !digest.equals(entry.sha256())) throw held("원본 내용이 준비 기록과 달라요");
            entry = entry.step(MigrationLedger.State.HASHED, null, digest); ledger.write(entry);
            // A new collision after PREPARED is held, never silently renamed or overwritten.
            if (target(entry.targetPath(), budget) != null) throw held("대상 이름이 준비 이후 생성됐어요");
            guard(entry.scopeIdentity(), budget); same(entry.source(), source(relativePath, budget));
            entry = entry.step(MigrationLedger.State.CREATING, null, digest); ledger.write(entry);
            // This intent is durable BEFORE the first externally visible create. Every resume from
            // here is verification-only, including failures before a returned descriptor is recorded.
            guard(entry.scopeIdentity(), budget);
            NewFile created = backend.createExclusive(entry.targetPath());
            if (created == null) throw held("새 파일 생성 결과를 확인하지 못했어요");
            try (NewFile owned = created) {
                Stamp output = owned.stamp(); checkPath(output, entry.targetPath());
                if (output.size() != 0) throw held("빈 새 파일이 아닌 응답이라 쓰지 않았어요");
                Stamp observed = target(entry.targetPath(), budget); same(output, observed);
                entry = entry.step(MigrationLedger.State.CREATED, output, digest); ledger.write(entry);
                copy(entry, owned, budget);
                guard(entry.scopeIdentity(), budget); owned.sync(); guard(entry.scopeIdentity(), budget);
            }
            guard(entry.scopeIdentity(), budget); // close/sync failure cannot produce a receipt.
            entry = entry.step(MigrationLedger.State.COPIED, entry.target(), digest); ledger.write(entry);
            return verify(entry, budget);
        }
    }
    /** Never creates or opens output for writing; no body/namespace mutation. Missing/partial files remain held. */
    VerifiedReceipt reconcile(String recordId, BooleanSupplier cancelled) throws IOException {
        synchronized (MigrationLedger.LOCK) {
            MigrationLedger.Entry entry = ledger.read(recordId);
            if (entry.state().ordinal() < MigrationLedger.State.CREATING.ordinal()) throw held("복사 생성 전 기록이에요. 명시적으로 이전을 다시 시작해 주세요");
            return verify(entry, new Budget(cancelled));
        }
    }
    /** Reference layer must call this before each new mapping transaction, including after restart. */
    VerifiedReceipt requireVerified(String recordId, BooleanSupplier cancelled) throws IOException {
        synchronized (MigrationLedger.LOCK) {
            MigrationLedger.Entry entry = ledger.read(recordId);
            if (entry.state() != MigrationLedger.State.VERIFIED) throw held("파일 검증이 완료되지 않았어요");
            return verify(entry, new Budget(cancelled));
        }
    }
    /** Background-only lookup. UI stale callbacks must use the immutable-receipt overload below. */
    void requireTargetIdentity(String recordId) throws IOException {
        synchronized (MigrationLedger.LOCK) {
            MigrationLedger.Entry entry = ledger.read(recordId);
            if (entry.state() != MigrationLedger.State.VERIFIED) throw held("검증된 참조 기록이 아니에요");
            requireTargetIdentity(new VerifiedReceipt(entry));
        }
    }
    /** Metadata-only freshness for an already-committed alias. No ledger I/O or long migration lock. */
    void requireTargetIdentity(VerifiedReceipt receipt) throws IOException {
        if (receipt == null) throw held("검증된 참조 영수증이 없어요");
        MigrationLedger.Entry entry = receipt.entry;
        backend.checkVerifiedTarget(entry.scopeIdentity(),entry.target());
    }

    private VerifiedReceipt verify(MigrationLedger.Entry entry, Budget budget) throws IOException {
        guard(entry.scopeIdentity(), budget); same(entry.source(), source(entry.source().relativePath(), budget));
        Stamp output = target(entry.targetPath(), budget);
        if (output == null) throw held("중단된 생성의 대상 파일이 없어요. 자동 재생성하지 않아요");
        checkPath(output, entry.targetPath());
        if (entry.target() != null) identity(entry.target(), output);
        if (output.size() != entry.source().size()) throw held("대상 크기가 달라 미완성 파일을 보존했어요");
        String targetHash = hash(output, false, budget);
        if (!entry.sha256().equals(targetHash)) throw held("대상 내용 검증에 실패했어요");
        // Reconciliation after lost sync/close must establish target durability too. This adapter
        // operation may fsync an identity-bound read-only descriptor, never reopen for body writes.
        guard(entry.scopeIdentity(), budget); backend.syncTarget(output); guard(entry.scopeIdentity(), budget);
        same(output, target(entry.targetPath(), budget));
        // An independent second source read detects changes while copying (even unchanged mtime).
        String sourceHash = hash(entry.source(), true, budget);
        if (!entry.sha256().equals(sourceHash)) throw held("복사 중 원본 내용이 달라졌어요");
        same(output, target(entry.targetPath(), budget)); guard(entry.scopeIdentity(), budget);
        MigrationLedger.Entry verified = entry.step(MigrationLedger.State.VERIFIED, output, entry.sha256());
        if (!verified.equals(entry)) ledger.write(verified);
        guard(entry.scopeIdentity(), budget);
        return new VerifiedReceipt(verified);
    }

    private void copy(MigrationLedger.Entry entry, NewFile output, Budget budget) throws IOException {
        guard(entry.scopeIdentity(), budget); same(entry.source(), source(entry.source().relativePath(), budget));
        MessageDigest digest = sha256(); long total = 0; int idle = 0;
        try (InputStream in = backend.readSource(entry.source())) {
            if (in == null) throw held("원본을 열지 못했어요");
            guard(entry.scopeIdentity(), budget); same(entry.source(), source(entry.source().relativePath(), budget));
            OutputStream out = output.stream(); if (out == null) throw held("새 파일 쓰기 핸들이 없어요");
            byte[] buffer = new byte[65536];
            while (true) {
                guard(entry.scopeIdentity(), budget);
                int n = in.read(buffer, 0, readSize(buffer.length, entry.source().size() - total));
                guard(entry.scopeIdentity(), budget);
                if (n == -1) break;
                if (n == 0) { if (++idle > 16) throw held("원본 읽기가 진행되지 않아요"); continue; }
                idle = 0;
                if (n < 0 || n > entry.source().size() - total) throw held("원본 크기가 준비 기록보다 커졌어요");
                identity(entry.target(), target(entry.targetPath(), budget));
                guard(entry.scopeIdentity(), budget); out.write(buffer, 0, n); guard(entry.scopeIdentity(), budget);
                digest.update(buffer, 0, n); total += n;
            }
            if (total != entry.source().size() || !entry.sha256().equals(hex(digest.digest()))) throw held("복사한 원본 크기·해시가 준비 기록과 달라요");
            guard(entry.scopeIdentity(), budget); out.flush(); guard(entry.scopeIdentity(), budget);
        }
        guard(entry.scopeIdentity(), budget); same(entry.source(), source(entry.source().relativePath(), budget));
    }

    private String hash(Stamp expected, boolean isSource, Budget budget) throws IOException {
        checkSize(expected); guard(null, budget);
        same(expected, isSource ? source(expected.relativePath(), budget) : target(expected.relativePath(), budget));
        MessageDigest digest = sha256(); long total = 0; int idle = 0;
        try (InputStream in = isSource ? backend.readSource(expected) : backend.readTarget(expected)) {
            if (in == null) throw held("검증 파일을 열지 못했어요");
            guard(null, budget);
            same(expected, isSource ? source(expected.relativePath(), budget) : target(expected.relativePath(), budget));
            byte[] buffer = new byte[65536];
            while (true) {
                guard(null, budget); int n = in.read(buffer, 0, readSize(buffer.length, expected.size() - total)); guard(null, budget);
                if (n == -1) break;
                if (n == 0) { if (++idle > 16) throw held("검증 읽기가 진행되지 않아요"); continue; }
                idle = 0;
                if (n < 0 || n > expected.size() - total) throw held("검증 중 파일 크기가 커졌어요");
                digest.update(buffer, 0, n); total += n;
            }
        }
        guard(null, budget);
        same(expected, isSource ? source(expected.relativePath(), budget) : target(expected.relativePath(), budget));
        if (total != expected.size()) throw held("검증 파일 크기가 달라요");
        return hex(digest.digest());
    }
    private Stamp source(String path, Budget budget) throws IOException {
        guard(null, budget); Stamp stamp = backend.source(path); guard(null, budget); checkPath(stamp, path); checkSize(stamp); return stamp;
    }
    private Stamp target(String path, Budget budget) throws IOException {
        guard(null, budget); Stamp stamp = backend.target(path); guard(null, budget);
        if (stamp != null) checkPath(stamp, path); return stamp;
    }
    private void checkSize(Stamp stamp) throws IOException {
        if (stamp.size() > limits.maxFileBytes()) throw held("이 파일은 설정된 이전 크기 제한을 넘어요. 아직 이전하지 않았어요");
    }
    private void guard(String scope, Budget budget) throws IOException {
        budget.check();
        if (!SOURCE_ROOT.equals(backend.sourceRootId()) || !TARGET_ROOT.equals(backend.targetRootId())) throw held("정확한 이전 원본·대상 폴더가 아니에요");
        String current = backend.scopeIdentity();
        if (current == null || current.isEmpty() || (scope != null && !scope.equals(current))) throw held("저장소 식별자가 바뀌었어요");
        if (budget.scope == null) budget.scope = current;
        else if (!budget.scope.equals(current)) throw held("이전 중 저장소가 바뀌었어요");
        backend.requireAccess(); budget.check();
    }
    private static void checkPath(Stamp stamp, String path) throws IOException {
        if (stamp == null || !path.equals(stamp.relativePath())) throw held("응답 파일의 정확한 상대 경로가 달라요");
    }
    private static void same(Stamp expected, Stamp actual) throws IOException {
        if (!expected.equals(actual)) throw held("파일 식별자·크기·수정 시각이 바뀌었어요");
    }
    private static void identity(Stamp expected, Stamp actual) throws IOException {
        if (expected == null || actual == null || !expected.relativePath().equals(actual.relativePath())
                || !expected.reference().equals(actual.reference()) || !expected.identity().equals(actual.identity())) throw held("대상 파일이 교체됐어요");
    }
    private static String collisionPath(String path, String id) throws IOException {
        int slash = path.lastIndexOf('/'); String folder = path.substring(0, slash + 1), name = path.substring(slash + 1);
        int dot = name.lastIndexOf('.'); String suffix = " (이전 " + id.substring(0, 8) + ")";
        String candidate = folder + (dot > 0 ? name.substring(0, dot) + suffix + name.substring(dot) : name + suffix);
        try { return safePath(candidate); } catch (IllegalArgumentException error) { throw held("충돌 회피 이름이 너무 길어요. 원본은 변경하지 않았어요"); }
    }
    private final class Budget {
        final BooleanSupplier cancelled; final long started = System.nanoTime(); String scope;
        Budget(BooleanSupplier cancelled) { this.cancelled = Objects.requireNonNull(cancelled); }
        void check() throws InterruptedIOException {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("이전을 중단했어요. 원본과 현재까지 만든 파일을 보존했어요.");
            if (System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(limits.timeoutMillis())) throw new InterruptedIOException("이전 시간 제한에 도달했어요. 완료되지 않은 파일은 보류했어요.");
        }
    }
    private static int readSize(int capacity, long remaining) { return remaining < capacity ? (int) remaining + 1 : capacity; }
    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException error) { throw new IOException(error); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return result.toString();
    }
    private static IOException held(String detail) { return new IOException(detail + " 원본과 기존 주소는 보존하며 덮어쓰기·삭제·불확실한 쓰기 재실행은 하지 않아요."); }

    static String safePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 4096 || path.indexOf('\\') >= 0
                || path.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("잘못된 상대 파일 경로예요.");
        String[] parts = path.split("/", -1);
        if (parts.length > 21) throw new IllegalArgumentException("폴더 깊이가 20단계를 넘어요.");
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals("..")
                || part.getBytes(StandardCharsets.UTF_8).length > 255) throw new IllegalArgumentException("잘못된 상대 파일 경로예요.");
        return path;
    }
    private static void token(String value, String what) {
        if (value == null || value.isEmpty() || value.length() > 8192 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid " + what);
    }
}
