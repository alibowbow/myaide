package dev.jun.gyeot.core;

import java.util.UUID;
/** Exact scoped authorization from user speech, never screen text or model tool arguments. */
public final class PhoneLogActions {
    public static final String FUNCTION="clear_recent_phone_log";
    private static final long CONTEXT_MS=120_000;
    public static final class Request {
        public final String id, goal;
        public final PhoneLogBook.Snapshot snapshot;
        public final long epoch, account;
        private Request(String id,String goal,PhoneLogBook.Snapshot snapshot,long epoch,long account){this.id=id;this.goal=goal;this.snapshot=snapshot;this.epoch=epoch;this.account=account;}
    }
    private Request pending, scopedRequest;
    private long epoch, contextUntil, scopedAccount;
    private boolean scoped;
    public synchronized Request heard(String speech,PhoneLogBook book,long account,long now) {
        String text=normalize(speech); epoch++;pending=null;
        if(scoped&&scopedAccount!=account){scoped=false;scopedRequest=null;}
        if(text.isBlank()||text.matches(".*(취소|하지마|하지말|지우지|삭제하지|안돼|안되|cancel|don.?t).*")){scoped=false;scopedRequest=null;return null;}
        if(text.matches(".*(어디|방법|어떻게|왜|지우면|삭제하면|지울까|지워도|지우는|삭제하는|삭제된|지워진|how|where|whatif).*")){scoped=false;scopedRequest=null;return null;}
        if(text.matches(".*(사진|파일|대화|메모|기억|계정|캐시|통화기록|채팅|photo|file|chat).*")){scoped=false;scopedRequest=null;return null;}
        boolean exact=text.matches(".*(폰조작기록|폰조작로그|phonecontrollog|phoneoperationlog).*");
        boolean clear=text.matches(".*(지워|지우자|삭제해|비워|clear|delete|remove).*")
            || text.matches(".*(?:기록|로그)(?:을|를)?(?:삭제|지우기)")
            || text.matches(".*(?:지우기|삭제)버튼(?:을)?(?:눌러|클릭).*");
        boolean follow=text.matches("(?:응|네|그래|맞아|그거|그것|화면에표시된것|화면에있는것)?(?:지워줘|지워|삭제해줘|비워줘|clearit|deleteit)[.!?]*");
        boolean clarification=text.matches("(?:그거|그것|화면에표시된것|화면에있는것|화면표시된것)");
        boolean same=exact || scoped&&now<=contextUntil&&(follow||clarification);
        if(exact){scoped=true;scopedAccount=account;contextUntil=now+CONTEXT_MS;}
        else if(!same){
            // Viewing pauses execution, but a subsequent explicit clear may retain the user-named scope.
            boolean viewing=scoped&&now<=contextUntil&&text.contains("화면")&&text.matches(".*(봐|보여|보기|보자|읽어|확인).*")&&!clear;
            if(!viewing){scoped=false;scopedRequest=null;}
        }
        if(same&&(clear||clarification&&scopedRequest!=null)){
            boolean continueRequest=!exact&&scopedRequest!=null&&scopedRequest.account==account;
            pending=new Request(continueRequest?scopedRequest.id:UUID.randomUUID().toString(),"들퍼레의 최근 폰 조작 기록만 정리",
                continueRequest?scopedRequest.snapshot:book.snapshot(),epoch,account);
            scopedRequest=pending;return pending;
        }
        if(exact)scopedRequest=null;
        return null;
    }
    public synchronized Request current(){return pending;}
    public synchronized void check(Request request,long account) {
        if(request==null||pending!=request||request.epoch!=epoch||request.account!=account)throw new IllegalStateException("새 지시 또는 연결 변경으로 이전 기록 정리를 취소했어요.");
    }
    public synchronized void interrupt(){epoch++;pending=null;}
    public synchronized void cancel(){epoch++;pending=null;scoped=false;scopedRequest=null;}
    private static String normalize(String text){return text==null?"":text.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\p{Punct}]","");}
}
