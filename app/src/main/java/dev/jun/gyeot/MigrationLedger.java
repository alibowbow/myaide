package dev.jun.gyeot;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.json.JSONObject;
import org.json.JSONTokener;

/** App-private per-file intent/receipt journal. An uncertain state is never permission to write again. */
final class MigrationLedger {
    static final Object LOCK = new Object(); // Serializes separate Core/Ledger instances in this process.
    static final int MAX_RECORDS = 2000;
    private static final int MAX_RECORD_BYTES = 65536;
    enum State { PREPARED, HASHED, CREATING, CREATED, COPIED, VERIFIED }
    record Entry(String id, State state, String scopeIdentity, MigrationCore.Stamp source,
                 String targetPath, MigrationCore.Stamp target, String sha256) {
        Entry {
            checkId(id);
            Objects.requireNonNull(state); Objects.requireNonNull(source);
            if (scopeIdentity == null || scopeIdentity.isEmpty() || scopeIdentity.length() > 8192
                    || scopeIdentity.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("잘못된 저장소 식별자예요.");
            MigrationCore.safePath(targetPath);
            if (target != null && !targetPath.equals(target.relativePath())) throw new IllegalArgumentException("대상 경로가 기록과 달라요.");
            if (state.ordinal() >= State.HASHED.ordinal() && (sha256 == null || !sha256.matches("[0-9a-f]{64}")))
                throw new IllegalArgumentException("검증 해시가 없어요.");
            if (state.ordinal() >= State.CREATED.ordinal() && target == null) throw new IllegalArgumentException("대상 식별자가 없어요.");
            if (state == State.PREPARED && (!"".equals(sha256) || target != null)) throw new IllegalArgumentException("잘못된 준비 기록이에요.");
            if ((state == State.HASHED || state == State.CREATING) && target != null) throw new IllegalArgumentException("잘못된 생성 기록이에요.");
            if (state == State.VERIFIED && source.size() != target.size()) throw new IllegalArgumentException("검증 크기가 달라요.");
        }
        Entry step(State next, MigrationCore.Stamp output, String digest) {
            return new Entry(id, next, scopeIdentity, source, targetPath, output, digest);
        }
    }
    interface WriteCheck { void beforeWrite(Entry entry) throws IOException; }
    interface DirectorySync { void sync(File directory) throws IOException; }
    private final File directory;
    private final WriteCheck writeCheck;
    private final DirectorySync directorySync;
    MigrationLedger(Context context) throws IOException { this(new File(context.getFilesDir(), "shared-folder-migration-ledger-v1")); }
    MigrationLedger(File directory) throws IOException { this(directory, entry -> {}); }
    /** Fault injection is package-private and never configured by production UI. */
    MigrationLedger(File directory, WriteCheck writeCheck) throws IOException { this(directory, writeCheck, MigrationLedger::syncDirectory); }
    MigrationLedger(File directory, WriteCheck writeCheck, DirectorySync directorySync) throws IOException {
        if (Files.isSymbolicLink(directory.toPath())) throw new IOException("이전 기록 폴더의 링크는 사용할 수 없어요.");
        boolean created = !directory.isDirectory();
        if (created && !directory.mkdir()) throw new IOException("이전 기록 폴더를 만들지 못했어요.");
        this.directory = directory.getCanonicalFile(); this.writeCheck = Objects.requireNonNull(writeCheck);
        this.directorySync = Objects.requireNonNull(directorySync);
        directorySync.sync(this.directory);
        if (created) directorySync.sync(this.directory.getParentFile());
    }
    Entry read(String id) throws IOException {
        synchronized (LOCK) {
            requirePrivateFiles(id);
            try (InputStream in = new AtomicFile(file(id)).openRead()) {
                byte[] bytes = in.readNBytes(MAX_RECORD_BYTES + 1);
                if (bytes.length > MAX_RECORD_BYTES) throw new IOException("이전 기록이 너무 커요.");
                JSONTokener parser = new JSONTokener(new String(bytes, StandardCharsets.UTF_8));
                JSONObject json = new JSONObject(parser);
                if (parser.nextClean() != 0) throw new IOException("이전 기록 뒤에 추가 데이터가 있어요.");
                if (json.length() != 9 || json.getInt("version") != 1 || !id.equals(json.getString("id"))
                        || !Boolean.TRUE.equals(json.get("originalRetained")))
                    throw new IOException("이전 기록 버전 또는 식별자가 달라요.");
                return new Entry(id, State.valueOf(json.getString("state")), json.getString("scope"),
                    stamp(json.getJSONObject("source")), json.getString("targetPath"),
                    json.isNull("target") ? null : stamp(json.getJSONObject("target")), json.getString("sha256"));
            } catch (IOException error) { throw error; }
            catch (Exception error) { throw new IOException("이전 기록을 검증하지 못했어요. 자동 재실행하지 않아요.", error); }
        }
    }
    List<Entry> entries() throws IOException {
        synchronized (LOCK) {
            requireDirectory();
            SortedSet<String> ids = new TreeSet<>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory.toPath())) {
                int scanned = 0;
                for (Path file : files) {
                    if (++scanned > MAX_RECORDS * 3) throw new IOException("이전 기록이 너무 많아 일부만 실행하지 않고 중단했어요.");
                    String name = file.getFileName().toString();
                    if (name.endsWith(".json.bak")) name = name.substring(0, name.length() - 4);
                    if (!name.endsWith(".json")) continue;
                    String id = name.substring(0, name.length() - 5);
                    try { checkId(id); } catch (IllegalArgumentException error) { throw new IOException("알 수 없는 이전 기록이 있어요.", error); }
                    ids.add(id);
                    if (ids.size() > MAX_RECORDS) throw new IOException("이전 기록이 2,000개를 넘어요. 남은 파일은 이전하지 않았어요.");
                }
            }
            List<Entry> result = new ArrayList<>();
            for (String id : ids) result.add(read(id));
            return List.copyOf(result);
        }
    }
    Entry find(String scope, String sourcePath) throws IOException {
        Entry found = null;
        for (Entry entry : entries()) if (scope.equals(entry.scopeIdentity()) && sourcePath.equals(entry.source().relativePath())) {
            if (found != null) throw new IOException("같은 원본의 이전 기록이 겹쳐 자동 실행하지 않아요.");
            found = entry;
        }
        return found;
    }
    void write(Entry entry) throws IOException {
        synchronized (LOCK) {
            requirePrivateFiles(entry.id());
            FileOutputStream out = null; AtomicFile atomic = new AtomicFile(file(entry.id()));
            try {
                writeCheck.beforeWrite(entry);
                JSONObject json = new JSONObject().put("version", 1).put("id", entry.id()).put("state", entry.state().name())
                    .put("scope", entry.scopeIdentity()).put("source", json(entry.source())).put("targetPath", entry.targetPath())
                    .put("target", entry.target() == null ? JSONObject.NULL : json(entry.target())).put("sha256", entry.sha256())
                    .put("originalRetained", true);
                byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_RECORD_BYTES) throw new IOException("이전 기록이 너무 커요.");
                out = atomic.startWrite(); out.write(bytes); out.flush(); out.getFD().sync();
                atomic.finishWrite(out); out = null;
                requirePrivateFiles(entry.id()); directorySync.sync(directory);
                // AtomicFile can log certain finish/rename failures instead of throwing. Never
                // perform the next external side effect until a bounded durable-record read agrees.
                try (InputStream in = atomic.openRead()) {
                    if (!Arrays.equals(bytes, in.readNBytes(MAX_RECORD_BYTES + 1))) throw new IOException("이전 기록의 저장 바이트가 일치하지 않아요.");
                }
                if (!entry.equals(read(entry.id()))) throw new IOException("이전 기록의 저장 결과가 일치하지 않아요.");
            } catch (Exception error) {
                if (out != null) atomic.failWrite(out);
                throw new IOException("이전 기록을 안전하게 저장하지 못했어요. 원본은 보존했어요.", error);
            }
        }
    }
    private File file(String id) { checkId(id); return new File(directory, id + ".json"); }
    private void requireDirectory() throws IOException {
        if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory()
                || !directory.equals(directory.getCanonicalFile())) throw new IOException("이전 기록 폴더가 바뀌었어요.");
    }
    private void requirePrivateFiles(String id) throws IOException {
        requireDirectory(); File file = file(id);
        for (String suffix : List.of("", ".new", ".bak")) if (Files.isSymbolicLink(new File(file.getPath() + suffix).toPath()))
            throw new IOException("이전 기록 파일의 링크는 사용할 수 없어요.");
    }
    /** AtomicFile syncs file data, not the renamed directory entry. Unsupported directory fsync is fatal. */
    static void syncDirectory(File directory) throws IOException {
        FileDescriptor descriptor = null;
        try {
            // O_DIRECTORY is not in the public Android SDK. NOFOLLOW + fstat verifies this
            // app-private descriptor is a directory before syncing; no file body is read.
            descriptor = Os.open(directory.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
            if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw new IOException("기록 경로가 폴더가 아니에요.");
            Os.fsync(descriptor);
        } catch (Exception error) { throw new IOException("이전 기록 폴더의 영구 저장을 확인하지 못했어요. 외부 파일을 생성하지 않아요.", error); }
        finally {
            if (descriptor != null) try { Os.close(descriptor); }
            catch (Exception error) { throw new IOException("이전 기록 폴더를 안전하게 닫지 못했어요.", error); }
        }
    }
    private static void checkId(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("잘못된 이전 기록 식별자예요.");
    }
    private static JSONObject json(MigrationCore.Stamp stamp) throws Exception {
        return new JSONObject().put("path", stamp.relativePath()).put("reference", stamp.reference()).put("identity", stamp.identity())
            .put("size", stamp.size()).put("modified", stamp.modified());
    }
    private static MigrationCore.Stamp stamp(JSONObject json) throws Exception {
        if (json.length() != 5) throw new IOException("잘못된 파일 식별 기록이에요.");
        return new MigrationCore.Stamp(json.getString("path"), json.getString("reference"), json.getString("identity"),
            json.getLong("size"), json.getLong("modified"));
    }
}
