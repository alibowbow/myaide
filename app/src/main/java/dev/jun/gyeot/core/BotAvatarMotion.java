package dev.jun.gyeot.core;

/** motion.ts: the deterministic pose model, a pure function of (seed, time, motion, gaze, reduced motion, view, blend). */
final class BotAvatarMotion {
    private BotAvatarMotion() {}

    static final double LOOK_YAW = 0.32, LOOK_PITCH = 0.18, LOOK_EYES = 0.7;
    private static final double TAU = Math.PI * 2;

    /** FNV-1a over UTF-16 code units, as an unsigned 32-bit value. */
    static long hashString(String s) {
        int h = (int) 2166136261L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 16777619;
        }
        return h & 0xffffffffL;
    }

    /** Stateless hash noise in [0, 1). */
    static double noise(long seed, double n) {
        int h = (int) seed ^ (BotAvatarJs.toInt32(n) * (int) 0x9e3779b1L);
        h = (h ^ (h >>> 16)) * (int) 0x85ebca6bL;
        h = (h ^ (h >>> 13)) * (int) 0xc2b2ae35L;
        h ^= h >>> 16;
        return (h & 0xffffffffL) / 4294967296.0;
    }

    private static double clamp(double v, double lo, double hi) { return Math.min(hi, Math.max(lo, v)); }

    private static double smooth(double u) { double x = clamp(u, 0, 1); return x * x * (3 - 2 * x); }

    static double blinkAt(long seed, double t) {
        double slotLen = 4.2, dur = 0.19;
        double slot = Math.floor(t / slotLen);
        double closure = 0;
        for (double s : new double[] {slot - 1, slot}) {
            double start = s * slotLen + 0.5 + noise(seed, s * 2) * 2.9;
            double[] starts = noise(seed, s * 2 + 1) > 0.8 ? new double[] {start, start + 0.34} : new double[] {start};
            for (double b : starts) {
                double u = (t - b) / dur;
                if (u > 0 && u < 1) closure = Math.max(closure, StrictMath.sin(Math.PI * u));
            }
        }
        return closure;
    }

    private static double[] target(long seed, double k) {
        if (noise(seed, 1000 + k * 3) < 0.4) return new double[] {0, 0};
        return new double[] {(noise(seed, 1001 + k * 3) * 2 - 1) * 0.6, (noise(seed, 1002 + k * 3) * 2 - 1) * 0.35};
    }

    static double[] wanderAt(long seed, double t) {
        double slotLen = 1.9;
        double s = Math.floor(t / slotLen);
        double[] a = target(seed, s - 1), b = target(seed, s);
        double e = smooth((t - s * slotLen) / 0.16);
        return new double[] {a[0] + (b[0] - a[0]) * e, a[1] + (b[1] - a[1]) * e};
    }

    private static double mix(double x, double y, double k) { return x + (y - x) * k; }

    static BotAvatar.Pose blendPoses(BotAvatar.Pose a, BotAvatar.Pose b, double t) {
        double k = clamp(t, 0, 1);
        double blink = mix(a.blink(), b.blink(), k);
        String shape = b.eyeShape();
        if (!a.eyeShape().equals(b.eyeShape())) {
            shape = k < 0.5 ? a.eyeShape() : b.eyeShape();
            blink = Math.max(blink, 1 - Math.abs(2 * k - 1));
        }
        return new BotAvatar.Pose(mix(a.yaw(), b.yaw(), k), mix(a.pitch(), b.pitch(), k), mix(a.roll(), b.roll(), k), mix(a.lift(), b.lift(), k),
            mix(a.sx(), b.sx(), k), mix(a.sy(), b.sy(), k), mix(a.gazeX(), b.gazeX(), k), mix(a.gazeY(), b.gazeY(), k),
            blink, mix(a.squintL(), b.squintL(), k), mix(a.squintR(), b.squintR(), k), shape);
    }

    static BotAvatar.Pose computePose(long seed, double time, String motion, BotAvatar.Gaze gaze, boolean reduced, double viewYaw, double viewPitch, BotAvatar.Blend blend) {
        BotAvatar.Pose pose = moodPose(seed, time, motion, gaze, reduced, viewYaw, viewPitch);
        if (blend == null || !(blend.t() < 1)) return pose;
        BotAvatar.Pose from = computePose(seed, time, blend.fromMotion(), gaze, reduced, viewYaw, viewPitch, blend.fromBlend());
        return blendPoses(from, pose, Double.isNaN(blend.t()) || Double.isInfinite(blend.t()) ? 1 : blend.t());
    }

    private static boolean finite(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }

    private static BotAvatar.Pose moodPose(long seed, double time, String motion, BotAvatar.Gaze gaze, boolean reduced, double viewYaw, double viewPitch) {
        double t = reduced ? 0 : time + noise(seed, 7) * 10;
        boolean given = gaze != null && finite(gaze.x()) && finite(gaze.y());
        double gx = given ? clamp(gaze.x(), -1, 1) : 0, gy = given ? clamp(gaze.y(), -1, 1) : 0;
        double weight = given ? (finite(gaze.weight()) ? clamp(gaze.weight(), 0, 1) : 1) : 0;
        double sway = 1 - 0.7 * weight;

        double yaw = BotAvatar.BASE_YAW, pitch = BotAvatar.BASE_PITCH, roll = 0, lift = 0, sx = 1, sy = 1;
        double gazeX = 0, gazeY = 0, blink = 0, squintL = 0, squintR = 0;
        String eyeShape = "pill";

        if ("happy".equals(motion)) {
            double period = 0.92;
            double p = reduced ? 0.5 : ((t % period) + period) % period / period;
            double air = 4 * p * (1 - p);
            double land = reduced ? 0 : clamp(1 - Math.min(p, 1 - p) / 0.12, 0, 1);
            lift = reduced ? 0 : -3.2 * air;
            sy = 1 - 0.09 * land + 0.03 * air * (reduced ? 0 : 1);
            sx = 1 + 0.07 * land - 0.015 * air * (reduced ? 0 : 1);
            roll = 4.5 * wave(t, reduced, period * 2, 0);
            yaw += 0.1 * wave(t, reduced, period * 2, 0.6);
            eyeShape = "happy";
        } else if ("thinking".equals(motion)) {
            yaw += 0.12 + 0.05 * sway * wave(t, reduced, 7.3, 0);
            pitch += 0.08;
            roll = -5 + 1.4 * wave(t, reduced, 4.1, 0);
            lift = -0.5 * (1 - StrictMath.cos((TAU * t) / 5.2)) * (reduced ? 0 : 1);
            gazeX = 0.55 + 0.3 * wave(t, reduced, 5.6, 0);
            gazeY = -0.8;
            squintL = 0.22;
            squintR = 0.32 + 0.1 * wave(t, reduced, 3.1, 0);
            if (!reduced) blink = blinkAt(seed, t * 0.7);
        } else if (BotAvatar.EXTRA_MOODS.contains(motion)) {
            // The app's own moods beyond open-bot-avatar's three, in the same idiom: a body motion and an eye shape.
            BotAvatar.Pose extra = extraPose(seed, t, motion, reduced);
            yaw += extra.yaw(); pitch += extra.pitch(); roll = extra.roll(); lift = extra.lift(); sx = extra.sx(); sy = extra.sy();
            gazeX = extra.gazeX(); gazeY = extra.gazeY(); blink = extra.blink(); squintL = extra.squintL(); squintR = extra.squintR(); eyeShape = extra.eyeShape();
        } else {
            yaw += 0.07 * sway * wave(t, reduced, 6.1, 0);
            pitch += 0.04 * sway * wave(t, reduced, 4.7, 1);
            roll = 1.6 * wave(t, reduced, 5.3, 2);
            lift = reduced ? 0 : -0.45 * (1 - StrictMath.cos((TAU * t) / 3.4));
            double breath = wave(t, reduced, 3.4, -Math.PI / 2);
            sy = 1 + 0.012 * breath;
            sx = 1 - 0.008 * breath;
            if (!reduced) {
                blink = blinkAt(seed, t);
                double[] w = wanderAt(seed, t);
                gazeX = w[0]; gazeY = w[1];
            }
        }

        if (given && weight > 0) {
            double share = reduced ? 1 : LOOK_EYES;
            if (eyeShape.equals("pill")) {
                gazeX = gazeX + (gx * share - gazeX) * weight;
                gazeY = gazeY + (gy * share - gazeY) * weight;
            }
            if (!reduced) {
                yaw += weight * (gx * LOOK_YAW - BotAvatar.BASE_YAW);
                pitch += weight * (-gy * LOOK_PITCH - BotAvatar.BASE_PITCH / 2);
            }
        }
        if (finite(viewYaw) && finite(viewPitch)) {
            yaw += viewYaw;
            pitch += viewPitch;
        }
        return new BotAvatar.Pose(yaw, pitch, roll, lift, sx, sy, gazeX, gazeY, blink, squintL, squintR, eyeShape);
    }

    private static double phase(double t, double period) { double x = t / period; return x - Math.floor(x); }

    /**
     * The extra moods (BotAvatar.EXTRA_MOODS). yaw and pitch are offsets from the resting view; the rest is the pose itself.
     * Like the library's moods, every value is a pure function of time, so a reduced-motion still is calm and steady.
     */
    private static BotAvatar.Pose extraPose(long seed, double t, String motion, boolean reduced) {
        double yaw = 0, pitch = 0, roll = 0, lift = 0, sx = 1, sy = 1, gazeX = 0, gazeY = 0, blink = 0, squintL = 0, squintR = 0;
        String eyes;
        switch (motion) {
            case "surprised" -> {
                // A startled hop back with round eyes, then a held breath and a tiny shiver.
                double p = reduced ? 0.6 : phase(t, 2.6), jump = reduced ? 0 : (p < 0.22 ? StrictMath.sin(Math.PI * p / 0.22) : 0);
                lift = -0.8 - 2.4 * jump;
                sy = 1.04 + 0.03 * jump; sx = 0.97 - 0.02 * jump;
                pitch = -0.06; yaw = 0.03 * wave(t, reduced, 3.3, 0);
                roll = 0.5 * wave(t, reduced, 0.18, 0);
                if (!reduced && p > 0.5) blink = blinkAt(seed, t * 0.6);
                eyes = "wide";
            }
            case "sad" -> {
                // Drooping low and slow, looking down, worried eyes.
                pitch = 0.13; yaw = 0.05 * wave(t, reduced, 7.9, 0);
                roll = -2.5 + 1.2 * wave(t, reduced, 6.4, 1);
                lift = 0.8 + (reduced ? 0 : 0.35 * (1 - StrictMath.cos(TAU * t / 4.6)));
                double breath = wave(t, reduced, 4.6, -Math.PI / 2);
                sy = 0.965 + 0.01 * breath; sx = 1.025 - 0.006 * breath;
                gazeX = 0.15 * wave(t, reduced, 8.3, 0); gazeY = 0.65;
                squintL = 0.1; squintR = 0.1;
                if (!reduced) blink = blinkAt(seed, t * 0.8);
                eyes = "sad";
            }
            case "sleepy" -> {
                // Eyes closed, a slow nod and deep breaths.
                double nod = reduced ? 0 : (1 - StrictMath.cos(TAU * t / 4.8)) / 2;
                pitch = 0.06 + 0.12 * nod; roll = -3 + 2.5 * wave(t, reduced, 9.6, 0);
                lift = 0.3 + 0.6 * nod;
                double breath = wave(t, reduced, 4.8, -Math.PI / 2);
                sy = 1 + 0.02 * breath; sx = 1 - 0.012 * breath;
                gazeY = 0.4;
                eyes = "sleepy";
            }
            case "wink" -> {
                // A real wink: looking at you, the left eye narrows, shuts for half a second with a small head tilt toward
                // it, and opens again; the rest of the loop is a calm breath. A still (reduced motion) holds the wink.
                double p = reduced ? 0.4 : phase(t, 2.8);
                boolean shut = reduced || (p > 0.18 && p < 0.62);
                double tilt = reduced ? 1 : smooth((p - 0.1) / 0.1) * (1 - smooth((p - 0.6) / 0.12));
                roll = -4.5 * tilt + 0.8 * wave(t, reduced, 5.3, 2);
                yaw = 0.04 * tilt; pitch = 0.02;
                double breath = wave(t, reduced, 3.4, -Math.PI / 2);
                lift = -0.6 * tilt - (reduced ? 0 : 0.3 * (1 - StrictMath.cos(TAU * t / 3.4)));
                sy = 1 + 0.012 * breath + 0.015 * tilt; sx = 1 - 0.008 * breath;
                if (!shut && !reduced) squintL = p < 0.18 ? smooth((p - 0.1) / 0.08) : 0;
                squintR = 0.15 * tilt;
                // Round eyes for the whole mood: the library's tall pill beside a shut eye reads as a bar ("^ |").
                eyes = shut ? "wink" : "round";
            }
            case "love" -> {
                // Heart eyes and a heartbeat: the body swells twice a second-ish and sways.
                double pulse = reduced ? 0 : Math.pow(Math.max(0, StrictMath.sin(TAU * t / 1.1)), 4);
                sx = 1 + 0.035 * pulse; sy = 1 + 0.035 * pulse;
                roll = 3.5 * wave(t, reduced, 2.6, 0); yaw = 0.06 * wave(t, reduced, 5.2, 0.4);
                lift = -0.8 - (reduced ? 0 : 0.6 * (1 - StrictMath.cos(TAU * t / 2.6)) / 2);
                eyes = "heart";
            }
            default -> {
                // angry: a glare with frowning eyes and short huffy shakes.
                double p = reduced ? 0.5 : phase(t, 2.2), gate = reduced ? 0 : (p < 0.25 ? StrictMath.sin(Math.PI * p / 0.25) : 0);
                double shake = wave(t, reduced, 0.32, 0);
                yaw = 0.05 * shake * gate; roll = 2.2 * shake * gate;
                pitch = 0.07; lift = 0.3;
                sy = 0.97 - 0.01 * gate; sx = 1.03 + 0.02 * gate;
                squintL = 0.12; squintR = 0.12;
                eyes = "angry";
            }
        }
        return new BotAvatar.Pose(yaw, pitch, roll, lift, sx, sy, gazeX, gazeY, blink, squintL, squintR, eyes);
    }

    private static double wave(double t, boolean reduced, double period, double phase) {
        return reduced ? 0 : StrictMath.sin((TAU * t) / period + phase);
    }
}
