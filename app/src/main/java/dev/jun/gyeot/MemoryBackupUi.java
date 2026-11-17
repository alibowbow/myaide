package dev.jun.gyeot;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.Jsons;
import dev.jun.gyeot.core.Memory;
import dev.jun.gyeot.core.MemoryBackup;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 설정 → 기억 → 백업: exports the long-term memory, "나에 대해" and the main chat's pinned notes to 들퍼레/백업 (a JSON the
 * app reads back and a Markdown copy to read), merges a chosen backup after a preview, and takes memories from another AI's
 * answer, saving only the ones ticked. Files are written with SharedMediaOutput and read only inside 들퍼레/백업.
 */
final class MemoryBackupUi {
    private static final BooleanSupplier NEVER = () -> false;
    /** One merge at a time, so a second one is planned against what the first added. */
    private static final Object MERGING = new Object();
    private final Activity activity; private final Ui ui; private final AppStore store; private final Runnable refresh;
    MemoryBackupUi(Activity activity, Ui ui, AppStore store, Runnable refresh) { this.activity = activity; this.ui = ui; this.store = store; this.refresh = refresh; }

    /** The memory page's 백업 rows. */
    View section() {
        LinearLayout group = ui.group();
        ui.addRow(group, ui.listRow(R.drawable.ic_download, Ui.ACCENT, "기억 내보내기", "들퍼레/백업 폴더에 저장해요", ui.chevron(), this::export), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_history, Ui.ACCENT, "백업에서 가져오기", "미리 보고 지금 기억에 합쳐요", ui.chevron(), this::importBackup), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_sparkle, Ui.ACCENT, "다른 AI에서 기억 가져오기", "부탁을 복사해 붙이고 골라 저장해요", ui.chevron(), this::fromOtherAi), true);
        return group;
    }

    /** Writes 기억-YYYYMMDD-HHmm.json and .md into 들퍼레/백업; an existing file is never overwritten (SharedMediaOutput adds a suffix). */
    void export() {
        try { SharedOutputFolder.selectedTree(activity); } catch (IOException e) { store.error(e.getMessage()); return; }
        boolean notesOpen = store.mainChat.available();
        long now = System.currentTimeMillis(); String persona = store.persona();
        JSONObject backup = MemoryBackup.export(store.memories(), persona, notesOpen ? store.mainChat.notes() : new JSONArray(), now);
        if (MemoryBackup.empty(backup)) { store.error("내보낼 기억이 아직 없어요."); return; }
        int secret = MemoryBackup.secretLines(persona);
        String held = (notesOpen ? "" : " · 메인 대화 저장소를 열지 못해 고정 기억은 빠졌어요") + (secret > 0 ? " · ‘나에 대해’에서 비밀번호·인증번호처럼 보이는 " + secret + "줄은 뺐어요" : "");
        // The .md is written only where Android names .md files text/markdown, so the saved file is checked as what it is.
        boolean markdown = MemoryBackup.MARKDOWN.equals(MimeTypeMap.getSingleton().getMimeTypeFromExtension("md"));
        store.status("기억을 내보내는 중…");
        new Thread(() -> {
            ZoneId zone = ZoneId.systemDefault(); String key = "memory-backup-" + UUID.randomUUID();
            String saved;
            try {
                saved = name(SharedMediaOutput.save(activity, key + ".json", MemoryBackup.FOLDER, MemoryBackup.fileName(now, zone, "json"), MemoryBackup.JSON,
                    MemoryBackup.json(backup).getBytes(StandardCharsets.UTF_8), () -> {}));
                discard(key + ".json");
            } catch (Exception e) { post(() -> store.error("기억을 내보내지 못했어요. " + failure(e))); return; }
            String readable = "";
            if (!markdown) readable = " · 이 폰은 .md 형식을 몰라서 JSON만 저장했어요";
            else try {
                // Same-minute exports get different suffixes, so the .md names its JSON instead of sharing a name.
                SharedMediaOutput.save(activity, key + ".md", MemoryBackup.FOLDER, MemoryBackup.fileName(now, zone, "md"), MemoryBackup.MARKDOWN,
                    MemoryBackup.markdown(backup, zone, saved).getBytes(StandardCharsets.UTF_8), () -> {});
                discard(key + ".md");
            } catch (Exception e) { readable = " · 읽기용 .md는 저장하지 못했어요"; }
            String message = "들퍼레/백업/" + saved + "에 내보냈어요 · " + MemoryBackup.counts(backup) + held + readable;
            post(() -> store.status(message));
        }, "memory-export").start();
    }
    /** A verified backup's private staging copy holds the memories too, so it does not outlive the export; a failed one stays for 대기 파일 저장. */
    private void discard(String key) { try { SharedMediaOutput.discard(activity, key); } catch (Exception kept) { /* Stays like a media copy. */ } }
    /** SharedMediaOutput's messages name media; here the file is a backup. */
    private static String failure(Exception e) { return Jsons.message(e, null).replace("미디어가", "백업이").replace("미디어", "백업"); }

    /** Lists the JSON files in 들퍼레/백업, newest first, to pick one. */
    void importBackup() {
        Uri tree;
        try { tree = SharedOutputFolder.selectedTree(activity); } catch (IOException e) { store.error(e.getMessage()); return; }
        store.status("백업을 찾는 중…");
        new Thread(() -> {
            try {
                FileExplorerRepository repo = new FileExplorerRepository(activity, tree);
                List<FileExplorerRepository.Entry> files = backups(repo);
                post(() -> {
                    if (files.isEmpty()) { store.error("들퍼레/백업에 기억 백업이 없어요. 먼저 ‘기억 내보내기’를 해 주세요."); return; }
                    store.status("가져올 백업을 골라 주세요"); choose(repo, files);
                });
            } catch (Exception e) { post(() -> store.error("백업 목록을 읽지 못했어요. " + Jsons.message(e, null))); }
        }, "memory-backups").start();
    }
    private static List<FileExplorerRepository.Entry> backups(FileExplorerRepository repo) throws Exception {
        repo.requireContentAccess(repo.document(repo.root));
        FileExplorerRepository.Entry folder = null;
        for (FileExplorerRepository.Entry entry : repo.list(repo.root, NEVER)) if (entry.directory() && MemoryBackup.FOLDER.equals(entry.name())) folder = entry;
        if (folder == null) return List.of();
        List<FileExplorerRepository.Entry> files = new ArrayList<>();
        for (FileExplorerRepository.Entry entry : repo.list(folder.id(), NEVER)) if (!entry.directory() && entry.name().toLowerCase(Locale.ROOT).endsWith(".json")) files.add(entry);
        files.sort(Comparator.comparingLong(FileExplorerRepository.Entry::modified).thenComparing(FileExplorerRepository.Entry::name).reversed());
        return files.size() > 50 ? List.copyOf(files.subList(0, 50)) : files;
    }
    private void choose(FileExplorerRepository repo, List<FileExplorerRepository.Entry> files) {
        Ui.Sheet sheet = ui.selectionSheet("백업에서 가져오기", "고르면 무엇을 합칠지 먼저 보여 줘요");
        LinearLayout group = ui.group();
        for (FileExplorerRepository.Entry entry : files)
            ui.addRow(group, ui.listRow(R.drawable.ic_file, Ui.ACCENT, entry.name(), size(entry.size()), ui.chevron(), () -> { sheet.dismiss(); open(repo, entry); }), true);
        sheet.body.addView(group);
        sheet.primary.setText("닫기");
        sheet.show();
    }
    private void open(FileExplorerRepository repo, FileExplorerRepository.Entry entry) {
        store.status("백업을 읽는 중…");
        new Thread(() -> {
            try {
                MemoryBackup.Backup backup = MemoryBackup.parse(read(repo, entry));
                MemoryBackup.Plan plan = plan(backup);
                post(() -> { store.status("합칠 것을 확인해 주세요"); preview(entry.name(), backup, plan); });
            } catch (IllegalArgumentException refused) { post(() -> store.error(refused.getMessage())); }
            catch (Exception e) { post(() -> store.error("백업 파일을 읽지 못했어요. " + Jsons.message(e, null))); }
        }, "memory-backup-read").start();
    }
    /** At most MemoryBackup.MAX_BYTES of strict UTF-8, from a file listed inside 들퍼레/백업 and checked again before and after. */
    private static String read(FileExplorerRepository repo, FileExplorerRepository.Entry entry) throws Exception {
        if (entry.size() > MemoryBackup.MAX_BYTES) throw new IllegalArgumentException("백업 파일이 너무 커요. 1MB 이하 파일만 가져올 수 있어요.");
        FileExplorerRepository.Entry current = repo.fresh(entry, NEVER);
        repo.requireContentAccess(current.uri());
        byte[] bytes;
        try (InputStream in = repo.resolver.openInputStream(current.uri())) {
            if (in == null) throw new IOException("파일을 열지 못했어요.");
            bytes = in.readNBytes(MemoryBackup.MAX_BYTES + 1);
        }
        repo.requireContentAccess(current.uri());
        if (bytes.length > MemoryBackup.MAX_BYTES) throw new IllegalArgumentException("백업 파일이 너무 커요. 1MB 이하 파일만 가져올 수 있어요.");
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException bad) { throw new IllegalArgumentException(Brand.NAME + " 기억 백업 파일이 아니에요. 들퍼레/백업의 기억-….json 파일을 골라 주세요."); }
    }
    private void preview(String name, MemoryBackup.Backup backup, MemoryBackup.Plan plan) {
        Ui.Sheet sheet = ui.selectionSheet("가져오기 미리보기", name);
        TextView text = ui.text(MemoryBackup.preview(backup, plan), 15, 450, Ui.TEXT); text.setLineSpacing(ui.dp(4), 1); text.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(6));
        sheet.body.addView(text);
        if (plan.empty()) { sheet.primary.setText("닫기"); sheet.show(); return; }
        sheet.primary.setText("합치기");
        sheet.primary.setOnClickListener(v -> { sheet.dismiss(); merge(backup); });
        sheet.footer.addView(ui.button("취소", false, sheet::dismiss), margin());
        sheet.show();
    }
    private MemoryBackup.Plan plan(MemoryBackup.Backup backup) {
        return MemoryBackup.plan(backup, store.memories(), store.persona(), store.mainChat.available() ? store.mainChat.notes() : null, System.currentTimeMillis());
    }
    /** Merges what the preview showed, planned again against the memory as it is now, off the main thread; nothing kept is removed or replaced. */
    private void merge(MemoryBackup.Backup backup) {
        store.status("기억을 합치는 중…");
        new Thread(() -> {
            synchronized (MERGING) {
                MemoryBackup.Plan plan = plan(backup);
                int memories = 0, notes = 0, refused = 0;
                try {
                    if (!plan.memories().isEmpty()) { long now = System.currentTimeMillis(); memories = store.changeMemory(list -> MemoryBackup.addMemories(list, plan.memories(), now)); }
                } catch (Exception e) { String message = e.getMessage(); activity.runOnUiThread(() -> store.error(message)); return; }
                if (plan.persona() != null) { store.settings.edit().putString("persona", plan.persona()).apply(); store.changed(); }
                for (MemoryBackup.Item note : plan.notes()) {
                    try { store.mainChat.addNote(note.kind(), note.text(), 0); notes++; } catch (Exception e) { refused++; }
                }
                List<String> parts = new ArrayList<>();
                if (memories > 0) parts.add("기억 " + memories + "개");
                if (plan.persona() != null) parts.add("‘나에 대해’ " + plan.personaLines() + "줄");
                if (notes > 0) parts.add("고정 기억 " + notes + "개");
                int left = plan.skipped().total() + (plan.memories().size() - memories) + refused;
                String message = parts.isEmpty() ? "새로 합친 것이 없어요" : "합쳤어요 · " + String.join(" · ", parts) + (left > 0 ? " · 뺀 것 " + left + "개" : "");
                boolean pinned = notes > 0;
                // The main chat learns of new pinned notes even when this screen is gone.
                activity.runOnUiThread(() -> { if (pinned) store.mainAssistant.contextChanged(); store.status(message); if (!activity.isDestroyed()) refresh.run(); });
            }
        }, "memory-backup-merge").start();
    }

    /** Copy the prompt to another AI, paste its answer, then tick which memories to keep. */
    void fromOtherAi() {
        Ui.Sheet sheet = ui.selectionSheet("다른 AI에서 기억 가져오기", "다른 AI가 알고 있는 내 이야기를 옮겨 와요");
        sheet.body.addView(ui.label("1. 아래 부탁을 복사해서 ChatGPT·Gemini·Claude 같은 다른 AI 대화에 붙여 넣어요.", 14, Ui.TEXT));
        TextView prompt = ui.label(MemoryBackup.PROMPT, 13, Ui.MUTED); prompt.setPadding(0, ui.dp(8), 0, 0); prompt.setTextIsSelectable(true); sheet.body.addView(prompt);
        sheet.body.addView(ui.actions(ui.chip("부탁 복사", this::copyPrompt)));
        TextView step = ui.label("2. 그 AI의 답을 아래에 붙여 넣어요.", 14, Ui.TEXT); step.setPadding(0, ui.dp(12), 0, ui.dp(8)); sheet.body.addView(step);
        EditText answer = ui.input("다른 AI의 답", 6, MemoryBackup.PASTE_LIMIT); sheet.body.addView(answer);
        TextView note = ui.label("비밀번호·인증번호처럼 보이는 줄과 이미 기억한 것은 빼고, 고른 것만 저장해요.", 12, Ui.MUTED); note.setPadding(0, ui.dp(8), 0, 0); sheet.body.addView(note);
        sheet.primary.setText("골라 보기");
        sheet.primary.setOnClickListener(v -> {
            MemoryBackup.Picked picked = MemoryBackup.pick(answer.getText().toString(), store.memories(), System.currentTimeMillis());
            if (picked.notes().isEmpty()) {
                note.setTextColor(Ui.ERROR);
                note.setText(answer.getText().toString().isBlank() ? "먼저 다른 AI의 답을 붙여 넣어 주세요."
                    : picked.skipped().total() > 0 ? "새로 저장할 기억이 없어요 · " + picked.skipped().text() : "답에서 기억을 찾지 못했어요. 목록을 그대로 붙여 넣어 주세요.");
                return;
            }
            sheet.dismiss(); tick(picked);
        });
        sheet.show();
    }
    private void copyPrompt() {
        activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(Brand.NAME, MemoryBackup.PROMPT));
        store.status("부탁을 복사했어요 · 다른 AI에 붙여 넣어 주세요");
    }
    private void tick(MemoryBackup.Picked picked) {
        Ui.Sheet sheet = ui.selectionSheet("저장할 기억 고르기", picked.notes().size() + "개 찾았어요 · 저장할 것만 체크해요");
        List<CheckBox> boxes = new ArrayList<>();
        for (String text : picked.notes()) {
            CheckBox box = new CheckBox(activity); box.setText(text); box.setChecked(true); box.setTextColor(Ui.TEXT); box.setTextSize(15); box.setPadding(ui.dp(6), ui.dp(10), 0, ui.dp(10));
            boxes.add(box); sheet.body.addView(box);
        }
        TextView note = ui.label(picked.skipped().total() > 0 ? "뺀 것: " + picked.skipped().text() : "비밀번호·인증번호처럼 보이는 것은 저장하지 않아요.", 12, Ui.MUTED);
        note.setPadding(0, ui.dp(10), 0, 0); sheet.body.addView(note);
        sheet.primary.setText("고른 기억 저장");
        sheet.primary.setOnClickListener(v -> {
            List<MemoryBackup.Item> chosen = new ArrayList<>();
            for (CheckBox box : boxes) if (box.isChecked()) chosen.add(new MemoryBackup.Item(Memory.ABOUT, box.getText().toString(), "", 0, 0));
            if (chosen.isEmpty()) { note.setTextColor(Ui.ERROR); note.setText("저장할 기억을 하나 이상 골라 주세요."); return; }
            int saved;
            long now = System.currentTimeMillis();
            try { saved = store.changeMemory(list -> MemoryBackup.addMemories(list, chosen, now)); } catch (Exception e) { note.setTextColor(Ui.ERROR); note.setText(e.getMessage()); return; }
            sheet.dismiss();
            store.status(saved == 0 ? "새로 저장한 기억이 없어요 · 이미 있거나 한도가 찼어요" : "기억 " + saved + "개를 저장했어요" + (chosen.size() > saved ? " · " + (chosen.size() - saved) + "개는 이미 있거나 한도가 차서 뺐어요" : ""));
            refresh.run();
        });
        sheet.footer.addView(ui.button("취소", false, sheet::dismiss), margin());
        sheet.show();
    }

    private LinearLayout.LayoutParams margin() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = ui.dp(8); return p; }
    private void post(Runnable action) { activity.runOnUiThread(() -> { if (!activity.isDestroyed()) action.run(); }); }
    private static String name(Uri document) { String id = DocumentsContract.getDocumentId(document); return id.substring(id.lastIndexOf('/') + 1); }
    private static String size(long bytes) { return bytes < 0 ? "" : bytes < 1024 ? bytes + "B" : String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0); }
}
