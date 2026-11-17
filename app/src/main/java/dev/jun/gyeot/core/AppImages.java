package dev.jun.gyeot.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Voice image requests enter the existing work/image queue, never the app's accessibility controls. */
public final class AppImages {
    public static final String FUNCTION = "app_generate_image";
    private AppImages() {}
    public static JSONObject declaration() {
        return obj("name", FUNCTION, "description", "Generate a NEW image in this app's work chat and gallery from the user's explicit spoken description. "
            + "Use this instead of phone_task, phone_action set_text or asking the user to type. A successful reply means queued, NOT an already generated image. "
            + "If the description was unclear, ask the user to say it again; never invent a subject. This does not change settings, send messages to people or edit an existing image.",
            "parameters", obj("type", "OBJECT", "properties", obj("batch_index", obj("type", "INTEGER", "description", "Zero-based item index ONLY when user explicitly requests multiple images. Default 0; repeat same index when retrying."), "prompt", obj("type", "STRING", "description", "The user's actual image description, preserving their language and details"),
                "aspect", obj("type", "STRING", "enum", arr("square", "portrait", "landscape", "9:16", "16:9", "4:3", "3:4"))), "required", arr("prompt")));
    }
    public static final String INSTRUCTIONS = " To create a new image inside this app, use app_generate_image with the user's actual visual description. "
        + "It sends the request directly to a new work conversation and saves the generated image in the gallery. You need not open a screen or use phone control first. "
        + "The own-app accessibility restriction does not prohibit app_generate_image or app_open_screen. Do not send a phone_task to type into this app's work composer. "
        + "If the user only says '작업 들어가서 이미지 생성할 거예요', ask what to draw. A noise/unclear transcript such as '¿Qué?' is not a prompt for any invented subject. "
        + "Ask them to repeat the description by voice, not to type manually. After queued:true say generation has started, never that it has finished. "
        + "When the user explicitly requests multiple images, call once per image with batch_index 0, 1, 2 and so on (maximum 12). Different requests may generate concurrently. Reuse the same index for a retry. "
        + "Use the user's requested ratio exactly; omit aspect when none is stated. Do not use this new-image tool to edit an existing photo.";
    public interface Submitter { String submit(String prompt, JSONObject picture) throws Exception; }
    public static final class Conversation {
        private final Map<String, JSONObject> calls = new LinkedHashMap<>();
        private final Map<Integer, JSONObject> batch = new LinkedHashMap<>();
        private String heard = ""; private long revision, queuedRevision = -1; private JSONObject queued;
        public void reset() { calls.clear(); batch.clear(); heard = ""; revision++; queuedRevision = -1; queued = null; }
        public String latestThread() { return queued == null ? "" : queued.optString("thread_id"); }
        public String heardText() { return heard; }
        public void heard(String text) {
            String value = text == null ? "" : text.strip();
            if (!value.isEmpty() && !value.equals(heard)) { heard = value; batch.clear(); revision++; }
        }
        public JSONObject submit(String id, JSONObject args, boolean codex, BooleanSupplier allowed, Submitter submitter) {
            if (!allowed.getAsBoolean()) return failure("취소된 이미지 요청이에요.");
            if (calls.containsKey(id)) return copy(calls.get(id));
            try {
                if (id == null || id.isBlank() || !(args.opt("prompt") instanceof String prompt) || prompt.isBlank() || prompt.length() > Drawing.PROMPT_LIMIT)
                    throw new IllegalArgumentException("그릴 내용을 구체적으로 말씀해 주세요.");
                for (java.util.Iterator<String> keys = args.keys(); keys.hasNext();) if (!java.util.Set.of("prompt", "aspect", "batch_index").contains(keys.next()))
                    throw new IllegalArgumentException("새 이미지 생성에 필요한 내용과 비율만 전달해 주세요.");
                String shape = args.optString("aspect", "square");
                if (!java.util.Set.of("square", "portrait", "landscape", "9:16", "16:9", "4:3", "3:4").contains(shape))
                    throw new IllegalArgumentException("이미지 비율을 다시 확인해 주세요.");
                if (unclear(prompt) || !heard.isEmpty() && unclear(heard))
                    throw new IllegalArgumentException("그릴 내용을 정확히 듣지 못했어요. 어떤 이미지를 만들지 다시 말씀해 주세요.");
                if (AppGallery.enlargeReference(heard))
                    throw new IllegalArgumentException("이 요청은 새 이미지 생성이 아니라 기존 이미지 확대예요. app_show_image를 사용하세요.");
                if (heard.matches("(?is).*(취소|그만|멈춰|그리지\\s*마|생성하지\\s*마|만들지\\s*마|번역해|설명해).*"))
                    throw new IllegalArgumentException("현재 발화는 새 이미지 생성 요청이 아니에요.");
                int batchIndex = GenerationPool.voiceIndex(heard, args);
                if (batch.containsKey(batchIndex)) { calls.put(id, batch.get(batchIndex)); return copy(batch.get(batchIndex)); }
                String spokenRatio = Drawing.aspect(heard); if (!spokenRatio.isEmpty()) shape = spokenRatio;
                JSONObject picture = Drawing.directArguments(obj("prompt", prompt.strip(), "aspect", shape, "edit", false, "codex", codex).toString());
                if (!allowed.getAsBoolean()) return failure("취소된 이미지 요청이에요.");
                String thread = submitter.submit(prompt.strip(), picture);
                if (thread == null || thread.isBlank()) return failure("이미지 작업을 시작하지 못했어요. 연결 상태와 작업창의 오류를 확인해 주세요.");
                queuedRevision = revision;
                queued = obj("ok", true, "queued", true, "completed", false, "thread_id", thread, "aspect", shape,
                    "note", "이미지 생성을 시작했어요. 작업 대화에서 진행 상태를, 완료 후 갤러리에서 결과를 확인할 수 있어요.");
                batch.put(batchIndex, copy(queued)); calls.put(id, queued);
                while (calls.size() > 32) calls.remove(calls.entrySet().iterator().next().getKey());
                return copy(queued);
            } catch (Exception e) { return failure(e.getMessage()); }
        }
    }
    private static JSONObject failure(String error) { return obj("ok", false, "queued", false, "completed", false, "error", error == null ? "이미지 요청을 처리하지 못했어요." : error); }
    private static boolean unclear(String value) {
        String text = value.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\p{Punct}¿¡]+", "");
        if (java.util.Set.of("", "qué", "que", "네", "응", "예", "뭐", "뭐라고", "잠깐", "잘못들었어").contains(text)) return true;
        String subject = text.replaceAll("들퍼레|앱|작업|화면|목록|들어가서|들어가|에서|이미지|그림|사진|생성|만들기|만들어|그려|할거예요|할거에요|할게요|해주세요|해줘|주세요|줘|하고싶어요|할래요|그릴거예요|을|를", "");
        return subject.isBlank();
    }
}
