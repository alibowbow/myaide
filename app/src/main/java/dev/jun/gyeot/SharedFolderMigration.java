package dev.jun.gyeot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Legacy SAF metadata inventory only. Actual authorized copying uses ManagedStorageWorkflow. */
final class SharedFolderMigration {
    private static final String SOURCE = "primary:Download/들퍼레";
    private static final String PREFS = "shared-folder-migration", KEY = "source";
    private SharedFolderMigration() {}
    record Plan(Uri source, Uri target, List<String> paths, int folders, long knownBytes, int unknownSizes) {
        Plan { paths = List.copyOf(paths); }
        String summary() {
            StringBuilder value = new StringBuilder("이전 위치: Download/들퍼레\n새 위치: 내장 저장공간/들퍼레\n\n파일 ")
                .append(paths.size()).append("개 · 폴더 ").append(folders).append("개\n알려진 크기 ").append(knownBytes)
                .append("바이트 · 크기 미상 ").append(unknownSizes).append("개\n\n이 목록은 파일명·종류·크기·날짜만 조회했어요. 내용은 읽거나 복사하지 않았어요.\n")
                .append("이 검색 전용 연결에서는 파일을 복사하지 않아요. 실제 이전은 저장소 설정의 Android 승인 후 검증 복사로 실행해요. 원본은 보존해요.\n\n");
            for (int i = 0; i < Math.min(100, paths.size()); i++) value.append(paths.get(i)).append('\n');
            if (paths.size() > 100) value.append("… 나머지 ").append(paths.size()-100).append("개");
            return value.toString();
        }
    }
    static void chooseSource(Activity activity, int requestCode) {
        SharedFolderAccessUi.chooseMetadata(activity,"기존 Download/들퍼레 · 목록만 확인 · 실제 이전은 실행하지 않아요.",()->activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(SharedOutputFolder.AUTHORITY, SOURCE)), requestCode));
    }
    static String acceptSource(Context context, Intent data, int resultCode) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return "이전 폴더 선택을 취소했어요. 파일은 바뀌지 않았어요.";
        Uri tree = data.getData();
        if (!SharedOutputFolder.matches(tree, SOURCE)) return "이전 Download/들퍼레 폴더만 선택해 주세요. Download 전체나 다른 폴더는 사용하지 않아요.";
        try {
            if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) throw new IOException("목록 조회 권한이 필요해요.");
            // Android labels this READ; our application policy uses it for metadata ONLY.
            context.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tree.toString()).commit()) throw new IOException("설정을 저장하지 못했어요.");
            return "이전 폴더의 파일 목록만 확인할 수 있어요. 내용 읽기·복사·삭제는 허용하지 않았어요.";
        } catch (Exception error) { return "이전 폴더 목록 권한을 저장하지 못했어요. 다시 선택해 주세요."; }
    }
    static Plan preview(Context context) throws Exception { return preview(context, () -> false); }
    static Plan preview(Context context, BooleanSupplier cancelled) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        BooleanSupplier stop = () -> cancelled.getAsBoolean() || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline;
        FileExplorerRepository.check(stop);
        Uri target = SharedOutputFolder.selectedTree(context);
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
        if (saved.isEmpty()) throw new IOException("먼저 이전 Download/들퍼레 폴더를 선택해 주세요. 목록만 확인해요.");
        Uri tree = Uri.parse(saved);
        if (!SharedOutputFolder.matches(tree, SOURCE)) throw new IOException("이전 Download/들퍼레 폴더를 다시 선택해 주세요.");
        SharedOutputFolder.requireGrant(context, tree, false);
        FileExplorerRepository repository = new FileExplorerRepository(context, tree);
        record Folder(String id, String path, int depth) {}
        Deque<Folder> queue = new ArrayDeque<>(); queue.add(new Folder(repository.root, "", 0));
        Set<String> seen = new HashSet<>(); List<String> paths = new ArrayList<>(); int folders=0, total=0, unknown=0; long bytes=0;
        while (!queue.isEmpty()) {
            FileExplorerRepository.check(stop);
            Folder folder = queue.remove();
            if (!seen.add(folder.id())) throw new IOException("반복되는 폴더 응답이라 목록 조회를 중단했어요.");
            for (FileExplorerRepository.Entry entry : repository.list(folder.id(), stop)) {
                if (++total > 2000) throw new IOException("이전 폴더에 항목이 2,000개를 넘어요. 목록 조회를 중단했어요. 파일은 변경하지 않았어요.");
                String path = folder.path() + entry.name();
                if (entry.directory()) {
                    if (folder.depth() >= 20) throw new IOException("폴더 깊이가 20단계를 넘어 목록 조회를 중단했어요.");
                    folders++; queue.add(new Folder(entry.id(), path + "/", folder.depth()+1));
                } else { paths.add(path); if (entry.size() < 0) unknown++; else bytes = Math.addExact(bytes, entry.size()); }
            }
        }
        return new Plan(tree, target, paths, folders, bytes, unknown);
    }
    /** Deliberately blocked at the entrypoint, not just hidden in the UI. Never auto-replay a preview. */
    static String migrate(Context context, Plan plan, BooleanSupplier cancelled) throws IOException {
        throw new IOException("이 검색 전용 목록 기능은 복사하지 않아요. 저장소 설정에서 실제 Android 승인 후 검증 복사·참조 연결을 실행해 주세요. 원본과 기존 주소는 보존돼요.");
    }
    static String status(Context context) {
        return "이 연결은 검색 전용 목록이에요. 실제 검증 복사·참조 연결은 저장소 설정에서 시작해요. 원본은 삭제하지 않아요.";
    }
}
