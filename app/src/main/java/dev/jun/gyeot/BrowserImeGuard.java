package dev.jun.gyeot;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.mozilla.geckoview.GeckoSession;

/** Suppress only software-keyboard presentation caused by automated web input.
 * Keeps Gecko focus, InputConnection, composing text, hardware input and every non-show callback intact.
 * One guard belongs to a live tab/session, never an Activity or the app's native text fields.
 */
final class BrowserImeGuard implements GeckoSession.TextInputDelegate {
    private final GeckoSession session;
    private final GeckoSession.TextInputDelegate delegate;
    private final BooleanSupplier live, automated, ownsInput;
    private final Runnable takeover;
    private final LegacyAutomationState legacyState;
    private boolean gesture, pendingShow;
    private long gestureEpoch;

    BrowserImeGuard(GeckoSession session, GeckoSession.TextInputDelegate delegate,
            BooleanSupplier live, BooleanSupplier automated, Runnable takeover) {
        this(session,delegate,live,automated,takeover,()->true);
    }
    BrowserImeGuard(GeckoSession session, GeckoSession.TextInputDelegate delegate,
            BooleanSupplier live, BooleanSupplier automated, Runnable takeover, BooleanSupplier ownsInput) {
        this(session,delegate,live,automated,takeover,ownsInput,null);
    }
    private BrowserImeGuard(GeckoSession session, GeckoSession.TextInputDelegate delegate,
            BooleanSupplier live, BooleanSupplier automated, Runnable takeover, BooleanSupplier ownsInput,
            LegacyAutomationState legacyState) {
        this.session=session; this.delegate=delegate; this.live=live; this.automated=automated; this.takeover=takeover;this.ownsInput=ownsInput;
        this.legacyState=legacyState;
    }

    /** Temporary adapter for a single global engine lease; not per-tab action attribution. */
    static final class LegacyAutomationState {
        private boolean hold, hasHumanLease;
        private long humanLease;
        void observeRunning(boolean running,long lease) {
            if(running&&(!hasHumanLease||humanLease!=lease))hold=true;
        }
        boolean automated(){return hold;}
        void userTakeover(long lease){hold=false;humanLease=lease;hasHumanLease=true;}
    }
    static BrowserImeGuard legacy(GeckoSession session, GeckoSession.TextInputDelegate delegate,
            BooleanSupplier live, BooleanSupplier running, LongSupplier lease, Runnable takeover,
            BooleanSupplier ownsInput) {
        LegacyAutomationState state=new LegacyAutomationState();
        return new BrowserImeGuard(session,delegate,live,
            ()->{state.observeRunning(running.getAsBoolean(),lease.getAsLong());return state.automated();},
            ()->{takeover.run();state.userTakeover(lease.getAsLong());},ownsInput,state);
    }
    static BrowserImeGuard installLegacy(GeckoSession session, BooleanSupplier live,
            BooleanSupplier running, LongSupplier lease, Runnable takeover) {
        GeckoSession.TextInputDelegate original=session.getTextInput().getDelegate();
        if(original instanceof BrowserImeGuard guard)return guard;
        BrowserImeGuard guard=legacy(session,original,live,running,lease,takeover,
            () -> session.getTextInput().getView()!=null&&session.getTextInput().getView().hasFocus());
        session.getTextInput().setDelegate(guard);return guard;
    }
    void observeRunning(boolean running,long lease){
        if(legacyState!=null)legacyState.observeRunning(running,lease);
    }
    static BrowserImeGuard install(GeckoSession session, BooleanSupplier live, BooleanSupplier automated, Runnable takeover) {
        GeckoSession.TextInputDelegate original=session.getTextInput().getDelegate();
        if(original instanceof BrowserImeGuard guard)return guard;
        BrowserImeGuard guard=new BrowserImeGuard(session,original,live,automated,takeover,
            () -> session.getTextInput().getView()!=null&&session.getTextInput().getView().hasFocus());
        session.getTextInput().setDelegate(guard);return guard;
    }
    static void cancelGesture(GeckoSession session){
        if(session!=null&&session.getTextInput().getDelegate() instanceof BrowserImeGuard guard)guard.cancelGesture();
    }
    long beginGesture(){gesture=live.getAsBoolean();pendingShow=false;return ++gestureEpoch;}
    boolean gesture(long epoch){
        if(!live.getAsBoolean()){cancelGesture();return false;}
        return gesture&&gestureEpoch==epoch;
    }
    void cancelGesture(){gesture=false;pendingShow=false;gestureEpoch++;}
    private void cancelGesture(long epoch){if(gestureEpoch==epoch)cancelGesture();}
    static void finishGesture(BrowserImeGuard started,long epoch,BrowserImeGuard current) {
        if(started!=null&&started==current&&started.gesture(epoch))started.humanInput();
        else if(started!=null)started.cancelGesture(epoch);
    }
    static void humanInputIfCurrent(BrowserImeGuard expected,BrowserImeGuard current) {
        if(expected!=null&&expected==current)expected.humanInput();
    }
    boolean liveInput(){return live.getAsBoolean();}
    void humanInput() {
        if(!live.getAsBoolean()){cancelGesture();return;}
        boolean requested=gesture&&pendingShow;
        cancelGesture();takeover.run();
        // A tap's native show request can precede ACTION_UP. Defer, rather than discard it.
        if(requested&&live.getAsBoolean()&&!automated.getAsBoolean()&&ownsInput.getAsBoolean())delegate.showSoftInput(session);
    }
    @Override public void showSoftInput(GeckoSession source){
        if(source!=session)return;
        // Observe a running legacy lease even when this session is currently backgrounded.
        boolean blocked=automated.getAsBoolean();
        if(!live.getAsBoolean()){cancelGesture();return;}
        if(blocked){if(gesture)pendingShow=true;return;}
        if(ownsInput.getAsBoolean())delegate.showSoftInput(source);
    }
    @Override public void hideSoftInput(GeckoSession source){
        if(source!=session)return;
        pendingShow=false;
        if(!live.getAsBoolean()){cancelGesture();return;}
        // Gecko's default hide uses the shared window token. Never close a native address/chat editor's IME.
        if(ownsInput.getAsBoolean())delegate.hideSoftInput(source);
    }
    @Override public void restartInput(GeckoSession source,int reason){delegate.restartInput(source,reason);}
    @Override public void updateSelection(GeckoSession source,int start,int end,int composingStart,int composingEnd){delegate.updateSelection(source,start,end,composingStart,composingEnd);}
    @Override public void updateExtractedText(GeckoSession source,ExtractedTextRequest request,ExtractedText text){delegate.updateExtractedText(source,request,text);}
    @Override public void updateCursorAnchorInfo(GeckoSession source,CursorAnchorInfo info){delegate.updateCursorAnchorInfo(source,info);}

    /** Gecko exposes webpage accessibility nodes through a virtual-node provider, not the root View. */
    static AccessibilityNodeProvider accessibility(AccessibilityNodeProvider original,Runnable humanInput) {
        return accessibility(original,()->true,humanInput);
    }
    static AccessibilityNodeProvider accessibility(AccessibilityNodeProvider original,BooleanSupplier current,Runnable humanInput) {
        if(original==null)return null;
        return new AccessibilityNodeProvider(){
            @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id){return original.createAccessibilityNodeInfo(id);}
            @Override public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(String text,int id){return original.findAccessibilityNodeInfosByText(text,id);}
            @Override public AccessibilityNodeInfo findFocus(int focus){return original.findFocus(focus);}
            @Override public void addExtraDataToAccessibilityNodeInfo(int id,AccessibilityNodeInfo info,String key,Bundle args){original.addExtraDataToAccessibilityNodeInfo(id,info,key,args);}
            @Override public boolean performAction(int id,int action,Bundle args){
                // A retained provider from a previously visible tab may not act on the replacement tab.
                if(!current.getAsBoolean())return false;
                if(action==AccessibilityNodeInfo.ACTION_CLICK||action==AccessibilityNodeInfo.ACTION_LONG_CLICK
                        ||action==AccessibilityNodeInfo.ACTION_FOCUS||action==AccessibilityNodeInfo.ACTION_SET_TEXT
                        ||action==AccessibilityNodeInfo.ACTION_PASTE||action==AccessibilityNodeInfo.ACTION_CUT)humanInput.run();
                return original.performAction(id,action,args);
            }
        };
    }
}
