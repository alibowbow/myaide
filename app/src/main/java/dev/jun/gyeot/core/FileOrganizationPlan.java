package dev.jun.gyeot.core;

import java.util.*;
import org.json.*;

/** Untrusted model output is data only: no paths, commands, source URIs or deletion verbs. */
public final class FileOrganizationPlan {
    public static final List<String> FOLDERS = List.of("음악", "사진", "영상", "문서", "기타");
    public record Item(String id, String folder) {}
    public static List<Item> parse(String json, Set<String> knownIds) throws Exception {
        if (json == null || json.length() > 65536) throw new IllegalArgumentException("정리 제안이 너무 커요.");
        JSONTokener token = new JSONTokener(json);
        JSONObject object = new JSONObject(token);
        if (token.nextClean() != 0) throw new IllegalArgumentException("정리 제안 뒤에 추가 데이터가 있어요.");
        if (object.length() != 1 || !object.has("copies")) throw new IllegalArgumentException("허용되지 않은 정리 제안이에요.");
        JSONArray copies = object.getJSONArray("copies");
        if (copies.length() > 200) throw new IllegalArgumentException("한 번에 200개까지 정리해요.");
        Set<String> seen = new HashSet<>(); List<Item> result = new ArrayList<>();
        for (int i = 0; i < copies.length(); i++) {
            JSONObject item = copies.getJSONObject(i);
            if (item.length() != 2 || !(item.get("id") instanceof String) || !(item.get("folder") instanceof String)) throw new IllegalArgumentException("잘못된 제안 형식이에요.");
            String id = item.getString("id"), folder = item.getString("folder");
            if (!knownIds.contains(id) || !seen.add(id) || !FOLDERS.contains(folder)) throw new IllegalArgumentException("알 수 없는 파일 또는 폴더 제안이에요.");
            result.add(new Item(id, folder));
        }
        return List.copyOf(result);
    }
    public static String safeName(String value) {
        if (value == null || value.isBlank() || value.length() > 200 || value.equals(".") || value.equals("..")
            || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("파일 이름에 경로나 제어 문자를 사용할 수 없어요.");
        return value;
    }
    private FileOrganizationPlan() {}
}
