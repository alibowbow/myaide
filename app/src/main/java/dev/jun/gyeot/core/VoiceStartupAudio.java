package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;

/** Short memory-only PCM pre-roll for a newly authorized voice connection. */
public final class VoiceStartupAudio {
    public static final int MAX_BYTES=16000*2*8;
    /** Room sound kept when a connection takes over a pre-roll that holds no words yet (keepLead): one second. */
    public static final int LEAD_BYTES=16000*2;
    /**
     * The room's level for the first-turn note (core/FirstTurn), at the microphone before its software gain: a frame at or
     * above it may be a soft voice under the speech line (RMS 160). Half the speech line, well above a quiet room's RMS 10 to 50.
     */
    public static final int ROOM_RMS=80;
    /** The stillness the first-turn note waits for before it goes: half a second of 16 kHz 16-bit PCM. */
    public static final int STILL_BYTES=16000;
    /** Two frames in a row above the room's level are taken for a voice; one alone is a click, a tap or the phone being moved. */
    public static final int VOICE_FRAMES=2;
    /** The first-turn note's answers (note, live): send it now, wait for stillness, a voice was heard, words were dropped. */
    public static final int GO=0, WAIT=1, VOICE=2, DROPPED=3;
    private final List<byte[]> pending=new ArrayList<>();
    /** For each frame in pending: when it came, whether it is above the room's level (1), its level and its length. */
    private final List<long[]> marks=new ArrayList<>();
    private int bytes, quietFrames;
    private long firstAt;
    /** lost: words were dropped (overflow) since the last report, kept through the silence that ends an overflow. */
    private boolean speech, overflow, lost;
    /** The first-turn note's count: frames in a row above the room's level, the stillness since, and a voice heard in room
     * sound that keepLead let go. */
    private int run, stillBytes;
    private boolean voiceBefore;
    /** The loudest frame above the room's level in what the note looked at last, and when it came; the same for the voice
     * in room sound that keepLead let go. */
    private int loudest, beforeLoudest; private long loudestAt, beforeAt;
    /** While the app's own short sound may be in the microphone (appSound): it is not the user's voice. */
    private long ownFrom=Long.MAX_VALUE, ownTo=Long.MIN_VALUE;
    private java.util.function.DoubleSupplier gain;
    public synchronized List<byte[]> accept(byte[] pcm,boolean ready,long now) {
        if(pcm==null||pcm.length==0)return List.of();
        boolean quiet=quiet(pcm);
        // Never send a clipped trailing command after overflow. Resume at a silence
        // boundary, where the user can start a new complete utterance.
        if(overflow) {quietFrames=quiet?quietFrames+1:0;if(quietFrames>=6){reset();}return List.of();}
        if(ready) {List<byte[]> out=drain(now);if(!overflow)out.add(pcm);return out;}
        if(!speech && !quiet) {speech=true;firstAt=now;}
        // Retain quiet frames too: a soft first word must not be clipped by this
        // local energy threshold. VAD is used only to recover from overflow.
        if(bytes+pcm.length>MAX_BYTES || speech && now-firstAt>10_000) {
            pending.clear();marks.clear();bytes=0;overflow=true;lost=true;quietFrames=0;return List.of();
        }
        int level=rms(pcm);
        pending.add(pcm.clone());marks.add(new long[]{now,aboveRoom(level)?1:0,level,pcm.length});bytes+=pcm.length;return List.of();
    }
    public synchronized List<byte[]> drain(long now) {
        if(overflow)return new ArrayList<>();
        if(speech && now-firstAt>10_000){pending.clear();marks.clear();bytes=0;overflow=true;lost=true;return new ArrayList<>();}
        List<byte[]> out=new ArrayList<>(pending);pending.clear();marks.clear();bytes=0;speech=false;return out;
    }
    public synchronized boolean overflowed(){return overflow;}
    /**
     * Whether words were dropped since this was last asked: the pre-roll overflowed, now or earlier (the silence that lets an
     * overflow go does not make the words come back). Reported once, at setupComplete, so the user hears to say them again.
     */
    public synchronized boolean takeDropped(){boolean was=overflow||lost;lost=false;return was;}
    /** Whether speech is held (or was, and was dropped): the user started talking before the connection was ready. */
    public synchronized boolean heardSpeech(){return speech||overflow||lost;}
    /**
     * The microphone's software gain right now (Loudness raises soft input up to 4x, the room's sound with it), read as each
     * frame comes, on the microphone's thread: the room's level is compared at the microphone, before that gain. None: 1.
     */
    public synchronized void gain(java.util.function.DoubleSupplier microphone){gain=microphone;}
    /**
     * The app's own short sound (the call greeting's tick, Greeter) may be in the microphone's frames that come from `from`
     * to `to` (System.nanoTime ms, the clock of accept): the first-turn note does not take those for the user's voice. It
     * changes nothing else: every frame is held and sent as it is.
     */
    public synchronized void appSound(long from,long to){ownFrom=from;ownTo=to;}
    /**
     * Whether the first-turn note may follow what is held, asked once before the drain at setupComplete. VOICE when two
     * frames in a row were above the room's level (outside the app's own sound), DROPPED when words were dropped; GO when
     * the last STILL_BYTES held were still; otherwise WAIT, and live() goes on counting from here.
     */
    public synchronized int note(){
        loudest=0;loudestAt=0;
        if(overflow||lost)return DROPPED;
        boolean held=scan();
        // A voice in room sound that was let go: its own level and time, for the phone log.
        if(voiceBefore&&!held){loudest=beforeLoudest;loudestAt=beforeAt;}
        if(voiceBefore||held)return VOICE;
        return stillBytes>=STILL_BYTES?GO:WAIT;
    }
    /** The same for one live frame after setupComplete, in order: VOICE at the second frame in a row above the room's level. */
    public synchronized int live(byte[] pcm,long now){
        int level=rms(pcm);boolean above=aboveRoom(level);
        if(above&&!(now>=ownFrom&&now<=ownTo)){
            run++;stillBytes=0;
            if(level>loudest){loudest=level;loudestAt=now;}
            return run>=VOICE_FRAMES?VOICE:WAIT;
        }
        // A loud frame in the app's own window is neither a voice nor stillness: words said over the tick never count as quiet.
        run=0;if(above)stillBytes=0;else stillBytes+=pcm.length;
        return stillBytes>=STILL_BYTES?GO:WAIT;
    }
    /** The loudest frame above the room's level that note() or live() counted last, and when it came (0 when none). */
    public synchronized int loudest(){return loudest;}
    public synchronized long loudestAt(){return loudestAt;}
    /** Counts the held frames in order; true when VOICE_FRAMES in a row are above the room's level. Leaves run and stillBytes at the end. */
    private boolean scan(){
        boolean voice=false;run=0;stillBytes=0;
        for(long[] mark:marks){
            if(mark[1]==1&&!(mark[0]>=ownFrom&&mark[0]<=ownTo)){
                run++;stillBytes=0;
                if(mark[2]>loudest){loudest=(int)mark[2];loudestAt=mark[0];}
                if(run>=VOICE_FRAMES)voice=true;
            } else {run=0;if(mark[1]==1)stillBytes=0;else stillBytes+=(int)mark[3];}
        }
        return voice;
    }
    private boolean aboveRoom(int level){
        double raised=gain==null?1:Math.max(1,gain.getAsDouble());
        return level>=160||level>=ROOM_RMS*raised;
    }
    /**
     * A connection takes this pre-roll over (the retry after a refused first connection): while it holds no words, only the
     * last LEAD_BYTES of room sound stay, so its bounds count from about now, as for a connection's own pre-roll; words held
     * are all kept, with the bounds of their first word.
     */
    public synchronized void keepLead(){
        if(speech||overflow)return;
        // A soft voice in the room sound that goes is remembered for the first-turn note.
        if(bytes>LEAD_BYTES){loudest=0;loudestAt=0;if(scan()){voiceBefore=true;beforeLoudest=loudest;beforeAt=loudestAt;}}
        while(bytes>LEAD_BYTES&&!pending.isEmpty()){bytes-=pending.remove(0).length;marks.remove(0);}
    }
    public synchronized void clear(){reset();lost=false;voiceBefore=false;}
    private void reset(){pending.clear();marks.clear();bytes=0;quietFrames=0;firstAt=0;speech=false;overflow=false;run=0;stillBytes=0;}
    /** Whether a frame is below the speech energy this pre-roll counts as speech (RMS 160). */
    public static boolean quiet(byte[] pcm) { return rms(pcm)<160; }
    /** A frame's level: the root mean square of its 16-bit samples. */
    public static int rms(byte[] pcm) {
        long energy=0;int count=pcm.length/2;
        for(int n=0;n+1<pcm.length;n+=2){int v=(short)((pcm[n]&255)|(pcm[n+1]<<8));energy+=(long)v*v;}
        return count==0?0:(int)Math.sqrt((double)energy/count);
    }
}
