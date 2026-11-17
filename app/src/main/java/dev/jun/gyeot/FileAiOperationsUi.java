package dev.jun.gyeot;

import android.app.*;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import dev.jun.gyeot.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Explicit local-read and separate provider-sharing consent; no background or replayed file commands. */
final class FileAiOperationsUi {
    private FileAiOperationsUi() {}
    static void show(Activity activity, FileExplorerRepository repo, String directory, Runnable changed) {
        if (repo == null || directory == null || !repo.contentAllowed()) {
            new AlertDialog.Builder(activity).setTitle("들퍼레 안에서만 가능해요").setMessage("AI 파일 읽기·쓰기는 지정한 들퍼레 폴더 안에서만 가능해요. 다른 폴더에서는 목록·이름 검색만 할 수 있어요.").setPositiveButton("확인", null).show(); return;
        }
        new Session(activity, repo, directory, changed).start();
    }
    /** Chat handoff still goes through the existing target/read/provider/write confirmations. */
    static Session showChat(Activity activity,FileExplorerRepository repo,String directory,FileAiOperations.Mode mode,String instruction,Runnable changed,java.util.function.BiConsumer<String,String> result) {
        return showChat(activity,repo,directory,mode,instruction,changed,result,null);
    }
    static Session showChat(Activity activity,FileExplorerRepository repo,String directory,FileAiOperations.Mode mode,String instruction,Runnable changed,java.util.function.BiConsumer<String,String> result,Completion completion) {
        if(repo==null||directory==null||!repo.contentAllowed()){result.accept("읽기·쓰기 불가","지정한 들퍼레 폴더를 별도로 연결해 주세요. 다른 위치는 목록만 탐색해요.");return null;}
        Session session=completion==null?new Session(activity,repo,directory,changed):new Session(activity,repo,directory,changed,completion);session.initialInstruction=instruction;session.observer=result;
        if(mode==FileAiOperations.Mode.CREATE)session.compose(mode,null);else session.choose(mode);
        return session;
    }
    private interface Task<T> { T run() throws Exception; }
    interface Completion { String request(AppStore store, Connection connection, String model, String effort, String instructions, String input, Runnable guard, AtomicReference<Runnable> cancel) throws Exception; }
    static final class Session implements Application.ActivityLifecycleCallbacks {
        final Activity activity; final FileExplorerRepository repo; final FileAiOperations operations; final String directory; final Runnable changed; final Ui ui; final AppStore store;
        final ExecutorService worker = Executors.newSingleThreadExecutor(); final AtomicBoolean cancelled = new AtomicBoolean(); final AtomicReference<Runnable> network = new AtomicReference<>();
        final Completion completion; final Set<AlertDialog> dialogs = new HashSet<>(); boolean closed, busy, saving, reported; String initialInstruction=""; java.util.function.BiConsumer<String,String> observer;
        Session(Activity activity, FileExplorerRepository repo, String directory, Runnable changed) { this(activity, repo, directory, changed, FileAiOperationsUi::complete); }
        Session(Activity activity, FileExplorerRepository repo, String directory, Runnable changed, Completion completion) {
            this.completion = completion; this.activity = activity; this.repo = repo; this.directory = directory; this.changed = changed; operations = new FileAiOperations(repo); ui = new Ui(activity); store = ((GyeotApp) activity.getApplication()).store();
            activity.getApplication().registerActivityLifecycleCallbacks(this);
        }
        void start() {
            show(new AlertDialog.Builder(activity).setTitle("AI 텍스트 작업 · 들퍼레만").setItems(new String[]{"파일 읽기 · 요약 / 질문", "새 텍스트 파일 작성", "원본 보존 · 새 수정본 작성"}, (d, which) -> {
                if (which == 1) compose(FileAiOperations.Mode.CREATE, null);
                else choose(which == 0 ? FileAiOperations.Mode.ASK : FileAiOperations.Mode.REVISE);
            }).setNegativeButton("닫기", (d, w) -> close()));
        }
        void choose(FileAiOperations.Mode mode) {
            run("현재 폴더의 텍스트 목록 확인 중…", () -> {
                operations.requireDirectory(directory, cancelled::get); List<FileExplorerRepository.Entry> eligible = new ArrayList<>();
                for (var e : repo.list(directory, cancelled::get)) if (!e.directory() && FileAiOperations.supported(e.name())) eligible.add(e);
                return eligible;
            }, items -> {
                if (items.isEmpty()) { finish("지원하는 텍스트가 없어요", "현재 폴더에는 .txt, .md, .csv, .json, .log 파일이 없어요. 탐색기에서 원하는 폴더로 들어간 뒤 다시 열어 주세요. PDF·사진·음악·Office 파일 편집은 지원하지 않아요."); return; }
                String[] names = items.stream().map(e -> e.name() + " · " + FileExplorerActivity.size(e.size())).toArray(String[]::new);
                show(new AlertDialog.Builder(activity).setTitle("이 폴더에서 파일 선택").setItems(names, (d,w) -> localRead(mode, items.get(w))).setNegativeButton("취소", (d,w) -> close()));
            });
        }
        void localRead(FileAiOperations.Mode mode, FileExplorerRepository.Entry entry) {
            show(new AlertDialog.Builder(activity).setTitle("휴대폰에서 파일 읽기").setMessage(entry.name() + "\n\nUTF-8 텍스트 최대 128KB를 이 화면에서 읽어요. 아직 어떤 AI에도 보내지 않아요. 다음 화면에서 원문을 보고 별도로 전송에 동의할 수 있어요.")
                .setNegativeButton("취소", (d,w) -> close()).setPositiveButton("기기에서 읽기", (d,w) -> run("텍스트 읽는 중…", () -> operations.read(entry, cancelled::get), source -> compose(mode, source))));
        }
        void compose(FileAiOperations.Mode mode, FileAiOperations.Snapshot source) {
            LinearLayout column = ui.column(); column.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(12));
            ui.add(column, ui.label(mode == FileAiOperations.Mode.CREATE ? "새 파일 작성 · 현재 폴더에 저장" : "선택한 파일: " + source.entry().name(), 14, Ui.TEXT), 8);
            ui.add(column, ui.label(".txt · .md · .csv · .json · .log / UTF-8 128KB 이하\n기존 파일을 덮어쓰지 않아요. 수정은 새 파일로 저장해요.", 12, Ui.MUTED), 10);
            if (source != null) {
                Button original = ui.chip("읽은 원문 보기 · 외부 전송 없음", () -> textDialog("휴대폰에서 읽은 원문", source.text(), null, null, false)); ui.add(column, original, 10);
            }
            EditText instruction = ui.input(mode == FileAiOperations.Mode.ASK ? "예: 이 메모를 세 줄로 요약해 줘" : mode == FileAiOperations.Mode.REVISE ? "예: 맞춤법을 고쳐 수정본으로 저장해 줘" : "예: 내일 준비할 체크리스트를 만들어 줘", 4, 4000); instruction.setText(initialInstruction); ui.add(column, instruction, 8);
            show(new AlertDialog.Builder(activity).setTitle(mode == FileAiOperations.Mode.ASK ? "읽은 파일에 질문" : "AI 텍스트 작성").setView(column).setNegativeButton("취소", (d,w) -> close()).setPositiveButton("AI 선택·전송 확인", (d,w) -> {
                String request = instruction.getText().toString().trim();
                if (request.isEmpty()) { finish("지시가 비어 있어요", "아무 내용도 전송하거나 저장하지 않았어요. 지시를 입력해서 다시 시작해 주세요."); return; }
                chooseModel(mode, source, request);
            }));
        }
        void chooseModel(FileAiOperations.Mode mode, FileAiOperations.Snapshot source, String instruction) {
            FileExplorerModelUi.show(activity,store,()->!closed&&!busy,dialog->{dialogs.add(dialog);dialog.setOnCancelListener(d->close());dialog.setOnDismissListener(d->dialogs.remove(dialog));dialog.show();Button negative=dialog.getButton(AlertDialog.BUTTON_NEGATIVE);if(negative!=null)negative.setOnClickListener(v->{dialog.dismiss();close();});Button positive=dialog.getButton(AlertDialog.BUTTON_POSITIVE);if(positive!=null&&"닫기".contentEquals(positive.getText()))positive.setOnClickListener(v->{dialog.dismiss();close();});},()->{
                ScopedModels selected=ScopedModels.explorer(store);String engine=selected.engine(),model=selected.model(engine),effort=selected.effectiveEffort(engine,model);
                store.settings.edit().putString("fileAiProvider",engine).apply();
                run("AI 연결 확인 중…",()->Connection.capture(store,engine),connection->consent(mode,source,instruction,engine,model,effort,connection));
            });
        }
        void consent(FileAiOperations.Mode mode, FileAiOperations.Snapshot source, String instruction, String engine, String model, String effort, Connection connection) {
            String notice = "받는 곳: " + WorkBook.author(engine) + "\n선택 모델: " + model + "\n\n이 지시" + (source == null ? "만" : "와 아래 선택한 파일명·원문 전체") + "를 해당 AI 서비스에 전송해요. URI·폴더 경로·다른 파일 목록은 보내지 않아요. 웹·URL 읽기·코드·파일 도구는 사용하지 않아요. 요금이 발생할 수 있어요. 동의는 이 요청 한 번만 적용돼요.\n\n지시: " + instruction + (source == null ? "" : "\n\n전송할 파일명: " + source.entry().name() + "\n전송할 원문:\n" + source.text());
            textDialog("AI로 보낼 내용 확인", notice, "동의하고 요청", () -> run("AI 응답 기다리는 중…", () -> {
                operations.requireDirectory(directory, cancelled::get); if (source != null) operations.unchanged(source, cancelled::get);
                Runnable guard = () -> { try { operations.requireDirectory(directory, cancelled::get); connection.check(store); if (!engine.equals(ScopedModels.explorer(store).engine()) || !store.ready(engine) || !model.equals(ScopedModels.explorer(store).model(engine)) || !effort.equals(ScopedModels.explorer(store).effectiveEffort(engine,model))) throw new IOException("선택한 AI 연결이나 모델이 바뀌었어요. 다시 확인해 주세요."); } catch (Exception error) { throw new CancellationException(error.getMessage()); } };
                guard.run(); String payload = FileAiOperations.request(mode, instruction, source);
                String response = completion.request(store, connection, model, effort, FileAiOperations.instructions(mode), payload, guard, network);
                guard.run(); return FileAiOperations.parse(response, mode, source);
            }, proposal -> preview(proposal, source)), true);
        }
        void preview(FileAiOperations.Proposal proposal, FileAiOperations.Snapshot source) {
            if (proposal.mode() == FileAiOperations.Mode.ASK) { finish("AI 답변 · 파일 변경 없음", proposal.text()); return; }
            String text = "새 파일: " + proposal.name() + "\n위치: 현재 들퍼레 하위 폴더\n" + (source == null ? "새 텍스트를 만들어요." : "원본 " + source.entry().name() + "은 그대로 두고 새 수정본을 만들어요.") + "\n아직 저장하지 않았어요. 같은 이름은 덮어쓰지 않아요. 저장 중 중단되면 미완성 새 파일이 남을 수 있어요.\n\n저장할 내용:\n" + proposal.text();
            textDialog("저장 전 미리보기", text, "새 파일 저장", () -> { saving = true; run("새 파일 저장·검증 중…", () -> operations.save(directory, proposal, source, cancelled::get), receipt -> {
                saving = false; changed.run(); finish("저장 검증 완료 · 원본 보존", receipt.name() + "\n" + receipt.bytes() + " bytes\nURI: " + receipt.uri() + "\nSHA-256: " + receipt.sha256());
            }); }, true);
        }
        <T> void run(String label, Task<T> task, java.util.function.Consumer<T> success) {
            if (closed || busy) return; busy = true;
            AlertDialog progress = show(new AlertDialog.Builder(activity).setMessage(label).setNegativeButton("취소", (d,w) -> close()));
            worker.execute(() -> { try { T value = task.run(); activity.runOnUiThread(() -> { progress.dismiss(); busy = false; if (!closed) success.accept(value); else if (saving) afterInterruptedSave(); }); }
                catch (Exception error) { activity.runOnUiThread(() -> { progress.dismiss(); busy = false; if (!closed) { saving = false; changed.run(); finish("중단 · 완료되지 않았어요", String.valueOf(error.getMessage())); } else if (saving) afterInterruptedSave(); }); } });
        }
        AlertDialog show(AlertDialog.Builder builder) {
            AlertDialog dialog = builder.create(); dialogs.add(dialog); dialog.setOnCancelListener(d -> close()); dialog.setOnDismissListener(d -> dialogs.remove(dialog)); dialog.show(); return dialog;
        }
        void textDialog(String title, String text, String action, Runnable yes, boolean closeOnCancel) {
            ScrollView scroll = new ScrollView(activity); TextView body = ui.label(text, 14, Ui.TEXT); body.setTextIsSelectable(true); body.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12)); scroll.addView(body);
            AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setNegativeButton(closeOnCancel ? "취소" : "닫기", (d,w) -> { if (closeOnCancel) close(); });
            if (action != null) dialog.setPositiveButton(action, (d,w) -> yes.run()); show(dialog);
        }
        void finish(String title, String text) { if(!reported&&observer!=null){reported=true;observer.accept(title,text);} textDialog(title, text, "닫기", this::close, true); }
        void afterInterruptedSave() {
            saving = false;
            if (!activity.isFinishing() && !activity.isDestroyed()) { changed.run(); Toast.makeText(activity, "저장을 중단했어요. 원본은 그대로이며 미완성 새 파일이 남아 있을 수 있어요. 목록을 확인해 주세요.", Toast.LENGTH_LONG).show(); }
        }
        void close() {
            if (closed) return; closed = true; if(!reported&&observer!=null){reported=true;observer.accept("작업 취소","완료로 처리하지 않았어요. 원본은 보존하며, 저장 도중 중단했다면 미완성 새 파일이 남을 수 있어요.");} cancelled.set(true); Runnable abort = network.getAndSet(null); if (abort != null) new Thread(abort, "file-ai-cancel").start(); worker.shutdownNow();
            for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss(); activity.getApplication().unregisterActivityLifecycleCallbacks(this);
        }
        @Override public void onActivityDestroyed(Activity a) { if (a == activity) close(); }
        @Override public void onActivityStopped(Activity a) { if (a == activity) close(); }
        @Override public void onActivityCreated(Activity a, Bundle state) {} @Override public void onActivityStarted(Activity a) {} @Override public void onActivityResumed(Activity a) {} @Override public void onActivityPaused(Activity a) {} @Override public void onActivitySaveInstanceState(Activity a, Bundle state) {}
    }
    /** Captured before showing provider-sharing consent. Secrets stay only in memory, never in UI/logs/files. */
    static final class Connection {
        final String engine, identity, key;
        private Connection(String engine, String identity, String key) { this.engine=engine; this.identity=identity; this.key=key; }
        static Connection capture(AppStore store, String engine) throws Exception {
            if (!store.ready(engine)) throw new IOException("AI 연결을 확인해 주세요.");
            String key = WorkBook.CLAUDE.equals(engine) ? store.claude.key() : WorkBook.GEMINI.equals(engine) ? store.gemini.key() : "";
            String identity = switch(engine) {
                case WorkBook.CHATGPT -> Long.toString(store.openai.workFormatGeneration());
                case WorkBook.GROK -> store.grok.connectionEpoch();
                case WorkBook.NIM -> Long.toString(store.nim.generation());
                case WorkBook.CLAUDE, WorkBook.GEMINI -> FileAiOperations.hash(key.getBytes(StandardCharsets.UTF_8));
                default -> throw new IOException("지원하지 않는 AI예요.");
            };
            return new Connection(engine, identity, key);
        }
        void check(AppStore store) throws Exception { if (!identity.equals(capture(store, engine).identity)) throw new IOException("AI 계정이나 키가 바뀌었어요. 새 연결로 전송하려면 다시 동의해 주세요."); }
    }
    static String complete(AppStore store, Connection connection, String model, String effort, String instructions, String input, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        connection.check(store); guard.run();
        return switch (connection.engine) {
            case WorkBook.CHATGPT -> OpenAiClient.complete(store.openai, model, web -> instructions, input, false, effort, "standard", guard, cancel).text;
            case WorkBook.CLAUDE -> claude(connection.key, model, store.claude.info(model), instructions, input, effort, guard, cancel);
            case WorkBook.GROK -> GrokWorkClient.complete(store.grok, model, web -> instructions, input, false, effort, guard, cancel).text;
            case WorkBook.NIM -> NimClient.complete(store.nim, Long.parseLong(connection.identity), model, instructions, input, guard, cancel).text;
            case WorkBook.GEMINI -> gemini(connection.key, model, instructions, input, effort, guard, cancel);
            default -> throw new IOException("선택한 AI를 지원하지 않아요. 다른 AI로 자동 전송하지 않아요.");
        };
    }
    static JSONObject claudeRequest(String model, JSONObject info, String instructions, String input, String effort) {
        return ClaudeProtocol.request(model, info, instructions, ClaudeProtocol.ask(input), 32000, effort, false, false, false, 0, "", false, false);
    }
    private static String claude(String key, String model, JSONObject info, String instructions, String input, String effort, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        byte[] body=claudeRequest(model, info, instructions, input, effort).toString().getBytes(StandardCharsets.UTF_8);
        guard.run();
        try(OpenAiAccount.Http.Exchange response=OpenAiAccount.Http.open("POST",ClaudeProtocol.MESSAGES,Map.of("x-api-key",key,"anthropic-version",ClaudeProtocol.VERSION,"Content-Type","application/json","Accept","text/event-stream"),body,120_000,cancel)) {
            if(response.status>=300)throw new IOException("Claude 요청 오류 HTTP "+response.status+". 다른 모델로 대체하거나 자동 재전송하지 않았어요.");
            ClaudeProtocol.Stream stream=new ClaudeProtocol.Stream();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(response.body,StandardCharsets.UTF_8))) {
                String line;long total=0;
                while((line=reader.readLine())!=null) { guard.run();total+=line.length();if(total>FileAiOperations.MAX_BYTES*16L)throw new IOException("AI 응답 한도를 넘었어요.");stream.line(line);if(stream.failure()!=null)throw new IOException(stream.failure());if(stream.completed())break; }
            }
            guard.run();if(!stream.completed()||!"end_turn".equals(stream.stopReason())||stream.toolCalls(0).length()!=0||stream.files().length()!=0)throw new IOException("완전한 텍스트 응답이 아니에요. 저장하지 않았어요.");return stream.text();
        } finally { cancel.set(null); }
    }
    /** Unlike general Gemini complete(), all hosted tools including URL-context are disabled here. */
    static JSONObject geminiRequest(String model, String instructions, String input, String effort) throws Exception {
        JSONArray contents = new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", input))));
        return GeminiWork.request(model, instructions, contents, false, false, false, null, effort);
    }
    private static String gemini(String key, String model, String instructions, String input, String effort, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        JSONObject request = geminiRequest(model, instructions, input, effort);
        guard.run();
        try (OpenAiAccount.Http.Exchange response = GeminiWorkClient.stream(key, model, request, 120_000, cancel)) {
            if (response.status >= 300) throw new IOException("Gemini 요청 오류 HTTP " + response.status + ". 자동으로 재전송하지 않았어요.");
            GeminiWork.Stream stream = new GeminiWork.Stream();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body, StandardCharsets.UTF_8))) {
                String line; long total = 0;
                while ((line = reader.readLine()) != null) { guard.run(); total += line.length(); if (total > FileAiOperations.MAX_BYTES * 16L) throw new IOException("AI 응답 한도를 넘었어요."); stream.line(line); if (stream.failure() != null) throw new IOException(stream.failure()); }
            }
            guard.run(); if (!stream.finished() || stream.calls().length() != 0 || !stream.note().isEmpty()) throw new IOException("완전한 텍스트 응답이 아니에요. 저장하지 않았어요."); return stream.text();
        } finally { cancel.set(null); }
    }
}
