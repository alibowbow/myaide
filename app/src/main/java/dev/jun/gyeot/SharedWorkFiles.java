package dev.jun.gyeot;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import dev.jun.gyeot.core.FileOrganizationPlan;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Crash-safe private staging for generated work files, with root-only, verified public export. */
final class SharedWorkFiles {
    private SharedWorkFiles() {}
    private static File directory(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), "pending-work-exports");
        if(java.nio.file.Files.isSymbolicLink(dir.toPath()))throw new IOException("보관 폴더의 링크는 사용할 수 없어요.");
        if (!dir.isDirectory()) { if(!dir.mkdir())throw new IOException("결과 파일을 임시 보관하지 못했어요.");MigrationLedger.syncDirectory(dir.getParentFile()); }
        return dir;
    }
    static synchronized Uri save(Context context, String name, String mime, byte[] bytes) throws Exception {
        FileOrganizationPlan.safeName(name);
        String id = UUID.randomUUID().toString(); File dir = directory(context), body = new File(dir, id + ".data"), record = new File(dir, id + ".json");
        // Private bytes survive a missing/revoked picker grant; never fall back to Download.
        try (FileOutputStream out = new FileOutputStream(body)) { out.write(bytes); out.getFD().sync(); }
        JSONObject state = new JSONObject().put("id", id).put("name", name).put("mime", mime).put("bytes", bytes.length)
            .put("sha256", FileExplorerRepository.hex(MessageDigest.getInstance("SHA-256").digest(bytes))).put("state", "prepared");
        write(record, state);
        try { return export(context, record, state, () -> false); }
        catch (Exception error) { throw new IOException("결과 파일은 앱 내부에 보관했어요. 내 휴대폰 > 저장 위치 > 대기 파일 저장에서 확인해 주세요. " + error.getMessage(), error); }
    }
    static int pendingCount(Context context) {
        File dir = new File(context.getFilesDir(), "pending-work-exports"); File[] files = dir.listFiles((d,n) -> n.endsWith(".json")); int count=0;
        if (files != null) for (File file : files) try { if (!"verified".equals(read(file).optString("state"))) count++; } catch (Exception error) { count++; }
        return count;
    }
    static synchronized String exportPending(Context context, BooleanSupplier cancelled) throws Exception {
        SharedOutputFolder.selectedTree(context); File[] records = directory(context).listFiles((d,n) -> n.endsWith(".json")); int saved=0, held=0;
        if (records == null || records.length == 0) return "저장을 기다리는 파일이 없어요.";
        Arrays.sort(records, Comparator.comparing(File::getName));
        for (File record : records) {
            FileExplorerRepository.check(cancelled); JSONObject state = read(record);
            if ("verified".equals(state.optString("state"))) continue;
            if (!"prepared".equals(state.optString("state"))) { held++; continue; }
            try { export(context, record, state, cancelled); saved++; }
            catch (Exception error) { if (cancelled.getAsBoolean()) throw error; held++; }
        }
        return saved + "개를 내장 저장공간/들퍼레에 저장·검증했어요. " + held + "개는 보류 중이에요. 중단된 쓰기는 덮어쓰거나 자동 재실행하지 않아요. 앱 내부 원본은 보존해요.";
    }
    private static Uri export(Context context, File record, JSONObject state, BooleanSupplier cancelled) throws Exception {
        if (!"prepared".equals(state.getString("state"))) throw new IOException("이전 저장 결과가 불확실해 자동 재실행하지 않아요. 저장 폴더와 앱 내부 보관본을 확인해 주세요.");
        String id = state.getString("id");
        if (!id.matches("[a-f0-9-]{36}")) throw new IOException("보관 기록 식별자가 잘못됐어요.");
        File body = new File(record.getParentFile(), id + ".data");
        if (!body.isFile() || body.length() != state.getLong("bytes") || !hash(body, cancelled).equals(state.getString("sha256"))) throw new IOException("앱 내부 보관본 검증에 실패했어요.");
        Uri tree = SharedOutputFolder.selectedTree(context); FileExplorerRepository target = new FileExplorerRepository(context, tree);
        List<FileExplorerRepository.Entry> existing = target.list(target.root, cancelled); Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (FileExplorerRepository.Entry entry : existing) { ids.add(entry.id()); names.add(entry.name()); }
        String name = state.getString("name"); FileOrganizationPlan.safeName(name);
        if (names.contains(name)) {
            int dot=name.lastIndexOf('.'); String suffix="-"+id.substring(0,8);
            name=dot>0?name.substring(0,dot)+suffix+name.substring(dot):name+suffix;
            FileOrganizationPlan.safeName(name);
            if (names.contains(name)) throw new IOException("같은 이름이 있어 덮어쓰지 않았어요.");
        }
        FileExplorerRepository.check(cancelled); SharedOutputFolder.requireContentAccess(context, target.document(target.root));
        state.put("state", "creating").put("targetTree", tree.toString()).put("outputName", name); write(record, state);
        Uri output = DocumentsContract.createDocument(context.getContentResolver(), target.document(target.root), state.getString("mime"), name);
        if (output == null) throw new IOException("저장 파일을 만들지 못했어요.");
        state.put("state", "created").put("uri", output.toString()); write(record, state);
        SharedOutputFolder.requireContentAccess(context, output);
        String outputId = DocumentsContract.getDocumentId(output);
        if (!outputId.equals(target.root + "/" + name) || ids.contains(outputId)) throw new IOException("기존 파일 또는 다른 위치를 가리켜 쓰기를 중단했어요.");
        boolean verifiedName=false;
        for (FileExplorerRepository.Entry entry : target.list(target.root, cancelled)) if (entry.id().equals(outputId) && entry.name().equals(name) && !entry.directory()) verifiedName=true;
        if (!verifiedName) throw new IOException("새 파일의 위치와 이름을 확인하지 못했어요.");
        SharedOutputFolder.requireContentAccess(context, output);
        android.os.ParcelFileDescriptor descriptor=context.getContentResolver().openFileDescriptor(output,"w");
        if(descriptor==null)throw new IOException("새 파일을 열지 못했어요.");
        try (InputStream in = new FileInputStream(body); android.os.ParcelFileDescriptor.AutoCloseOutputStream out = new android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
            byte[] buffer=new byte[65536]; int n;
            while(true){FileExplorerRepository.check(cancelled);SharedOutputFolder.requireContentAccess(context,output);
                if(!tree.equals(SharedOutputFolder.selectedTree(context)))throw new IOException("저장 위치가 바뀌었어요.");
                n=in.read(buffer);if(n==-1)break;FileExplorerRepository.check(cancelled);SharedOutputFolder.requireContentAccess(context,output);out.write(buffer,0,n);
            }
            FileExplorerRepository.check(cancelled);SharedOutputFolder.requireContentAccess(context,output);out.flush();out.getFD().sync();SharedOutputFolder.requireContentAccess(context,output);
        }
        SharedOutputFolder.requireContentAccess(context, output);
        if (!state.getString("sha256").equals(target.hash(output, Long.MAX_VALUE, cancelled))) throw new IOException("저장 검증에 실패했어요. 원본과 미완성 파일은 보존했어요.");
        FileExplorerRepository.check(cancelled);SharedOutputFolder.requireContentAccess(context,output);state.put("state", "verified"); write(record,state);
        // Private staging is deliberately retained: an interrupted caller may not yet have saved the returned URI.
        return output;
    }
    private static String hash(File file, BooleanSupplier cancelled) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try (InputStream in=new FileInputStream(file)) { byte[] b=new byte[65536]; int n; while((n=in.read(b))!=-1) { FileExplorerRepository.check(cancelled); digest.update(b,0,n); } }
        return FileExplorerRepository.hex(digest.digest());
    }
    private static JSONObject read(File file) throws Exception {
        try (InputStream in = new AtomicFile(file).openRead()) { return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
    }
    private static void write(File file, JSONObject state) throws IOException {
        AtomicFile atomic=new AtomicFile(file); FileOutputStream out=null;
        try {
            for(String suffix:List.of("",".bak",".new"))if(java.nio.file.Files.isSymbolicLink(new File(file+suffix).toPath()))throw new IOException("보관 기록 링크에는 쓰지 않아요.");
            byte[] expected=state.toString().getBytes(StandardCharsets.UTF_8);out=atomic.startWrite();out.write(expected);out.flush();out.getFD().sync();atomic.finishWrite(out);out=null;
            MigrationLedger.syncDirectory(file.getParentFile());
            try(InputStream check=atomic.openRead()){if(!Arrays.equals(expected,check.readNBytes(expected.length+1)))throw new IOException("보관 기록 저장을 확인하지 못했어요.");}
        }
        catch (Exception error) { if(out!=null)atomic.failWrite(out); throw new IOException("파일 보관 기록을 저장하지 못했어요.",error); }
    }
}
