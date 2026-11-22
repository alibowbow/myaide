package dev.jun.gyeot.core;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 시스템 동작 1부: alarms, timers, directions, media keys, the flashlight and settings screens through
 * Android's own public intents, with no permission prompt or phone control. This validates the model's arguments
 * and describes the intents to try, in order; DeviceActionRunner resolves and fires them. Results say honestly what happened.
 */
public final class DeviceActions {
    public static final String ALARM = "device_alarm", TIMER = "device_timer", SHOW_ALARMS = "show_alarms", NAVIGATE = "navigate",
        MEDIA = "media", TORCH = "torch", SETTINGS = "settings_panel", ROTATION = "screen_rotation";
    public static final Set<String> NAMES = Set.of(ALARM, TIMER, SHOW_ALARMS, NAVIGATE, MEDIA, TORCH, SETTINGS, ROTATION);
    /** A screen rotation action as done (a toggle resolved to the way it went). */
    public static Action rotation(String mode) { return new Action(ROTATION, mode, List.of(), false, false, false, 0); }
    /** 화면 방향: portrait (세로), landscape (가로), the other of the two (바꿔), or auto (자동 회전). */
    public static final List<String> ROTATIONS = List.of("portrait", "landscape", "toggle", "auto");
    // android.provider.AlarmClock values (DeviceActionRunnerTests checks them against the SDK).
    public static final String SET_ALARM = "android.intent.action.SET_ALARM", SET_TIMER = "android.intent.action.SET_TIMER",
        LIST_ALARMS = "android.intent.action.SHOW_ALARMS", VIEW = "android.intent.action.VIEW", SETTINGS_HOME = "android.settings.SETTINGS",
        EXTRA_HOUR = "android.intent.extra.alarm.HOUR", EXTRA_MINUTES = "android.intent.extra.alarm.MINUTES",
        EXTRA_DAYS = "android.intent.extra.alarm.DAYS", EXTRA_MESSAGE = "android.intent.extra.alarm.MESSAGE",
        EXTRA_LENGTH = "android.intent.extra.alarm.LENGTH", EXTRA_SKIP_UI = "android.intent.extra.alarm.SKIP_UI";
    /** Naver Map asks callers to name themselves. */
    static final String APP_ID = "dev.jun.gyeot";
    public static final int TIMER_MAX_SECONDS = 24 * 3600, LABEL_LIMIT = 40, DESTINATION_LIMIT = 100;
    /** Schema order; index + 1 is java.util.Calendar's day number (SUNDAY = 1), which AlarmClock.EXTRA_DAYS expects. */
    public static final List<String> DAYS = List.of("sun", "mon", "tue", "wed", "thu", "fri", "sat");
    private static final String KOREAN_DAYS = "일월화수목금토";
    public static final List<String> MEDIA_COMMANDS = List.of("play", "pause", "next", "previous");
    private static final List<String> MEDIA_LABELS = List.of("재생", "일시정지", "다음 곡", "이전 곡");
    /** android.view.KeyEvent MEDIA_PLAY, MEDIA_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS, in MEDIA_COMMANDS order. */
    private static final int[] MEDIA_KEYS = {126, 127, 87, 88};
    /**
     * Settings screens the assistant may open: plain system screens the user changes themselves, never security, accounts or
     * app permissions. Name → label, Settings.Panel action ("" when there is none), the matching Settings action.
     */
    private static final Map<String, String[]> PANELS = new LinkedHashMap<>();
    static {
        PANELS.put("internet", new String[]{"인터넷", "android.settings.panel.action.INTERNET_CONNECTIVITY", "android.settings.WIRELESS_SETTINGS"});
        PANELS.put("wifi", new String[]{"와이파이", "android.settings.panel.action.WIFI", "android.settings.WIFI_SETTINGS"});
        PANELS.put("bluetooth", new String[]{"블루투스", "", "android.settings.BLUETOOTH_SETTINGS"});
        PANELS.put("volume", new String[]{"음량", "android.settings.panel.action.VOLUME", "android.settings.SOUND_SETTINGS"});
        PANELS.put("nfc", new String[]{"NFC", "android.settings.panel.action.NFC", "android.settings.NFC_SETTINGS"});
        PANELS.put("display", new String[]{"디스플레이", "", "android.settings.DISPLAY_SETTINGS"});
        PANELS.put("location", new String[]{"위치", "", "android.settings.LOCATION_SOURCE_SETTINGS"});
        PANELS.put("battery", new String[]{"절전 모드", "", "android.settings.BATTERY_SAVER_SETTINGS"});
    }
    public static final Set<String> PANEL_NAMES = Collections.unmodifiableSet(PANELS.keySet());
    /** Added to the phone-control instructions: these tools come before screen automation whenever they fit. */
    /** The voice model's one sentence about these tools; their declarations say the rest, briefly. */
    public static final String PHONE_RULE = " For alarms, timers, maps, media keys, the flashlight and settings screens use device_alarm, device_timer, show_alarms, navigate, media, torch or settings_panel instead of screen automation, and say their result's say sentence.";
    private DeviceActions() {}

    /** One intent to try, as plain data. Extras hold Integer, Boolean, String or a List of Integer. */
    public static final class Launch {
        public final String action, data, app, outcome;
        public final Map<String, Object> extras;
        Launch(String action, String data, String app, String outcome, Map<String, Object> extras) {
            this.action = action; this.data = data; this.app = app; this.outcome = outcome; this.extras = Collections.unmodifiableMap(extras);
        }
        /** For a notification tap the clock app shows its screen, so the user sees what the tap sets (or saves it there). */
        public Launch forTap() {
            if (!Boolean.TRUE.equals(extras.get(EXTRA_SKIP_UI))) return this;
            Map<String, Object> shown = new LinkedHashMap<>(extras); shown.put(EXTRA_SKIP_UI, false);
            return new Launch(action, data, app, "screen", shown);
        }
    }
    /** One validated tool call: the intents to try in order, or a torch or media-key command that needs no activity. */
    public static final class Action {
        public final String tool, readback;
        public final List<Launch> launches;
        /** The model said the user confirmed; heard is whether the user's current words were a yes. Only both send it to the clock app. */
        public final boolean confirmRequested, confirmationHeard;
        public final boolean torchOn;
        public final int mediaKey;
        Action(String tool, String readback, List<Launch> launches, boolean confirmRequested, boolean confirmationHeard, boolean torchOn, int mediaKey) {
            this.tool = tool; this.readback = readback; this.launches = List.copyOf(launches);
            this.confirmRequested = confirmRequested; this.confirmationHeard = confirmationHeard; this.torchOn = torchOn; this.mediaKey = mediaKey;
        }
        public boolean activity() { return !launches.isEmpty(); }
        /**
         * An alarm or timer without a heard yes: nothing may be sent. Given an hour or a length, the clock app sets the alarm or
         * starts the timer whatever EXTRA_SKIP_UI says (AOSP DeskClock does); that flag only hides its screen afterwards.
         */
        public boolean needsConfirmation() { return (ALARM.equals(tool) || TIMER.equals(tool)) && !confirmationHeard; }
    }

    public static Action parse(String tool, JSONObject args, String heard, ZonedDateTime now) {
        // Unused optional fields may arrive as JSON null; they count as absent.
        JSONObject values = obj();
        if (args != null) for (java.util.Iterator<String> keys = args.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!args.isNull(key)) put(values, key, args.opt(key));
        }
        return switch (tool == null ? "" : tool) {
            case ALARM -> alarm(values, heard, now);
            case TIMER -> timer(values, heard);
            case SHOW_ALARMS -> new Action(SHOW_ALARMS, "알람 목록", List.of(new Launch(LIST_ALARMS, "", "시계 앱", "list", Map.of())), false, false, false, 0);
            case NAVIGATE -> navigate(values);
            case MEDIA -> media(values);
            case TORCH -> torch(values);
            case ROTATION -> {
                String mode = values.optString("mode").strip().toLowerCase(java.util.Locale.ROOT);
                if (!ROTATIONS.contains(mode)) throw new IllegalArgumentException("화면 방향(mode)은 portrait, landscape, toggle, auto 중 하나예요.");
                yield new Action(ROTATION, mode, List.of(), false, false, false, 0);
            }
            case SETTINGS -> settings(values);
            default -> throw new IllegalArgumentException("지원하지 않는 시스템 동작이에요.");
        };
    }

    private static Action alarm(JSONObject args, String heard, ZonedDateTime now) {
        if (!args.has("hour")) throw new IllegalArgumentException("알람 시각(hour, 0~23)을 알려 주세요.");
        int hour = whole(args, "hour", 0, 23, "알람 시(hour)는 0~23 사이로 알려 주세요.");
        int minute = args.has("minute") ? whole(args, "minute", 0, 59, "알람 분(minute)은 0~59 사이로 알려 주세요.") : 0;
        TreeSet<Integer> days = days(args.opt("days"));
        String date = args.optString("date").strip();
        if (!days.isEmpty() && !date.isEmpty()) throw new IllegalArgumentException("반복 요일과 날짜는 함께 정할 수 없어요. 하나만 알려 주세요.");
        ZonedDateTime when = nextAlarm(now, hour, minute, days);
        String readback = days.isEmpty() ? day(when, now) + " " + clock(hour, minute) : repeat(days) + " " + clock(hour, minute);
        if (!date.isEmpty()) {
            LocalDate wanted;
            try { wanted = LocalDate.parse(date); } catch (RuntimeException invalid) { throw new IllegalArgumentException("날짜(date)는 2026-11-08처럼 알려 주세요."); }
            // The clock app takes no date: it rings the next time this hour:minute comes. Refuse rather than ring on another day.
            if (!wanted.equals(when.toLocalDate()))
                throw new IllegalArgumentException("시계 앱 알람은 날짜를 정할 수 없어서 다음 " + clock(hour, minute) + "인 " + day(when, now) + "에 울려요. 요청한 날짜("
                    + wanted.getMonthValue() + "월 " + wanted.getDayOfMonth() + "일)와 달라 맞추지 않았어요.");
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put(EXTRA_HOUR, hour); extras.put(EXTRA_MINUTES, minute);
        if (!days.isEmpty()) extras.put(EXTRA_DAYS, new ArrayList<>(days));
        String label = clean(args.optString("label"), LABEL_LIMIT);
        if (!label.isEmpty()) extras.put(EXTRA_MESSAGE, label);
        boolean requested = args.optBoolean("confirmed"), heardYes = requested && affirmative(heard);
        // Sent only after a heard yes (needsConfirmation), so the clock app need not show its screen.
        extras.put(EXTRA_SKIP_UI, true);
        return new Action(ALARM, readback, List.of(new Launch(SET_ALARM, "", "시계 앱", "set", extras)), requested, heardYes, false, 0);
    }
    private static Action timer(JSONObject args, String heard) {
        if (!args.has("seconds")) throw new IllegalArgumentException("타이머 길이(seconds)를 초 단위로 알려 주세요.");
        double raw = args.optDouble("seconds", Double.NaN);
        if (Double.isNaN(raw) || Double.isInfinite(raw)) throw new IllegalArgumentException("타이머 길이(seconds)는 숫자로 알려 주세요.");
        long seconds = Math.round(raw);
        if (seconds < 1 || seconds > TIMER_MAX_SECONDS) throw new IllegalArgumentException("타이머는 1초부터 24시간까지 맞출 수 있어요.");
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put(EXTRA_LENGTH, (int) seconds);
        String label = clean(args.optString("label"), LABEL_LIMIT);
        if (!label.isEmpty()) extras.put(EXTRA_MESSAGE, label);
        boolean requested = args.optBoolean("confirmed"), heardYes = requested && affirmative(heard);
        extras.put(EXTRA_SKIP_UI, true);
        return new Action(TIMER, length((int) seconds), List.of(new Launch(SET_TIMER, "", "시계 앱", "set", extras)), requested, heardYes, false, 0);
    }
    private static Action navigate(JSONObject args) {
        String destination = destination(args);
        NaverPlaces.mode(args.optString("mode"));
        return search(destination, false);
    }
    /** The destination of a navigate call, cleaned; refused when there is none. */
    public static String destination(JSONObject args) {
        String destination = clean(args == null ? "" : args.optString("destination"), DESTINATION_LIMIT);
        if (destination.isEmpty()) throw new IllegalArgumentException("어디로 갈지(destination) 장소 이름이나 주소를 알려 주세요.");
        return destination;
    }
    /**
     * A search for a place name in a maps app. KakaoMap and Naver Map search for it (their route links need coordinates);
     * naverFirst puts Naver Map before KakaoMap, for a user who connected 네이버 길안내 (NaverPlaces).
     */
    public static Action search(String destination, boolean naverFirst) {
        String q = URLEncoder.encode(destination, StandardCharsets.UTF_8).replace("+", "%20");
        Launch kakao = new Launch(VIEW, "kakaomap://search?q=" + q, "카카오맵", "search", Map.of()),
            naver = new Launch(VIEW, "nmap://search?query=" + q + "&appname=" + APP_ID, "네이버 지도", "search", Map.of());
        return new Action(NAVIGATE, destination, List.of(naverFirst ? naver : kakao, naverFirst ? kakao : naver,
            new Launch(VIEW, "google.navigation:q=" + q, "Google 지도", "navigation", Map.of()),
            new Launch(VIEW, "geo:0,0?q=" + q, "지도 앱", "search", Map.of())), false, false, false, 0);
    }
    /**
     * To a place 지역 검색 found (NaverPlaces): Naver Map's navigation from where the user is (car) or its route for another
     * way of travel, then KakaoMap's route and, by car, Google Maps' navigation to the same coordinates, then any maps app's pin.
     */
    /** With waypoints (경유지): Naver Map's route through them (its 안내 시작 starts navigation); the other apps go straight there. */
    public static Action navigateTo(NaverPlaces.Place place, String mode, List<NaverPlaces.Place> via) {
        Action plain = navigateTo(place, mode);
        if (via == null || via.isEmpty() || "public".equals(mode)) return plain;
        List<Launch> launches = new ArrayList<>(plain.launches);
        launches.set(0, new Launch(VIEW, NaverPlaces.link(place, mode, APP_ID, via), "네이버 지도", "route", Map.of()));
        return new Action(NAVIGATE, place.name(), launches, false, false, false, 0);
    }
    public static Action navigateTo(NaverPlaces.Place place, String mode) {
        String at = String.format(Locale.ROOT, "%.7f,%.7f", place.lat(), place.lng()), name = URLEncoder.encode(place.name(), StandardCharsets.UTF_8).replace("+", "%20");
        String by = switch (mode) { case "public" -> "PUBLICTRANSIT"; case "walk" -> "FOOT"; case "bicycle" -> "BICYCLE"; default -> "CAR"; };
        List<Launch> launches = new ArrayList<>();
        launches.add(new Launch(VIEW, NaverPlaces.link(place, mode, APP_ID), "네이버 지도", "car".equals(mode) ? "navigation" : "route", Map.of()));
        launches.add(new Launch(VIEW, "kakaomap://route?ep=" + at + "&by=" + by, "카카오맵", "route", Map.of()));
        if ("car".equals(mode)) launches.add(new Launch(VIEW, "google.navigation:q=" + at, "Google 지도", "navigation", Map.of()));
        launches.add(new Launch(VIEW, "geo:" + at + "?q=" + at + "(" + name + ")", "지도 앱", "place", Map.of()));
        return new Action(NAVIGATE, place.name(), launches, false, false, false, 0);
    }
    private static Action media(JSONObject args) {
        int index = MEDIA_COMMANDS.indexOf(args.optString("command").strip().toLowerCase(Locale.ROOT));
        if (index < 0) throw new IllegalArgumentException("미디어 명령(command)은 play, pause, next, previous 중 하나예요.");
        return new Action(MEDIA, MEDIA_LABELS.get(index), List.of(), false, false, false, MEDIA_KEYS[index]);
    }
    private static Action torch(JSONObject args) {
        Object on = args.opt("on");
        if (!(on instanceof Boolean) && !"true".equals(String.valueOf(on)) && !"false".equals(String.valueOf(on)))
            throw new IllegalArgumentException("손전등을 켤지 끌지(on: true 또는 false) 알려 주세요.");
        boolean value = args.optBoolean("on");
        return new Action(TORCH, value ? "켜기" : "끄기", List.of(), false, false, value, 0);
    }
    private static Action settings(JSONObject args) {
        String name = args.optString("panel").strip().toLowerCase(Locale.ROOT).replace("-", "").replace(" ", "");
        String[] panel = PANELS.get(name);
        if (panel == null) throw new IllegalArgumentException("열 수 있는 설정 화면은 " + String.join(", ", PANEL_NAMES) + "예요.");
        List<Launch> launches = new ArrayList<>();
        if (!panel[1].isEmpty()) launches.add(new Launch(panel[1], "", "설정", "panel", Map.of()));
        launches.add(new Launch(panel[2], "", "설정", "settings", Map.of()));
        launches.add(new Launch(SETTINGS_HOME, "", "설정", "settings_home", Map.of()));
        return new Action(SETTINGS, panel[0], launches, false, false, false, 0);
    }

    /**
     * How an activity can start right now. Android lets an app start activities from the background when one of its activities
     * is visible, when the system has bound its accessibility service, or when it holds the overlay permission; from Android 15
     * that last one counts only while an overlay window is actually visible. A locked screen gets the tap-to-open notification.
     */
    public static String route(boolean locked, boolean appVisible, boolean accessibilityBound, boolean overlayPermission, boolean overlayVisible, int sdk) {
        if (locked) return "locked";
        if (appVisible) return "app";
        if (accessibilityBound) return "accessibility";
        if (overlayPermission && (sdk < 35 || overlayVisible)) return "overlay";
        return "notification";
    }

    /** Started, or sent for torch and media: the honest result for the model to say. */
    public static JSONObject result(Action action, Launch launch, String route) {
        JSONObject value = obj("ok", true, "tool", action.tool, "outcome", launch != null ? launch.outcome : MEDIA.equals(action.tool) ? "sent" : "done", "readback", action.readback, "route", route,
            "say", said(action, launch));
        if (launch != null) put(value, "app", launch.app);
        if (action.confirmRequested) put(value, "confirmation_heard", action.confirmationHeard);
        return value;
    }
    /** No yes heard yet, so nothing was sent: the say sentence is the app's read-back, as the question to ask. */
    public static JSONObject ask(Action action) {
        String question = ALARM.equals(action.tool) ? action.readback + "에 알람을 맞출까요?" : action.readback + " 타이머를 시작할까요?";
        JSONObject value = obj("ok", false, "tool", action.tool, "outcome", "needs_confirmation", "requires_confirmation", true, "readback", action.readback,
            "say", (action.confirmRequested ? "확인 대답을 듣지 못했어요. " : "") + question);
        if (action.confirmRequested) put(value, "confirmation_heard", false);
        return value;
    }
    /** Posted as a tap-to-open notification instead: nothing has opened or been set yet. */
    public static JSONObject notified(Action action, Launch launch, String route) {
        String what = what(action, launch);
        String say = ("locked".equals(route) ? "화면이 잠겨 있어서 알림을 보냈어요. 잠금을 풀고 알림을 눌러 " : "지금은 다른 앱 화면을 바로 열 수 없어서 알림을 보냈어요. 알림을 눌러 ")
            + Brand.josa(what, "을", "를") + " 열어 주세요." + (ALARM.equals(action.tool) ? " 알람은 아직 맞춰지지 않았어요." : TIMER.equals(action.tool) ? " 타이머는 아직 시작되지 않았어요." : "");
        return obj("ok", true, "tool", action.tool, "outcome", "notification", "tap_to_open", true, "app", launch.app, "readback", action.readback, "route", route, "say", say);
    }
    /** The notification text: what a tap opens. */
    public static String notificationText(Action action, Launch launch) {
        return "탭해서 열기 · " + what(action, launch) + (ALARM.equals(action.tool) || TIMER.equals(action.tool) ? " (" + action.readback + ")" : "");
    }
    /** No app on this phone handles any of the intents. */
    public static JSONObject noApp(Action action) {
        String error = switch (action.tool) {
            case ALARM, TIMER, SHOW_ALARMS -> "이 폰에서 알람과 타이머를 다룰 시계 앱을 찾지 못했어요.";
            case NAVIGATE -> "길안내를 열 지도 앱을 찾지 못했어요. 카카오맵, 네이버 지도, Google 지도 중 하나를 설치하면 돼요.";
            default -> "그 설정 화면을 열 수 있는 앱을 찾지 못했어요.";
        };
        return obj("ok", false, "tool", action.tool, "outcome", "no_app", "readback", action.readback, "error", error, "say", error);
    }
    /** It could not start and notifications are off too, so nothing reached the user. */
    public static JSONObject blocked(Action action) {
        String error = "지금은 다른 앱 화면을 바로 열 수 없고 알림도 꺼져 있어서 열지 못했어요. " + Brand.NAME + " 앱을 연 뒤 다시 말해 주세요.";
        return obj("ok", false, "tool", action.tool, "outcome", "blocked", "readback", action.readback, "error", error, "say", error);
    }
    public static JSONObject failed(Action action, String error) {
        return obj("ok", false, "tool", action.tool, "outcome", "failed", "readback", action.readback, "error", error, "say", error);
    }
    private static String said(Action action, Launch launch) {
        // Alarms and timers reach here only after a heard yes (needsConfirmation).
        return switch (action.tool) {
            case ALARM -> "알람을 맞췄어요: " + action.readback + ".";
            case TIMER -> action.readback + " 타이머를 시작했어요.";
            case SHOW_ALARMS -> "시계 앱의 알람 목록을 열었어요.";
            case NAVIGATE -> switch (launch.outcome) {
                case "navigation" -> launch.app + "에서 ‘" + action.readback + "’ 길안내를 열었어요.";
                case "route" -> launch.app + "에서 ‘" + action.readback + "’까지 길찾기를 열었어요. 경로를 골라 안내를 시작해 주세요.";
                case "place" -> launch.app + "에서 ‘" + action.readback + "’ 위치를 열었어요. 길안내는 지도 앱에서 시작해 주세요.";
                default -> launch.app + "에서 ‘" + action.readback + "’ 검색 화면을 열었어요. 길안내는 아직 시작하지 않았으니 장소를 골라 길찾기를 눌러 주세요.";
            };
            case MEDIA -> "‘" + action.readback + "’ 신호를 보냈어요. " + (action.mediaKey == MEDIA_KEYS[0] ? "마지막으로 재생하던 앱이 받아요." : "재생 중인 앱이 받아요.");
            case TORCH -> action.torchOn ? "손전등을 켰어요." : "손전등을 껐어요.";
            case ROTATION -> "auto".equals(action.readback) ? "화면 자동 회전을 켰어요." : "화면을 " + ("landscape".equals(action.readback) ? "가로" : "세로") + "로 고정했어요.";
            default -> "settings_home".equals(launch.outcome) ? action.readback + " 화면은 바로 열 수 없어서 설정 앱을 열었어요."
                : action.readback + " 설정 " + ("panel".equals(launch.outcome) ? "창" : "화면") + "을 열었어요.";
        };
    }
    private static String what(Action action, Launch launch) {
        return switch (action.tool) {
            case ALARM -> "알람 화면";
            case TIMER -> "타이머 화면";
            case SHOW_ALARMS -> "알람 목록";
            case NAVIGATE -> launch.app + " ‘" + action.readback + "’ " + switch (launch.outcome) { case "navigation" -> "길안내"; case "route" -> "길찾기"; case "place" -> "위치"; default -> "검색"; };
            default -> action.readback + " 설정";
        };
    }

    // An affirmative reply on its own (응, 네 맞춰 주세요, 그렇게 해 줘), never a question, a change or a refusal.
    private static final Pattern YES = Pattern.compile(
        "(?:(?:응|웅|어|엉|네|넵|넹|예|그래|그럼|좋아|좋습니다|오케이|ok|okay|yes|yeah|yep|맞아|맞습니다|콜)(?:요|용)?)+"
        + "(?:(?:그렇게|그대로|그걸로)?(?:맞춰|설정해|해|켜|시작해|돌려|부탁해|진행해)(?:줘|주세요|줘요|요)?)?"
        + "|(?:그렇게|그대로|그걸로)(?:해|맞춰|설정해|시작해|진행해)(?:줘|주세요|줘요|요)?"
        + "|(?:맞춰|설정해|시작해|진행해|부탁해)(?:줘|주세요|줘요|요)?");
    /** The user's latest words are a plain yes, so a read-back can count as their spoken confirmation. */
    public static boolean affirmative(String speech) {
        String raw = speech == null ? "" : speech.strip();
        if (raw.isEmpty() || raw.endsWith("?") || raw.endsWith("？")) return false;
        String text = raw.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·…~。，！]+", "");
        return !text.isEmpty() && text.length() <= 20 && YES.matcher(text).matches();
    }

    /** The next time the clock app rings for this hour:minute (and these Calendar days), as it computes it. */
    static ZonedDateTime nextAlarm(ZonedDateTime now, int hour, int minute, Set<Integer> days) {
        ZonedDateTime when = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        if (!when.isAfter(now)) when = when.plusDays(1);
        for (int i = 0; i < 7 && !days.isEmpty() && !days.contains(calendarDay(when.getDayOfWeek())); i++) when = when.plusDays(1);
        return when;
    }
    private static int calendarDay(DayOfWeek day) { return day.getValue() % 7 + 1; }
    private static String clock(int hour, int minute) {
        return (hour < 12 ? "오전 " : "오후 ") + (hour % 12 == 0 ? 12 : hour % 12) + ":" + (minute < 10 ? "0" : "") + minute;
    }
    private static String day(ZonedDateTime when, ZonedDateTime now) {
        long ahead = ChronoUnit.DAYS.between(now.toLocalDate(), when.toLocalDate());
        String date = when.getMonthValue() + "월 " + when.getDayOfMonth() + "일 " + KOREAN_DAYS.charAt(calendarDay(when.getDayOfWeek()) - 1) + "요일";
        return ahead == 0 ? "오늘(" + date + ")" : ahead == 1 ? "내일(" + date + ")" : date;
    }
    private static String repeat(Set<Integer> days) {
        if (days.size() == 7) return "매일";
        if (days.equals(Set.of(2, 3, 4, 5, 6))) return "평일마다";
        if (days.equals(Set.of(1, 7))) return "주말마다";
        StringBuilder names = new StringBuilder();
        // Monday first, as Korean calendars read.
        for (int day : new int[]{2, 3, 4, 5, 6, 7, 1}) if (days.contains(day)) names.append(names.length() == 0 ? "" : "·").append(KOREAN_DAYS.charAt(day - 1));
        return "매주 " + names;
    }
    static String length(int seconds) {
        int hours = seconds / 3600, minutes = seconds % 3600 / 60, rest = seconds % 60;
        StringBuilder text = new StringBuilder();
        if (hours > 0) text.append(hours).append("시간");
        if (minutes > 0) text.append(text.length() == 0 ? "" : " ").append(minutes).append("분");
        if (rest > 0) text.append(text.length() == 0 ? "" : " ").append(rest).append("초");
        return text.toString();
    }
    private static TreeSet<Integer> days(Object raw) {
        TreeSet<Integer> days = new TreeSet<>();
        if (raw == null || raw == JSONObject.NULL) return days;
        List<String> names = new ArrayList<>();
        if (raw instanceof JSONArray list) for (int i = 0; i < list.length(); i++) names.add(list.optString(i));
        else for (String part : String.valueOf(raw).split("[,\\s·/]+")) names.add(part);
        for (String name : names) {
            String value = name.strip().toLowerCase(Locale.ROOT).replace("요일", "");
            if (value.isEmpty()) continue;
            int index = value.length() >= 3 ? DAYS.indexOf(value.substring(0, 3)) : value.length() == 1 ? KOREAN_DAYS.indexOf(value.charAt(0)) : -1;
            if (index < 0) throw new IllegalArgumentException("반복 요일(days)은 mon, tue, wed, thu, fri, sat, sun으로 알려 주세요.");
            days.add(index + 1);
        }
        return days;
    }
    private static int whole(JSONObject args, String key, int min, int max, String error) {
        Object raw = args.opt(key);
        double value;
        if (raw instanceof Number number) value = number.doubleValue();
        else if (raw instanceof String text && text.strip().matches("\\d{1,4}")) value = Integer.parseInt(text.strip());
        else throw new IllegalArgumentException(error);
        if (value != Math.rint(value) || value < min || value > max) throw new IllegalArgumentException(error);
        return (int) value;
    }
    /** One line of plain text: no control characters, collapsed spaces, at most limit characters (never half a surrogate pair). */
    static String clean(String text, int limit) {
        String value = text == null ? "" : text.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        if (value.codePointCount(0, value.length()) <= limit) return value;
        return value.substring(0, value.offsetByCodePoints(0, limit)).strip();
    }

    /** Declarations for the voice assistant (GeminiProtocol), kept short: the result's say sentence carries the details. */
    public static JSONArray declarations() {
        String confirm = " The first call, without confirmed, sets nothing: its say sentence is a read-back question. Ask it; only after the user's yes, call again with the same values and confirmed true.";
        JSONArray days = arr(); for (String day : List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun")) days.put(day);
        JSONArray panels = arr(); for (String name : PANEL_NAMES) panels.put(name);
        JSONArray commands = arr(); for (String command : MEDIA_COMMANDS) commands.put(command);
        JSONObject confirmed = obj("type", "BOOLEAN", "description", "true only after the user's yes to the read-back");
        return arr(
            function(ALARM, "Set an alarm in the clock app; resolve 내일 7시 or 30분 뒤 to a 24-hour time." + confirm,
                obj("hour", obj("type", "INTEGER", "description", "0-23"), "minute", obj("type", "INTEGER", "description", "0-59"),
                    "date", obj("type", "STRING", "description", "YYYY-MM-DD meant; omit when repeating"),
                    "days", obj("type", "ARRAY", "items", obj("type", "STRING", "enum", days)),
                    "label", obj("type", "STRING"), "confirmed", confirmed), "hour"),
            function(TIMER, "Start a countdown timer, 1 second to 24 hours." + confirm,
                obj("seconds", obj("type", "INTEGER"), "label", obj("type", "STRING"), "confirmed", confirmed), "seconds"),
            function(SHOW_ALARMS, "Open the clock app's alarm list.", obj()),
            function(NAVIGATE, "A place in a maps app; with the 네이버 길안내 key, Naver Map navigation or route. On choose, ask, then call again with pick.",
                obj("destination", obj("type", "STRING", "description", "As the user said it"), "mode", obj("type", "STRING", "enum", arr("car", "public", "walk", "bicycle"), "description", "Only when the user names a way of travel; leave it out otherwise (the user's default is used); never ask"),
                    "nearby", obj("type", "BOOLEAN", "description", "근처/가까운"), "pick", obj("type", "INTEGER"),
                    "via", obj("type", "ARRAY", "items", obj("type", "STRING"), "description", "경유지 in order, as said (\"회사 들렀다가 집으로\": via [회사], destination 집); at most 5")), "destination"),
            function(MEDIA, "Send a media key to the app playing music or video.", obj("command", obj("type", "STRING", "enum", commands)), "command"),
            function(TORCH, "Turn the flashlight on or off.", obj("on", obj("type", "BOOLEAN")), "on"),
            function(ROTATION, "Turn the phone's screen to portrait (세로) or landscape (가로), the other of the two (toggle: 화면 돌려 줘, 가로세로 바꿔 줘), or back to auto-rotate.",
                obj("mode", obj("type", "STRING", "enum", arr("portrait", "landscape", "toggle", "auto"))), "mode"),
            function(SETTINGS, "Open a phone settings screen for the user to change.", obj("panel", obj("type", "STRING", "enum", panels)), "panel"));
    }
    private static JSONObject function(String name, String description, JSONObject props, String... required) {
        JSONObject value = obj("name", name, "description", description);
        if (props.length() == 0) return value;
        JSONObject parameters = obj("type", "OBJECT", "properties", props);
        if (required.length > 0) put(parameters, "required", arr((Object[]) required));
        put(value, "parameters", parameters);
        return value;
    }
}
