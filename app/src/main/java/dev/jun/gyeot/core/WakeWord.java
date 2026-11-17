package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 호출어: a personal wake word from the user's own recordings of "들퍼레" and "퍼레", matched on the phone
 * with no network and no model download. 16 kHz mono PCM becomes MFCC frames (12 cepstra and their deltas, 10 ms apart); an
 * adaptive energy gate cuts speech into segments; each segment's start is compared with every recording by open-end dynamic
 * time warping, so "퍼레, 길 안내해 줘" matches as well as "퍼레" alone. The threshold comes from how alike the recordings are to
 * one another, loosened or tightened by the sensitivity the user picks. Speaker-dependent by design: it is the user's voice.
 */
public final class WakeWord {
    public static final int RATE = 16_000, WINDOW = 400, HOP = 160, FFT = 512, MELS = 26, CEPSTRA = 12;
    /** Feature size: the cepstra and their deltas. */
    public static final int DIM = CEPSTRA * 2;
    /** A recording shorter than this many frames (0.25 s of speech) or longer than 2 s is refused. */
    public static final int MIN_FRAMES = 25, MAX_FRAMES = 200;
    /** Recordings the user makes: this many is enough to set a threshold; more is better. */
    public static final int ENROLL = 5;
    /** Sensitivity steps the user picks, 1 strict to 5 loose; 3 by default. */
    public static final int SENSITIVITY_MIN = 1, SENSITIVITY_MAX = 5, SENSITIVITY_DEFAULT = 3;
    /** The threshold's widening over the recordings' spread: SPREAD_BASE + SPREAD_STEP × sensitivity (Model.factor). */
    public static final double SPREAD_BASE = 0.95, SPREAD_STEP = 0.1;
    private WakeWord() {}

    // ── Features ───────────────────────────────────────────────────────────────────────────────────────

    /** Mel filterbank over the FFT bins, 20 Hz to 7.6 kHz, built once. */
    private static final float[][] FILTERS = filters();
    private static final float[] HAMMING = new float[WINDOW];
    static { for (int i = 0; i < WINDOW; i++) HAMMING[i] = (float) (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (WINDOW - 1))); }

    /** One analysed frame: its cepstra and its log energy (for the speech gate). */
    public record Frame(float[] cepstra, float energy) {}

    /** Turns a stream of PCM into frames as it comes, keeping the tail of each chunk for the next. */
    public static final class Framer {
        private final short[] carry = new short[WINDOW]; private int carried;
        private float previous;
        public List<Frame> add(short[] pcm, int length) {
            List<Frame> out = new ArrayList<>();
            int at = 0;
            while (true) {
                int need = WINDOW - carried;
                if (length - at < need) { System.arraycopy(pcm, at, carry, carried, length - at); carried += length - at; return out; }
                System.arraycopy(pcm, at, carry, carried, need);
                out.add(frame(carry));
                // Keep the overlap: the next frame starts HOP samples later.
                System.arraycopy(carry, HOP, carry, 0, WINDOW - HOP); carried = WINDOW - HOP; at += need;
            }
        }
        private Frame frame(short[] samples) {
            float[] re = new float[FFT], im = new float[FFT];
            double energy = 0;
            for (int i = 0; i < WINDOW; i++) {
                float x = samples[i] / 32768f, emphasized = x - 0.97f * previousOf(i, samples);
                re[i] = emphasized * HAMMING[i]; energy += x * x;
            }
            previous = samples[HOP - 1] / 32768f;
            fft(re, im);
            float[] logMel = new float[MELS];
            for (int m = 0; m < MELS; m++) {
                double sum = 0; float[] filter = FILTERS[m];
                for (int k = 0; k < filter.length; k++) if (filter[k] != 0) sum += filter[k] * (re[k] * re[k] + im[k] * im[k]);
                logMel[m] = (float) Math.log(sum + 1e-10);
            }
            float[] cepstra = new float[CEPSTRA];
            for (int c = 1; c <= CEPSTRA; c++) {
                double sum = 0;
                for (int m = 0; m < MELS; m++) sum += logMel[m] * Math.cos(Math.PI * c * (m + 0.5) / MELS);
                cepstra[c - 1] = (float) sum;
            }
            return new Frame(cepstra, (float) (10 * Math.log10(energy / WINDOW + 1e-10)));
        }
        private float previousOf(int i, short[] samples) { return i == 0 ? previous : samples[i - 1] / 32768f; }
    }

    /** All frames of one finished recording. */
    public static List<Frame> frames(short[] pcm) { return new Framer().add(pcm, pcm.length); }

    /**
     * Matching features for a run of frames: cepstra with their mean taken out (the microphone and the room drop away) and
     * their deltas, DIM per frame.
     */
    public static float[][] features(List<Frame> frames) { return features(frames, frames.size()); }
    /**
     * As above, the mean taken over the first meanOver frames only: a segment that goes on into a request ("퍼레, 길 안내해 줘")
     * is normalized by its start, where the wake word is, as a recording is by itself.
     */
    public static float[][] features(List<Frame> frames, int meanOver) {
        int n = frames.size(); float[][] out = new float[n][DIM];
        // The mean of the loud frames only: pauses and the quiet the gate keeps would pull it toward the noise.
        float[] mean = new float[CEPSTRA]; float peak = -200f; int loud = 0;
        List<Frame> head = frames.subList(0, Math.max(1, Math.min(n, meanOver)));
        for (Frame frame : head) peak = Math.max(peak, frame.energy());
        for (Frame frame : head) if (frame.energy() >= peak - 15f) { loud++; for (int c = 0; c < CEPSTRA; c++) mean[c] += frame.cepstra()[c]; }
        for (int c = 0; c < CEPSTRA; c++) mean[c] /= Math.max(1, loud);
        for (int t = 0; t < n; t++) for (int c = 0; c < CEPSTRA; c++) out[t][c] = frames.get(t).cepstra()[c] - mean[c];
        for (int t = 0; t < n; t++) for (int c = 0; c < CEPSTRA; c++) {
            float ahead = out[Math.min(n - 1, t + 1)][c] + 2 * out[Math.min(n - 1, t + 2)][c], behind = out[Math.max(0, t - 1)][c] + 2 * out[Math.max(0, t - 2)][c];
            out[t][CEPSTRA + c] = (ahead - behind) / 10f;
        }
        return out;
    }

    // ── Speech gate ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Cuts frames into speech segments: a frame is speech while its energy is SPEECH_DB above the noise floor, which follows
     * the quietest recent frames (road noise rises, it follows). A segment opens after 3 speech frames, keeps 10 frames of what
     * came before, and closes after 30 frames (0.3 s) of quiet or at MAX_FRAMES.
     */
    public static final class Gate {
        static final float SPEECH_DB = 9f;
        private float floor = Float.NaN; private final ArrayList<Frame> recent = new ArrayList<>(), segment = new ArrayList<>();
        private int loud, quiet; private boolean open;
        /** What the segment holds so far (open or just closed). */
        public List<Frame> segment() { return segment; }
        public boolean open() { return open; }
        /** Feeds one frame; true when a segment just closed (read it with segment(), then it starts over on the next frame). */
        public boolean add(Frame frame) {
            if (!open && !segment.isEmpty()) segment.clear();
            float e = frame.energy();
            if (Float.isNaN(floor)) floor = e;
            // The floor drops at once to a quieter frame and rises slowly, so speech never lifts it much.
            floor = e < floor ? e : floor + 0.02f * (e - floor);
            boolean speech = e > floor + SPEECH_DB && e > -65f;
            if (!open) {
                recent.add(frame); if (recent.size() > 13) recent.remove(0);
                loud = speech ? loud + 1 : 0;
                if (loud >= 3) { open = true; quiet = 0; segment.clear(); segment.addAll(recent); recent.clear(); }
                return false;
            }
            segment.add(frame);
            quiet = speech ? 0 : quiet + 1;
            if (quiet >= 30 || segment.size() >= MAX_FRAMES + 40) { open = false; loud = 0; return true; }
            return false;
        }
    }

    /** The speech part of a recording (the gate's longest segment), or null when there is none of a usable length. */
    public static float[][] enrolled(short[] pcm) {
        Gate gate = new Gate(); List<Frame> best = List.of();
        List<Frame> all = frames(pcm);
        // Trailing quiet closes the last segment.
        List<Frame> padded = new ArrayList<>(all); Frame silence = new Frame(new float[CEPSTRA], -100f);
        for (int i = 0; i < 40; i++) padded.add(silence);
        for (Frame frame : padded) if (gate.add(frame) && gate.segment().size() > best.size()) best = new ArrayList<>(gate.segment());
        // The closing quiet is not part of the word.
        int end = best.size(); while (end > 0 && best.get(end - 1).energy() <= -99f) end--;
        List<Frame> word = trim(best.subList(0, end));
        return word.size() < MIN_FRAMES || word.size() > MAX_FRAMES ? null : features(word);
    }
    /** Frames without the quiet at either end (more than 12 dB under the loudest frame). */
    static List<Frame> trim(List<Frame> frames) {
        float peak = -200f; for (Frame frame : frames) peak = Math.max(peak, frame.energy());
        int start = 0, end = frames.size();
        while (start < end && frames.get(start).energy() < peak - 12f) start++;
        while (end > start && frames.get(end - 1).energy() < peak - 12f) end--;
        return frames.subList(start, end);
    }

    // ── Matching ───────────────────────────────────────────────────────────────────────────────────────

    /** Frames a match may start after the segment's start: the gate opens a little before the word. */
    static final int START_SLACK = 20;

    /**
     * How far the start of a segment is from a recording: open-end DTW (the match may start in the first START_SLACK frames and
     * end anywhere from 0.6 to 1.6 times the recording's length), the path's mean frame distance. Lower is closer.
     */
    public static double distance(float[][] template, float[][] segment) {
        int n = template.length, m = Math.min(segment.length, START_SLACK + (int) (n * 1.6) + 1);
        if (n == 0 || m < (int) (n * 0.6)) return Double.POSITIVE_INFINITY;
        double[][] cost = new double[n][m]; int[][] steps = new int[n][m];
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) {
            double d = frameDistance(template[i], segment[j]);
            if (i == 0) {
                // A free start in the slack; afterwards the first template frame may only stretch.
                if (j < START_SLACK) { cost[0][j] = d; steps[0][j] = 1; }
                else { cost[0][j] = cost[0][j - 1] + d; steps[0][j] = steps[0][j - 1] + 1; }
                continue;
            }
            double best = cost[i - 1][j]; int count = steps[i - 1][j];
            if (j > 0 && cost[i - 1][j - 1] <= best) { best = cost[i - 1][j - 1]; count = steps[i - 1][j - 1]; }
            if (j > 0 && cost[i][j - 1] < best) { best = cost[i][j - 1]; count = steps[i][j - 1]; }
            cost[i][j] = best + d; steps[i][j] = count + 1;
        }
        double best = Double.POSITIVE_INFINITY;
        for (int j = (int) (n * 0.6); j < m; j++) best = Math.min(best, cost[n - 1][j] / steps[n - 1][j]);
        return best;
    }
    private static double frameDistance(float[] a, float[] b) {
        double sum = 0; for (int k = 0; k < DIM; k++) { double d = a[k] - b[k]; sum += d * d; }
        return Math.sqrt(sum);
    }

    /**
     * The user's recordings and the threshold they set: each recording's distance to its nearest other one tells how much the
     * user's own repeats vary; the threshold is the largest of those, widened by sensitivity: SPREAD_BASE + SPREAD_STEP × step
     * (1: x1.05, 3: x1.25, 5: x1.45). Step 1
     * still stays above x1, so every recording's own variation passes.
     */
    public record Model(List<float[][]> templates, double threshold) {
        public static Model of(List<float[][]> templates, int sensitivity) {
            if (templates.size() < 2) throw new IllegalArgumentException("호출어 녹음이 두 개 이상 필요해요.");
            double spread = 0;
            for (int a = 0; a < templates.size(); a++) {
                double nearest = Double.POSITIVE_INFINITY;
                for (int b = 0; b < templates.size(); b++) if (a != b) nearest = Math.min(nearest, distance(templates.get(b), templates.get(a)));
                spread = Math.max(spread, nearest);
            }
            int step = Math.max(SENSITIVITY_MIN, Math.min(SENSITIVITY_MAX, sensitivity));
            return new Model(List.copyOf(templates), spread * factor(step));
        }
        /** How far past the recordings' own spread a segment may be, for a sensitivity step (clamped to 1–5). */
        public static double factor(int sensitivity) {
            return SPREAD_BASE + SPREAD_STEP * Math.max(SENSITIVITY_MIN, Math.min(SENSITIVITY_MAX, sensitivity));
        }
        /** The closest recording's distance from the start of this segment. */
        public double score(float[][] segment) {
            double best = Double.POSITIVE_INFINITY;
            for (float[][] template : templates) best = Math.min(best, distance(template, segment));
            return best;
        }
        public boolean wakes(float[][] segment) { return score(segment) <= threshold; }
    }

    /**
     * Listens to a stream: frames, the speech gate, and one check per segment, made as soon as the segment is long enough to
     * hold the longest recording (so a request said straight after the wake word does not hold it up) or when it closes.
     */
    public static final class Listener {
        private final Model model; private final Framer framer = new Framer(); private final Gate gate = new Gate();
        private final int enough, typical; private boolean checked; private double last = Double.NaN;
        public Listener(Model model) {
            this.model = model; int longest = 0, total = 0;
            for (float[][] template : model.templates()) { longest = Math.max(longest, template.length); total += template.length; }
            enough = START_SLACK + (int) (longest * 1.6) + 1; typical = total / Math.max(1, model.templates().size());
        }
        /** The score of the last segment checked (NaN before any), for the settings' test. */
        public double lastScore() { return last; }
        public double threshold() { return model.threshold(); }
        /** Feeds PCM; true when the wake word was heard in it. */
        public boolean feed(short[] pcm, int length) {
            boolean heard = false;
            for (Frame frame : framer.add(pcm, length)) {
                boolean closed = gate.add(frame);
                List<Frame> segment = gate.segment();
                if (gate.open() && !checked && segment.size() >= enough) { checked = true; heard |= check(segment); }
                if (closed) { if (!checked) heard |= check(segment); checked = false; }
            }
            return heard;
        }
        private boolean check(List<Frame> segment) {
            // From the first loud frame: the gate keeps some quiet before the word.
            List<Frame> region = segment.subList(0, Math.min(segment.size(), enough));
            float peak = -200f; for (Frame frame : region) peak = Math.max(peak, frame.energy());
            int start = 0; while (start < region.size() && region.get(start).energy() < peak - 12f) start++;
            region = region.subList(start, region.size());
            if (region.size() < MIN_FRAMES) return false;
            float[][] features = features(region, typical);
            last = model.score(features);
            return last <= model.threshold();
        }
    }

    // ── FFT and filters ────────────────────────────────────────────────────────────────────────────────

    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1; for (; (j & bit) != 0; bit >>= 1) j ^= bit; j ^= bit;
            if (i < j) { float t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len; float wr = (float) Math.cos(angle), wi = (float) Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    float tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti;
                    float next = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = next;
                }
            }
        }
    }
    private static float[][] filters() {
        int bins = FFT / 2 + 1; float[][] bank = new float[MELS][bins];
        double low = mel(20), high = mel(7600);
        double[] centers = new double[MELS + 2];
        for (int i = 0; i < centers.length; i++) centers[i] = hz(low + (high - low) * i / (MELS + 1)) * FFT / RATE;
        for (int m = 0; m < MELS; m++) for (int k = 0; k < bins; k++) {
            double left = centers[m], center = centers[m + 1], right = centers[m + 2];
            if (k > left && k <= center) bank[m][k] = (float) ((k - left) / (center - left));
            else if (k > center && k < right) bank[m][k] = (float) ((right - k) / (right - center));
        }
        return bank;
    }
    private static double mel(double hz) { return 2595 * Math.log10(1 + hz / 700); }
    private static double hz(double mel) { return 700 * (Math.pow(10, mel / 2595) - 1); }
    /** For tests: the filterbank's shape. */
    static int[] filterShape() { return new int[]{FILTERS.length, FILTERS[0].length}; }
    /** For tests: the Hamming window's ends. */
    static float[] windowEnds() { return Arrays.copyOf(new float[]{HAMMING[0], HAMMING[WINDOW / 2]}, 2); }
}
