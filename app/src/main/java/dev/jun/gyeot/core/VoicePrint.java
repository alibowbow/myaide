package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The user's voice, known (준: "내 목소리를 알아볼 수 있게해줘"). A voice's mark: the mean and the spread of its cepstra over
 * the loud frames of what it said (WakeWord's analysis, 16 kHz), with its pitch (median and spread of log F0). Marks are
 * projected by a linear discriminant learned from 130 LibriSpeech speakers (validation, test-other, three train-clean-100
 * shards; 16 clips each), where one speaker's clips lie close and different speakers' apart, and compared by cosine with
 * the mean of the user's own (read once, ENROLL sentences, and the confident ones since). On LibriSpeech test-clean (40
 * speakers it never saw, 3 sentences of 4 s to enroll, 3 s to check): below STRANGER, 1.9 % of the owner's sentences and
 * 77 % of others'; at OWNER or above, 86 % of the owner's and 2.3 % of others'. LibriSpeech's speakers each had their own
 * microphone, which helps; on one phone voices are nearer, so 퍼레 also learns the owner's confident ones as it hears them. A rough check on the phone, no network:
 * 퍼레 only keeps the user's memories from a voice it does not know; the fingerprint still guards what is private. Pure Java.
 */
public final class VoicePrint {
    private VoicePrint() {}
    public static final int ENROLL = 3, LEARNED = 6, DIM = WakeWord.CEPSTRA * 2 + 2;
    /** Cosine with the user's voice: at or above OWNER, the owner (and learned from); below STRANGER, someone else. */
    public static final double OWNER = 0.55, STRANGER = 0.25;
    public enum Who { OWNER, UNSURE, STRANGER }

    /** A voice's mark from 16 kHz mono speech, or null when there is under half a second of it. */
    public static float[] mark(short[] pcm) {
        List<WakeWord.Frame> frames = WakeWord.frames(pcm);
        float peak = -200f; for (WakeWord.Frame f : frames) peak = Math.max(peak, f.energy());
        List<float[]> loud = new ArrayList<>();
        for (WakeWord.Frame f : frames) if (f.energy() >= peak - 18f && f.energy() > -60f) loud.add(f.cepstra());
        if (loud.size() < 50) return null;
        int c = WakeWord.CEPSTRA; float[] out = new float[DIM];
        for (float[] v : loud) for (int i = 0; i < c; i++) out[i] += v[i];
        for (int i = 0; i < c; i++) out[i] /= loud.size();
        for (float[] v : loud) for (int i = 0; i < c; i++) { float d = v[i] - out[i]; out[c + i] += d * d; }
        for (int i = 0; i < c; i++) out[c + i] = (float) Math.sqrt(out[c + i] / loud.size());
        double[] pitch = pitch(pcm, frames, peak);
        out[2 * c] = (float) (8 * pitch[0]); out[2 * c + 1] = (float) (4 * pitch[1]);
        return out;
    }
    /**
     * The voice's pitch: the median log F0 and its spread (75th less 25th percentile) over the voiced 45 ms stretches of every
     * other frame within 25 dB of the loudest, by autocorrelation (70–400 Hz, a peak over half of the energy); 150 Hz, no
     * spread, when there are fewer than six.
     */
    static double[] pitch(short[] pcm, List<WakeWord.Frame> frames, float peak) {
        int span = WakeWord.WINDOW + 2 * WakeWord.HOP, lo = WakeWord.RATE / 400, hi = WakeWord.RATE / 70;
        List<Double> logs = new ArrayList<>(); double[] seg = new double[span];
        for (int i = 0; i + 2 < frames.size(); i += 2) {
            if (frames.get(i).energy() < peak - 25f) continue;
            int at = i * WakeWord.HOP; if (at + span > pcm.length) break;
            double mean = 0; for (int k = 0; k < span; k++) { seg[k] = pcm[at + k] / 32768.0; mean += seg[k]; }
            mean /= span; for (int k = 0; k < span; k++) seg[k] -= mean;
            double zero = 0; for (int k = 0; k < span; k++) zero += seg[k] * seg[k];
            if (zero <= 0) continue;
            double best = -2; int lag = lo;
            for (int l = lo; l < hi; l++) { double s = 0; for (int k = 0; k + l < span; k++) s += seg[k] * seg[k + l]; s /= zero; if (s > best) { best = s; lag = l; } }
            if (best > 0.5) logs.add(Math.log((double) WakeWord.RATE / lag));
        }
        if (logs.size() <= 5) return new double[]{Math.log(150), 0};
        double[] v = logs.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return new double[]{percentile(v, 0.5), percentile(v, 0.75) - percentile(v, 0.25)};
    }
    private static double percentile(double[] sorted, double p) {
        double at = p * (sorted.length - 1); int i = (int) Math.floor(at); double f = at - i;
        return i + 1 < sorted.length ? sorted[i] * (1 - f) + sorted[i + 1] * f : sorted[i];
    }
    /** A mark in the space where voices differ (the discriminant, whitened within a speaker). */
    public static double[] embed(float[] mark) {
        double[] out = new double[LDA[0].length];
        for (int i = 0; i < DIM; i++) { double x = mark[i] - MU[i]; for (int j = 0; j < out.length; j++) out[j] += x * LDA[i][j]; }
        return out;
    }

    /** The user's voice: the sentences read (enrolled) and the confident ones heard since (learned, the latest LEARNED). */
    public record Profile(List<float[]> enrolled, List<float[]> learned) {
        public Profile { enrolled = List.copyOf(enrolled); learned = List.copyOf(learned); }
        public static Profile empty() { return new Profile(List.of(), List.of()); }
        public boolean ready() { return enrolled.size() >= ENROLL; }
        public Profile enroll(float[] mark) { if (mark == null) return this; List<float[]> e = new ArrayList<>(enrolled); e.add(mark); return new Profile(e, learned); }
        public Profile learn(float[] mark) {
            if (mark == null) return this;
            List<float[]> l = new ArrayList<>(learned); l.add(mark);
            while (l.size() > LEARNED) l.remove(0);
            return new Profile(enrolled, l);
        }
        /** The mean of every mark's embedding. */
        public double[] centre() {
            double[] c = null; int n = 0;
            for (List<float[]> part : List.of(enrolled, learned)) for (float[] m : part) {
                double[] e = embed(m); if (c == null) c = new double[e.length];
                for (int i = 0; i < e.length; i++) c[i] += e[i]; n++;
            }
            if (c != null) for (int i = 0; i < c.length; i++) c[i] /= n;
            return c;
        }
        public String json() {
            JSONObject o = new JSONObject();
            try { o.put("enrolled", marks(enrolled)).put("learned", marks(learned)); } catch (Exception ignored) {}
            return o.toString();
        }
        private static JSONArray marks(List<float[]> list) {
            JSONArray a = new JSONArray();
            for (float[] m : list) { JSONArray v = new JSONArray(); for (float x : m) try { v.put((double) x); } catch (Exception ignored) {} a.put(v); }
            return a;
        }
        public static Profile read(String saved) {
            try {
                JSONObject o = new JSONObject(saved == null || saved.isBlank() ? "{}" : saved);
                return new Profile(marks(o.optJSONArray("enrolled")), marks(o.optJSONArray("learned")));
            } catch (Exception unreadable) { return empty(); }
        }
        private static List<float[]> marks(JSONArray a) {
            List<float[]> out = new ArrayList<>();
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONArray v = a.optJSONArray(i); if (v == null || v.length() != DIM) continue;
                float[] m = new float[DIM]; for (int k = 0; k < DIM; k++) m[k] = (float) v.optDouble(k); out.add(m);
            }
            return out;
        }
    }
    /** How alike a mark is to the user's voice: the cosine of their embeddings, -1 to 1 (NaN without a profile). */
    public static double score(Profile p, float[] mark) {
        if (p == null || !p.ready() || mark == null) return Double.NaN;
        double[] c = p.centre(), e = embed(mark);
        double dot = 0, a = 0, b = 0;
        for (int i = 0; i < c.length; i++) { dot += c[i] * e[i]; a += c[i] * c[i]; b += e[i] * e[i]; }
        return a == 0 || b == 0 ? 0 : dot / Math.sqrt(a * b);
    }
    /** Who spoke: the owner, unsure, or someone else; unsure when there is no profile or too little speech. */
    public static Who who(Profile p, float[] mark) {
        double s = score(p, mark);
        return Double.isNaN(s) ? Who.UNSURE : s >= OWNER ? Who.OWNER : s < STRANGER ? Who.STRANGER : Who.UNSURE;
    }
    /** How the enrolled sentences agree (the lowest cosine of one with the others' mean): low when one was not the same voice. */
    public static double agreement(List<float[]> given) {
        List<float[]> marks = new ArrayList<>(); for (float[] m : given) if (m != null) marks.add(m);
        double worst = 1;
        for (int i = 0; i < marks.size(); i++) {
            List<float[]> others = new ArrayList<>(marks); float[] one = others.remove(i);
            Profile p = new Profile(others, List.of());
            if (others.isEmpty()) continue;
            double[] c = p.centre(), e = embed(one); double dot = 0, a = 0, b = 0;
            for (int k = 0; k < c.length; k++) { dot += c[k] * e[k]; a += c[k] * c[k]; b += e[k] * e[k]; }
            worst = Math.min(worst, a == 0 || b == 0 ? 0 : dot / Math.sqrt(a * b));
        }
        return worst;
    }

    // The discriminant (tools: LibriSpeech features as in mark(), LDA to 20 dimensions, whitened within a speaker).
    private static final float[] MU = {1.20562f, -5.41222f, 3.65479f, -6.66658f, -5.43646f, -5.21416f, -3.78509f, -1.33403f, -1.26767f, -1.02254f, -1.43494f, -1.09214f, 22.7493f, 13.3731f, 11.8186f, 8.79564f, 7.25218f, 6.575f, 5.64742f, 5.21431f, 4.68887f, 4.11147f, 3.92645f, 3.66352f, 40.602f, 1.16229f};
    private static final float[][] LDA = {
        {-0.0653894f, 0.211853f, 0.0017936f, 0.0984033f, 0.013398f, 0.0673474f, 0.0150536f, -0.0353624f, -0.0201408f, -0.0303029f, -0.0344634f, -0.0265583f, 0.0114957f, 0.0204067f, -0.0108269f, 0.00754335f, -0.0036877f, -0.00803273f, -0.000910992f, 0.0166643f},
        {-0.115322f, 0.041527f, -0.136529f, -0.133739f, 0.109371f, 0.0931085f, 0.0228735f, 0.133802f, 0.0345599f, 0.0273636f, -0.0367607f, 0.0317943f, -0.0424811f, -0.0348241f, 0.00161417f, 0.0520336f, 0.0012245f, -0.00945556f, -0.00984451f, -0.00591899f},
        {-0.0357756f, 0.141471f, 0.238289f, 0.162563f, 0.00456911f, 0.0150863f, 0.0299446f, 0.0846141f, 0.0253572f, 0.0521549f, 0.0657356f, -0.056525f, -0.0243655f, -0.108866f, 0.0264604f, 0.0380043f, -0.0592938f, -0.0128739f, -0.00390929f, 0.00596087f},
        {-0.223945f, -0.0902256f, -0.062709f, -0.0266294f, 0.0123508f, -0.218041f, -0.0433063f, -0.172804f, -0.032501f, 0.110506f, -0.058361f, -0.139562f, 0.115401f, -0.0647451f, 0.0487101f, -0.0929607f, 0.00490441f, 0.0145398f, 0.0107381f, -0.0237792f},
        {-0.0243815f, 0.0932508f, 0.443017f, -0.0515981f, 0.200289f, -0.0287986f, -0.117031f, 0.0873915f, -0.125569f, 0.0746528f, -0.0760516f, 0.0195843f, 0.0615715f, 0.0908034f, -0.00130654f, 0.00582136f, 0.0274113f, 0.0129219f, -0.0320163f, -0.0481635f},
        {-0.236808f, -0.201568f, -0.0885041f, 0.243356f, 0.138636f, -0.235681f, 0.0987699f, 0.078431f, -0.0436073f, -0.149021f, 0.0792013f, 0.0137005f, -0.152339f, 0.0951381f, 0.0527641f, 0.0269457f, -0.0129265f, 0.0530263f, -0.00284762f, -0.0333169f},
        {0.015701f, 0.117158f, 0.224388f, -0.190671f, 0.165966f, 0.113026f, 0.248273f, -0.162293f, 0.10715f, -0.369509f, -0.00358596f, -0.193322f, -0.0467475f, -0.0279536f, 0.104936f, 0.0293158f, 0.00233626f, 0.0636692f, -0.00345293f, 0.0171739f},
        {-0.111205f, -0.275079f, -0.141214f, 0.216252f, 0.183523f, 0.330741f, 0.148448f, 0.34136f, 0.129685f, -0.130498f, -0.110137f, -0.0304341f, 0.302412f, -0.0693387f, 0.0877889f, -0.106514f, 0.0721857f, 0.028444f, -0.0110728f, -0.0633796f},
        {-0.132458f, 0.104845f, 0.131527f, -0.0654377f, -0.371135f, 0.0578903f, 0.478542f, 0.172535f, 0.140317f, 0.265026f, 0.138304f, -0.112496f, 0.050361f, 0.127349f, 0.00739332f, 0.0840473f, 0.147165f, 0.0623291f, -0.0345862f, -0.0964058f},
        {-0.114373f, -0.420585f, -0.0578785f, 0.126921f, -0.00223615f, 0.36087f, -0.122439f, -0.295098f, -0.188931f, 0.244583f, 0.0385059f, -0.249128f, -0.165001f, 0.139325f, 0.000382112f, 0.235881f, -0.00424542f, -0.0115739f, -0.161922f, 0.0690629f},
        {-0.0229733f, -0.129981f, 0.240188f, -0.175582f, -0.385937f, 0.0772858f, 0.10227f, -0.0599688f, -0.352413f, -0.155653f, -0.378039f, 0.39089f, -0.080425f, -0.0663998f, 0.143557f, 0.102901f, -0.275064f, -0.032759f, 0.0579672f, 0.150298f},
        {0.0974497f, -0.0708705f, -0.0706693f, 0.172056f, 0.362747f, 0.0643164f, 0.275033f, -0.445266f, -0.0622926f, 0.213575f, 0.184204f, 0.590786f, 0.071478f, 0.0549366f, -0.0191117f, -0.146582f, -0.0140479f, 0.0393775f, 0.111605f, -0.00900096f},
        {-0.0259265f, 0.118789f, -0.00150638f, 0.100658f, -0.0127412f, 0.012129f, 0.0223076f, -0.0227275f, 0.0488212f, -0.041713f, -0.0262824f, -0.0527501f, 0.043269f, 0.0547988f, -0.129855f, 0.0134611f, -0.0541378f, -0.00980348f, 0.0174308f, 0.0624744f},
        {0.111456f, 0.0259878f, -0.0248809f, 0.106351f, 0.0430442f, -0.0380383f, 0.0621579f, -0.0449409f, -0.00622451f, 0.112236f, -0.265997f, 0.0956954f, -0.0766541f, -0.247759f, 0.00661916f, 0.29674f, 0.125807f, 0.007426f, -0.0527213f, 0.0405648f},
        {0.0600722f, -0.0177801f, -0.00931637f, 0.00633778f, 0.0273617f, -0.00326652f, 0.0510171f, -0.0357074f, 0.0610104f, -0.0142475f, -0.153055f, -0.073095f, 0.0411026f, 0.153003f, 0.0517276f, 0.0822774f, 0.08206f, -0.152741f, 0.15463f, -0.288115f},
        {0.0464752f, -0.114542f, 0.139434f, 0.0274746f, -0.0474005f, -0.163333f, -0.0143005f, -0.0931463f, 0.104947f, 0.0764235f, -0.0851656f, -0.170282f, 0.0517898f, -0.0391546f, 0.0159309f, -0.166112f, 0.221009f, -0.24036f, -0.0202383f, -0.0738032f},
        {-0.0452139f, -0.0237092f, -0.0883811f, 0.0781022f, 0.0538195f, -0.109581f, 0.153433f, -0.0944947f, 0.0578433f, -0.0190643f, -0.00727396f, 0.0483705f, 0.0073421f, 0.0324456f, -0.119683f, -0.157272f, 0.0354201f, -0.244755f, -0.57093f, 0.232051f},
        {-0.0196463f, -0.00913648f, -0.0248359f, -0.00719575f, 0.0241135f, 0.0245637f, 0.0418275f, -0.0064893f, 0.265269f, 0.157487f, -0.04283f, -0.0778316f, -0.0488959f, -0.139325f, 0.150101f, 0.117963f, -0.194793f, -0.208936f, 0.541172f, -0.0835278f},
        {-0.0487664f, -0.0233468f, 0.0850763f, -0.0149584f, -0.0789033f, 0.043631f, 0.0709025f, -0.0717188f, 0.146463f, -0.115667f, -0.271725f, -0.0519413f, -0.27394f, -0.17566f, -0.203669f, -0.0481105f, -0.249762f, 0.145332f, -0.570796f, -0.594271f},
        {-0.0488746f, -0.0766225f, 0.0695285f, 0.0773374f, 0.0897714f, -0.120114f, 0.0810953f, 0.071647f, 0.134234f, 0.000955364f, -0.382826f, 0.0461729f, -0.0159765f, 0.347885f, -0.0068106f, -0.0258668f, 0.395797f, 0.445803f, 0.169654f, 0.816012f},
        {-0.181773f, -0.119088f, 0.156566f, -0.0317271f, -0.0404094f, 0.19988f, 0.315689f, 0.0580568f, 0.331701f, 0.219303f, -0.027573f, -0.0122173f, -0.359606f, 0.0715584f, 0.487464f, -0.548992f, 0.351207f, -0.44669f, -0.358325f, 0.438949f},
        {-0.0361227f, -0.0376524f, 0.0206786f, 0.0772499f, 0.0544814f, 0.195608f, 0.124881f, -0.0155008f, 0.297393f, 0.160731f, -0.218705f, 0.0290704f, -0.275052f, -0.171933f, 0.220924f, -0.343019f, -0.251626f, 0.947352f, -0.0540225f, -0.184671f},
        {-0.0444527f, -0.0697224f, 0.22089f, 0.0815886f, 0.151145f, 0.0913679f, 0.0959459f, -0.0131548f, 0.196983f, 0.162625f, -0.133171f, 0.0286498f, -0.0854675f, 0.332611f, 0.135817f, -0.185862f, -1.20157f, -0.334677f, 0.202435f, 0.0325122f},
        {-0.298964f, -0.0119032f, 0.0442918f, 0.163161f, 0.0416804f, 0.176373f, 0.0726238f, 0.000458651f, 0.269626f, 0.196856f, -0.133706f, -0.150114f, -0.39662f, 0.0582688f, -0.0706453f, -0.0171355f, 0.0799718f, 0.861281f, 0.399359f, -0.292159f},
        {0.162441f, 0.101385f, -0.0574463f, 0.120581f, 0.194837f, -0.00642986f, 0.472175f, 0.130281f, -0.525456f, 0.195453f, -0.165672f, -0.338922f, 0.0487511f, 0.0621712f, 0.175105f, 0.0295396f, -0.174159f, 0.00482711f, -0.0670712f, 0.0576711f},
        {-0.223542f, 0.0359306f, 0.124004f, 0.0708645f, -0.0750584f, 0.235225f, 0.0778478f, -0.0235095f, -0.446691f, -0.129059f, 0.0442122f, -0.0567522f, -0.41981f, -0.473967f, -0.863604f, -0.47605f, 0.370023f, -0.231806f, 0.455722f, -0.0234219f}
    };
}
