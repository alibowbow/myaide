package dev.jun.gyeot.core;

/**
 * 차에서 호출어: what the standby does now, from what it knows. One decision, so the service, its
 * notification and the settings page always agree:
 *
 * <pre>
 * OFF              the user turned it off
 * NEEDS_SETUP      no usable recordings, or no car chosen
 * NEEDS_PERMISSION the microphone or Bluetooth permission is missing
 * WAITING_FOR_CAR  ready; the chosen car is not connected, so the microphone stays off
 * PAUSED_FOR_CALL  a voice call has the microphone
 * LISTENING        the car is connected: it listens for 들퍼레 / 퍼레
 * </pre>
 */
public final class WakeStandby {
    public enum State { OFF, NEEDS_SETUP, NEEDS_PERMISSION, WAITING_FOR_CAR, PAUSED_FOR_CALL, LISTENING }
    private WakeStandby() {}

    public static State state(boolean enabled, boolean recordings, boolean carChosen, boolean permissions, boolean carConnected, boolean voiceActive) {
        if (!enabled) return State.OFF;
        if (!recordings || !carChosen) return State.NEEDS_SETUP;
        if (!permissions) return State.NEEDS_PERMISSION;
        if (!carConnected) return State.WAITING_FOR_CAR;
        if (voiceActive) return State.PAUSED_FOR_CALL;
        return State.LISTENING;
    }
    /** Only LISTENING holds the microphone. */
    public static boolean microphone(State state) { return state == State.LISTENING; }
    /** The standby notification's line for each state. */
    public static String label(State state, String car) {
        String name = car == null || car.isBlank() ? "차" : car;
        return switch (state) {
            case OFF -> "호출어 꺼짐";
            case NEEDS_SETUP -> "호출어 설정이 필요해요 · 녹음과 차 선택";
            case NEEDS_PERMISSION -> "호출어에 마이크·블루투스 권한이 필요해요";
            case WAITING_FOR_CAR -> name + " 연결을 기다리는 중 · 마이크 꺼짐";
            case PAUSED_FOR_CALL -> "음성 대화 중 · 호출어 잠시 멈춤";
            case LISTENING -> name + " 연결됨 · “들퍼레” 또는 “퍼레”를 듣는 중";
        };
    }
}
