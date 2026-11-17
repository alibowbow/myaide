package dev.jun.gyeot;

import dev.jun.gyeot.core.*;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Narrow host command: no accessibility click, file deletion, credential access or remote log upload. The scope is the
 * user's own explicit request (PhoneLogActions) and the clear is proven by its local receipt. */
final class PhoneLogCommand {
    private PhoneLogCommand() {}
    static JSONObject run(AppStore store,PhoneLogActions.Request request,Runnable authorization) throws Exception {
        authorization.run();
        PhoneLogBook.Receipt existing=store.phoneLogs.receipt(request.id);
        if(existing!=null)return result(store,existing,true);
        authorization.run();
        PhoneLogBook.Receipt receipt=store.phoneLogs.clear(request.id,request.snapshot,android.os.SystemClock.elapsedRealtime());
        store.phoneLog("  · phone_log · 요청한 최근 기록만 정리 · 5분 안에 되돌릴 수 있어요");
        return result(store,receipt,false);
    }
    private static JSONObject result(AppStore store,PhoneLogBook.Receipt receipt,boolean replay) {
        boolean verified=store.phoneLogs.verified(receipt);
        long recoverable=store.phoneLogs.undoRemaining(receipt,android.os.SystemClock.elapsedRealtime());
        return obj("ok",verified,"verified",verified,"action_executed",!replay,"replayed",replay,"retryable",false,
            "operation",PhoneLogActions.FUNCTION,"receipt",receipt.operation,"snapshot",receipt.snapshot,"cleared_count",receipt.cleared,
            "recoverable_for_ms",recoverable,
            "answer",verified?"요청한 최근 폰 조작 기록 "+receipt.cleared+"개를 정리했어요. 이후 새 기록은 남겨뒀어요." + (recoverable>0?" 설정에서 남은 복구 시간 안에 되돌릴 수 있어요.":""):"이전 정리 기록이 복구되었거나 바뀌었어요. 다시 지우지 않았어요.");
    }
}
