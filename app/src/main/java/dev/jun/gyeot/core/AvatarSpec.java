package dev.jun.gyeot.core;

import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * The assistant's living avatar (open-bot-avatar), stored as JSON next to the profile picture. Three kinds:
 * - gallery: one of the bundled open-bot-avatar shapes;
 * - made: a shape an AI drew from the user's description (the open-bot-avatar design prompt);
 * - picture: the user's own profile picture (made with GPT image or uploaded) as the character: its traced silhouette carries
 *   the volume, the picture itself is laid over the front, and the eyes are either the engine's living eyes or the
 *   character's own (then nothing is drawn over them).
 * Everything is checked on read, so a damaged or hand-edited file can never reach the renderer.
 */
public final class AvatarSpec {
    public static final String GALLERY = "gallery", MADE = "made", PICTURE = "picture";
    public static final List<String> KINDS = List.of(GALLERY, MADE, PICTURE);
    /** Body colours offered in the editor: open-bot-avatar's own palette (apps/site/src/studio/look.ts), its orange first. */
    public static final String[] COLORS = {"#ff6a1a", "#f8a01c", "#f2384b", "#ec4899", "#8b5cf6", "#1f7cf2", "#14b3a5", "#1fbf68", "#a8693f", "#7a7a7a", "#f4f4f4"};
    public static final String[] EYE_COLORS = {"#151515", "#ffffff", "#2b2f77"};
    public static final int MAX_JSON = 32 * 1024;

    public final String kind, color, eyeColor, picture;
    public final JSONObject definition;
    public final boolean ownEyes;
    public final double depth, textureX, textureY, textureWidth, textureHeight;
    public final int shade;

    private AvatarSpec(String kind, JSONObject definition, String color, String eyeColor, boolean ownEyes, double depth,
            double tx, double ty, double tw, double th, int shade, String picture) {
        this.kind = kind; this.definition = definition; this.color = color; this.eyeColor = eyeColor; this.ownEyes = ownEyes; this.depth = depth;
        textureX = tx; textureY = ty; textureWidth = tw; textureHeight = th; this.shade = shade; this.picture = picture;
    }

    /** A bundled or AI-made shape in one colour. */
    public static AvatarSpec shape(String kind, JSONObject definition, String color, String eyeColor) {
        if (!GALLERY.equals(kind) && !MADE.equals(kind)) throw new IllegalArgumentException("아바타 종류를 확인하지 못했어요.");
        return new AvatarSpec(kind, checked(definition), hex(color, BotAvatar.DEFAULT_COLOR), hex(eyeColor, BotAvatar.DEFAULT_EYE_COLOR), false, BotAvatar.DEFAULT_DEPTH, 0, 0, 0, 0, 0, "");
    }
    /** The profile picture as the character; picture is a checksum of the exact picture it was traced from. */
    public static AvatarSpec picture(AvatarCharacter.Result traced, String name, String picture, boolean ownEyes, String eyeColor) {
        if (picture == null || !picture.matches("[0-9a-f]{8,64}")) throw new IllegalArgumentException("프로필 사진을 확인하지 못했어요.");
        return new AvatarSpec(PICTURE, checked(AvatarCharacter.definition(traced, name)), hex(null, BotAvatar.DEFAULT_COLOR), hex(eyeColor, BotAvatar.DEFAULT_EYE_COLOR), ownEyes,
            4, traced.textureX(), traced.textureY(), traced.textureWidth(), traced.textureHeight(), 0xff000000 | traced.shade(), picture);
    }
    public AvatarSpec colors(String body, String eyes) {
        return new AvatarSpec(kind, definition, hex(body, color), hex(eyes, eyeColor), ownEyes, depth, textureX, textureY, textureWidth, textureHeight, shade, picture);
    }
    public AvatarSpec ownEyes(boolean own) {
        return new AvatarSpec(kind, definition, color, eyeColor, PICTURE.equals(kind) && own, depth, textureX, textureY, textureWidth, textureHeight, shade, picture);
    }
    public BotAvatar.Definition parsed() { return BotAvatar.parse(definition); }
    public String name() { return definition.optString("name"); }

    public String json() {
        JSONObject out = Jsons.obj("version", 1, "kind", kind, "definition", definition, "color", color, "eyeColor", eyeColor, "depth", depth);
        if (PICTURE.equals(kind)) {
            Jsons.put(out, "ownEyes", ownEyes); Jsons.put(out, "picture", picture); Jsons.put(out, "shade", String.format(Locale.ROOT, "#%06x", shade & 0xffffff));
            Jsons.put(out, "texture", Jsons.obj("x", textureX, "y", textureY, "width", textureWidth, "height", textureHeight));
        }
        return out.toString();
    }
    /** Reads a stored spec. Throws IllegalArgumentException for anything malformed or out of range. */
    public static AvatarSpec parse(String json) {
        if (json == null || json.length() > MAX_JSON) throw new IllegalArgumentException("아바타 설정이 너무 커요.");
        JSONObject in;
        try { in = new JSONObject(json); } catch (Exception e) { throw new IllegalArgumentException("아바타 설정을 읽지 못했어요."); }
        if (in.optInt("version") != 1) throw new IllegalArgumentException("아바타 설정 형식이 달라요.");
        String kind = in.optString("kind");
        if (!KINDS.contains(kind)) throw new IllegalArgumentException("아바타 종류를 확인하지 못했어요.");
        JSONObject definition = checked(in.optJSONObject("definition"));
        double depth = in.optDouble("depth", BotAvatar.DEFAULT_DEPTH);
        if (!(depth >= 0 && depth <= 14)) throw new IllegalArgumentException("아바타 입체감 값이 올바르지 않아요.");
        String color = hex(in.optString("color"), BotAvatar.DEFAULT_COLOR), eyes = hex(in.optString("eyeColor"), BotAvatar.DEFAULT_EYE_COLOR);
        if (!PICTURE.equals(kind)) return new AvatarSpec(kind, definition, color, eyes, false, depth, 0, 0, 0, 0, 0, "");
        JSONObject t = in.optJSONObject("texture");
        String picture = in.optString("picture"), shade = in.optString("shade");
        if (t == null || !picture.matches("[0-9a-f]{8,64}") || !shade.matches("#[0-9a-f]{6}")) throw new IllegalArgumentException("사진 아바타 설정을 확인하지 못했어요.");
        double tx = t.optDouble("x", Double.NaN), ty = t.optDouble("y", Double.NaN), tw = t.optDouble("width", Double.NaN), th = t.optDouble("height", Double.NaN);
        if (!(Math.abs(tx) <= 1000 && Math.abs(ty) <= 1000 && tw > 1 && tw <= 2000 && th > 1 && th <= 2000)) throw new IllegalArgumentException("사진 위치 값이 올바르지 않아요.");
        return new AvatarSpec(kind, definition, color, eyes, in.optBoolean("ownEyes"), depth, tx, ty, tw, th, 0xff000000 | Integer.parseInt(shade.substring(1), 16), picture);
    }
    /** A definition the engine accepts, normalized through its own validator. */
    static JSONObject checked(JSONObject definition) {
        if (definition == null) throw new IllegalArgumentException("아바타 모양이 없어요.");
        List<String> errors = BotAvatar.validate(definition);
        if (!errors.isEmpty()) throw new IllegalArgumentException("아바타 모양이 규칙에 맞지 않아요: " + String.join("; ", errors.subList(0, Math.min(3, errors.size()))));
        return BotAvatar.parse(definition).json();
    }
    static String hex(String value, String fallback) {
        String v = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (v.matches("#[0-9a-f]{3}")) v = "#" + v.charAt(1) + v.charAt(1) + v.charAt(2) + v.charAt(2) + v.charAt(3) + v.charAt(3);
        return v.matches("#[0-9a-f]{6}") ? v : fallback;
    }

    /**
     * The open-bot-avatar design prompt plus the user's description: the model answers with one JSON definition. Returns the
     * definition, or throws IllegalArgumentException with the engine's reasons (shown to the user, who can try again).
     */
    public static JSONObject fromModel(String answer) {
        String text = answer == null ? "" : answer.strip();
        int start = text.indexOf('{'), end = text.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("AI가 아바타 JSON을 보내지 않았어요. 다시 만들어 주세요.");
        JSONObject json;
        try { json = new JSONObject(text.substring(start, end + 1)); } catch (Exception e) { throw new IllegalArgumentException("AI가 보낸 아바타 JSON을 읽지 못했어요. 다시 만들어 주세요."); }
        return checked(json);
    }
    public static final int DESCRIPTION_LIMIT = 300;
    /** The user's words for the design prompt, trimmed and bounded. */
    public static String description(String text) {
        String t = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        if (t.isEmpty()) throw new IllegalArgumentException("어떤 모습으로 만들지 적어 주세요.");
        if (t.length() > DESCRIPTION_LIMIT) throw new IllegalArgumentException(DESCRIPTION_LIMIT + "자 이내로 적어 주세요.");
        return t;
    }
}
