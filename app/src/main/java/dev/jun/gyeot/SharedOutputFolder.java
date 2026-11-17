package dev.jun.gyeot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;

/** A user-selected subfolder, not access to the storage volume or a fabricated filesystem path. */
final class SharedOutputFolder {
    static final String AUTHORITY = "com.android.externalstorage.documents";
    static final String ROOT_ID = "primary:들퍼레";
    private static final String PREFS = "shared-output-folder", KEY = "tree", MANAGED = "managed-root", ACTIVATED = "managed-authorized";
    private SharedOutputFolder() {}

    static void chooseFolder(Activity activity, int requestCode) { SharedFolderAccessUi.chooseOutput(activity,requestCode); }
    static void launchPicker(Activity activity, int requestCode) {
        Uri initial=DocumentsContract.buildDocumentUri(AUTHORITY,"primary:");
        try { selectedTree(activity); initial=DocumentsContract.buildDocumentUri(AUTHORITY,ROOT_ID); } catch(IOException notConnected) {}
        activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial), requestCode);
    }
    static boolean matches(Uri tree, String id) {
        try { return "content".equals(tree.getScheme()) && AUTHORITY.equals(tree.getAuthority()) && tree.getQuery() == null && tree.getFragment() == null
            && tree.getPathSegments().size() == 2 && "tree".equals(tree.getPathSegments().get(0)) && id.equals(DocumentsContract.getTreeDocumentId(tree))
            && tree.equals(DocumentsContract.buildTreeDocumentUri(AUTHORITY, id)); }
        catch (RuntimeException error) { return false; }
    }
    record Selection(boolean accepted,String message) {}
    static String acceptFolder(Context context, Intent data, int resultCode) { return acceptSelection(context,data,resultCode).message(); }
    static Selection acceptSelection(Context context, Intent data, int resultCode) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null)
            return new Selection(false,"폴더 선택을 취소했어요. 기존 저장 위치는 바뀌지 않았어요.");
        Uri tree = data.getData();
        if (!matches(tree, ROOT_ID)) return new Selection(false,"내장 저장공간 바로 아래에 들퍼레 폴더를 만들고 그 폴더를 선택해 주세요. Download 안의 폴더나 저장공간 전체는 선택하지 마세요.");
        try {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (flags != (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                return new Selection(false,"폴더 읽기·쓰기 권한이 필요해요. 폴더를 다시 선택해 주세요.");
            context.getContentResolver().takePersistableUriPermission(tree, flags);
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tree.toString()).putBoolean(MANAGED,false).commit())
                throw new IOException("설정을 저장하지 못했어요.");
            return new Selection(true,"새 작업 파일은 내장 저장공간/들퍼레, 새 음악은 들퍼레/음악에 저장해요. 기존 파일은 옮기거나 삭제하지 않았어요.");
        } catch (Exception error) { return new Selection(false,"폴더 권한을 저장하지 못했어요. 다시 선택해 주세요."); }
    }
    static boolean managedSelected(Context context) { return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean(MANAGED,false); }
    static boolean managedAuthorized(Context context) { return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean(ACTIVATED,false); }
    static void activateManaged(Context context) throws IOException {
        ManagedSharedStorage.tree(context); // Real root exists and current OS access is verified first.
        if(!context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean(MANAGED,true).putBoolean(ACTIVATED,true).commit()) throw new IOException("저장 위치 설정을 기록하지 못했어요.");
    }
    static Uri selectedTree(Context context) throws IOException {
        if(managedSelected(context)&&managedAuthorized(context)) try { return ManagedSharedStorage.tree(context); } catch(IOException|RuntimeException revoked) { /* Keep an independent valid SAF connection usable. */ }
        return selectedSafTree(context);
    }
    static Uri selectedSafTree(Context context) throws IOException {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
        if (value.isEmpty()) throw new IOException("저장 폴더가 아직 연결되지 않았어요. 먼저 내 휴대폰 > 저장 위치에서 들퍼레 폴더를 만들거나 선택해 주세요.");
        Uri tree = Uri.parse(value);
        if (!matches(tree, ROOT_ID)) throw new IOException("내장 저장공간 바로 아래 들퍼레 폴더를 다시 선택해 주세요.");
        requireGrant(context, tree, true);
        return tree;
    }
    static void requireGrant(Context context, Uri tree, boolean write) throws IOException {
        if(tree != null && ManagedSharedStorage.AUTHORITY.equals(tree.getAuthority())) {
            if(!managedAuthorized(context) || !tree.equals(ManagedSharedStorage.tree(context))) throw new IOException("앱 저장 폴더 연결을 다시 확인해 주세요.");
            return;
        }
        for (UriPermission grant : context.getContentResolver().getPersistedUriPermissions())
            if (tree.equals(grant.getUri()) && grant.isReadPermission() && (!write || grant.isWritePermission())) return;
        throw new IOException("폴더 권한이 만료됐어요. Android 폴더 선택 화면에서 다시 허용해 주세요.");
    }
    /** Content access is narrower than an Android grant: metadata search never expands this boundary. */
    static void requireContentAccess(Context context, Uri uri) throws IOException {
        if(uri != null && ManagedSharedStorage.AUTHORITY.equals(uri.getAuthority())) {
            if(!managedAuthorized(context)) throw new IOException("앱 저장 폴더 연결을 먼저 완료해 주세요.");
            ManagedSharedStorage.guardUri(context,uri); return;
        }
        Uri selected = selectedSafTree(context); // A real independent persisted read/write grant must still exist.
        if (uri == null || !"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority())) throw new IOException("파일 내용 읽기·쓰기는 지정한 들퍼레 폴더 안에서만 가능해요. 다른 폴더는 이름·종류·크기·날짜 검색만 허용해요.");
        java.util.List<String> segments = uri.getPathSegments();
        if (uri.getQuery() != null || uri.getFragment() != null || segments.size() != 4 || !"tree".equals(segments.get(0)) || !ROOT_ID.equals(segments.get(1)) || !"document".equals(segments.get(2)))
            throw new IOException("허용된 폴더의 문서 주소가 아니에요.");
        String id;
        try { id = DocumentsContract.getDocumentId(uri); }
        catch (RuntimeException error) { throw new IOException("파일 주소를 확인하지 못했어요.", error); }
        if (!uri.equals(DocumentsContract.buildDocumentUriUsingTree(selected, id))) throw new IOException("표준 문서 주소가 아니에요.");
        if (!id.equals(ROOT_ID) && !id.startsWith(ROOT_ID + "/")) throw new IOException("지정한 들퍼레 폴더 밖의 파일이에요.");
        for (String part : id.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf('\\') >= 0 || part.chars().anyMatch(c -> Character.isISOControl(c)))
            throw new IOException("안전하지 않은 파일 주소예요.");
    }
    static boolean canAccess(Context context, Uri uri) {
        try { requireContentAccess(context, uri); return true; }
        catch (Exception error) { return false; }
    }
    static String status(Context context) {
        try {
            Uri tree=selectedTree(context);
            return "저장 위치: 내장 공유 저장소/들퍼레\n새 작업 파일과 음악·이미지·영상은 이 폴더에 저장해요.\n연결 방식: "+(ManagedSharedStorage.AUTHORITY.equals(tree.getAuthority())?"Android 모든 파일 접근 승인 · 앱 내부는 지정 폴더로 제한":"선택한 폴더의 SAF 권한")+"\n기존 Download/들퍼레는 검증 복사·참조 연결을 별도로 실행해요. 원본은 삭제하지 않아요.";
        } catch (IOException error) {
            return error.getMessage()+"\n저장소 설정에서 Android 권한을 직접 허용하면 앱이 들퍼레 폴더를 만들어요. 허용하지 않아도 기존 폴더 연결과 공개 목록 탐색은 유지해요.";
        }
    }
}
