package dev.jun.gyeot;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import dev.jun.gyeot.core.*;
import org.json.*;
import java.util.*;
import java.util.regex.Pattern;
import static dev.jun.gyeot.core.Jsons.*;

/** One durable local conversation. All reply writes are conditional on immutable turn identities. */
final class MainChatStore implements AutoCloseable {
    record Turn(long user,long reply,String run,long generation,JSONObject selection,String fingerprint) {}
    /** Oldest originals after the current summary, to be folded into it; sources are the IDs a valid summary may cite. */
    record Summary(long generation,long through,String previous,JSONArray messages,Set<Long> sources) {}
    /**
     * What one request may carry: extended memory, the stored summary, a character budget, long-term notes, the send time for the
     * question's clock line (0: none), the calendar block a schedule question carries after it (MainChatCalendar.context),
     * and the transcript of a video the question is about (YouTubeTranscriptClient), sent with the attached files' text.
     */
    record Options(boolean memory,boolean summary,int budget,String longTerm,long now,String calendar,String video) {
        Options(boolean memory,boolean summary,int budget,String longTerm,long now,String calendar){this(memory,summary,budget,longTerm,now,calendar,"");}
        Options(boolean memory,boolean summary,int budget,String longTerm,long now){this(memory,summary,budget,longTerm,now,"","");}
        static Options of(boolean memory,int budget){return new Options(memory,memory,budget,"",0);}
    }
    /** Conversation since the last automatic memory pass. */
    record MemoryBatch(long generation,long through,String transcript) {}
    /** The newest originals always stay verbatim; compaction never folds them into the summary. */
    static final int KEEP_VERBATIM=24;
    /** Wording that asks to keep or drop something: a memory pass runs after that reply instead of waiting for more turns. */
    private static final Pattern MEMORY_CUE=Pattern.compile("(?i)(기억\\s*해|기억하|외워|잊어|잊지\\s*마|라고\\s*불러|부르지\\s*마|remember|forget)");
    /** A reply the app closed on; what had streamed in stays in its partial column. */
    static final String INTERRUPTED="앱이 종료되어 답변이 중단됐어요. 자동 재전송하지 않았어요.";
    /** Rows recent history carries: finished messages, and answers the app closed on with what had streamed in. */
    private static final String SHOWN="(state='done' OR (state='interrupted' AND partial<>''))";
    private SQLiteDatabase db; private String failure="";
    MainChatStore(Context context) {
        try {
            java.io.File path=context.getDatabasePath("main-conversation.db"); path.getParentFile().mkdirs();
            db=SQLiteDatabase.openDatabase(path.getPath(),null,SQLiteDatabase.CREATE_IF_NECESSARY, broken->{ throw new SQLiteException("메인 대화 저장소 손상 · 원본 보존"); });
            db.enableWriteAheadLogging(); db.execSQL("PRAGMA foreign_keys=ON");
            db.execSQL("CREATE TABLE IF NOT EXISTS meta (id INTEGER PRIMARY KEY, generation INTEGER NOT NULL, summary TEXT NOT NULL DEFAULT '', through_id INTEGER NOT NULL DEFAULT 0, fingerprint TEXT NOT NULL DEFAULT '')");
            if(!columns("meta").contains("memory_through"))db.execSQL("ALTER TABLE meta ADD COLUMN memory_through INTEGER NOT NULL DEFAULT 0");
            // A random id per conversation; only its hash leaves the phone, as the replies' prompt cache key (cacheKey).
            if(!columns("meta").contains("conversation"))db.execSQL("ALTER TABLE meta ADD COLUMN conversation TEXT NOT NULL DEFAULT ''");
            db.execSQL("INSERT OR IGNORE INTO meta(id,generation) VALUES(1,1)");
            db.execSQL("CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY AUTOINCREMENT,role TEXT NOT NULL,text TEXT NOT NULL,state TEXT NOT NULL,run TEXT NOT NULL DEFAULT '',reply_to INTEGER NOT NULL DEFAULT 0,job TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL,engine TEXT NOT NULL DEFAULT '',model TEXT NOT NULL DEFAULT '',omit INTEGER NOT NULL DEFAULT 0)");
            // Added with streaming: the part of a reply that has streamed in (text keeps the state line until it ends), and
            // the provider's summary of its thinking beside a finished answer. Older databases gain them, empty.
            Set<String> added=columns("messages");
            if(!added.contains("partial"))db.execSQL("ALTER TABLE messages ADD COLUMN partial TEXT NOT NULL DEFAULT ''");
            if(!added.contains("thinking"))db.execSQL("ALTER TABLE messages ADD COLUMN thinking TEXT NOT NULL DEFAULT ''");
            // What a finished answer took, for its foot: first-character time and token counts (core/Usage), never content.
            if(!added.contains("usage"))db.execSQL("ALTER TABLE messages ADD COLUMN usage TEXT NOT NULL DEFAULT ''");
            // Added with web search: what came from the web with a finished answer (core/MainSearch.record), never part of its text.
            if(!added.contains("web"))db.execSQL("ALTER TABLE messages ADD COLUMN web TEXT NOT NULL DEFAULT ''");
            // Added with attachments: the files sent with a user message (core/MainAttachments.clean: names, kinds, a text file's
            // text; never any bytes, which stay in AppStore.mainAttachmentFile). Empty for every other message.
            if(!added.contains("files"))db.execSQL("ALTER TABLE messages ADD COLUMN files TEXT NOT NULL DEFAULT ''");
            db.execSQL("CREATE INDEX IF NOT EXISTS msg_state ON messages(state)");
            db.execSQL("CREATE INDEX IF NOT EXISTS msg_reply ON messages(reply_to)");
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS message_search USING fts4(text,tokenize=unicode61)");
            db.execSQL("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,text TEXT NOT NULL,source INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY,source INTEGER NOT NULL,request TEXT NOT NULL,selection TEXT NOT NULL,fingerprint TEXT NOT NULL,state TEXT NOT NULL,room TEXT NOT NULL DEFAULT '',run TEXT NOT NULL DEFAULT '',receipt INTEGER NOT NULL DEFAULT 0,hidden INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS unresolved_jobs ON jobs(receipt)");
            db.beginTransaction();try {
                // What had streamed in stays in partial, shown with 이어서 답하기; nothing is sent again on its own.
                JSONArray pending=query("SELECT id FROM messages WHERE state IN ('queued','running')",null);
                for(int i=0;i<pending.length();i++){long id=pending.optJSONObject(i).optLong("id");db.execSQL("UPDATE messages SET state='interrupted',text=? WHERE id=?",new Object[]{INTERRUPTED,id});index(id,INTERRUPTED);}
                // A picture still being drawn when the app closed is not drawn again on its own: its card says so (다시 그리기 asks again).
                JSONArray drawing=query("SELECT id,text FROM (SELECT id,text FROM messages WHERE role='assistant' AND state='done' ORDER BY id DESC LIMIT 100) WHERE text LIKE '%\"pending\"%'",null);
                for(int i=0;i<drawing.length();i++){JSONObject row=drawing.optJSONObject(i);String text=row.optString("text"),fixed=ChatImages.stopped(text,"앱이 종료되어 그림을 그리지 못했어요. 자동으로 다시 그리지 않았어요.");if(!fixed.equals(text))updateText(row.optLong("id"),"done",fixed);}
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            pinnedChanged();
        } catch(Exception e) { failure="메인 대화 저장소를 열지 못했어요. 원본은 보존했어요. 저장 공간을 확인해 주세요.";if(db!=null)try{db.close();}catch(Exception ignored){}db=null; }
    }
    private Set<String> columns(String table){Set<String> names=new HashSet<>();try(Cursor c=db.rawQuery("PRAGMA table_info("+table+")",null)){int name=c.getColumnIndex("name");while(c.moveToNext())names.add(c.getString(name));}return names;}
    synchronized boolean available(){return db!=null&&db.isOpen();} String failure(){return failure;}
    private void require(){if(!available())throw new IllegalStateException(failure);}
    /** The attached files' text, then the video transcript read for this question (YouTubeTranscriptClient): this request only. */
    private static String attachedWith(String files,String video){return video==null||video.isBlank()?files:files==null||files.isBlank()?video:files+"\n\n"+video;}
    synchronized long generation(){require();try(Cursor c=db.rawQuery("SELECT generation FROM meta WHERE id=1",null)){c.moveToFirst();return c.getLong(0);}}
    synchronized int pendingCount(){require();try(Cursor c=db.rawQuery("SELECT count(*) FROM messages WHERE state IN ('queued','running')",null)){c.moveToFirst();return c.getInt(0);}}
    private long count(String sql,String... args){try(Cursor c=db.rawQuery(sql,args)){c.moveToFirst();return c.getLong(0);}}
    private JSONObject meta(){return query("SELECT * FROM meta WHERE id=1",null).optJSONObject(0);}
    private JSONArray query(String sql,String[] args){require();JSONArray out=arr();try(Cursor c=db.rawQuery(sql,args)){while(c.moveToNext()){JSONObject row=obj();for(int i=0;i<c.getColumnCount();i++)put(row,c.getColumnName(i),c.getType(i)==Cursor.FIELD_TYPE_INTEGER?c.getLong(i):c.getString(i));if(row.has("created"))put(row,"at",row.optLong("created"));out.put(row);}}return out;}
    private long insertMessage(String role,String text,String state,String run,long reply,String job,String engine,String model){ContentValues v=new ContentValues();v.put("role",role);v.put("text",text);v.put("state",state);v.put("run",run);v.put("reply_to",reply);v.put("job",job);v.put("created",System.currentTimeMillis());v.put("engine",engine);v.put("model",model);long id=db.insertOrThrow("messages",null,v);index(id,text);return id;}
    private void index(long id,String text){db.execSQL("DELETE FROM message_search WHERE docid=?",new Object[]{id});db.execSQL("INSERT INTO message_search(docid,text) VALUES(?,?)",new Object[]{id,text});}
    /** A reply's new state and text; whatever had streamed into partial goes, so nothing from before stays drawn or saved. */
    private void updateText(long id,String state,String text){db.execSQL("UPDATE messages SET state=?,text=?,partial='' WHERE id=?",new Object[]{state,text,id});index(id,text);}
    synchronized Turn enqueue(String text,JSONObject selection,String fingerprint){return enqueue(text,null,selection,fingerprint);}
    /**
     * As enqueue, with the files attached to the message (MainAttachments.clean keeps what is stored). A message with files
     * may have no words: it asks with MainAttachments.ASK.
     */
    synchronized Turn enqueue(String text,JSONArray files,JSONObject selection,String fingerprint){require();JSONArray attached=MainAttachments.clean(files);String clean=text==null?"":text.strip();if(clean.isEmpty()&&attached.length()>0)clean=MainAttachments.ASK;if(clean.isEmpty()||clean.length()>MainChatContext.INPUT)throw new IllegalArgumentException("메시지는 1~12,000자로 적어 주세요.");db.beginTransaction();try{if(pendingCount()>=8)throw new IllegalStateException("답변 8개가 대기 중이에요. 잠시 기다리거나 취소해 주세요.");String run=UUID.randomUUID().toString(),engine=selection.optString("engine");String model=selection.optJSONObject("models").optString(engine);long user=insertMessage("user",clean,"done","",0,"",engine,model);if(attached.length()>0)db.execSQL("UPDATE messages SET files=? WHERE id=?",new Object[]{attached.toString(),user});long reply=insertMessage("assistant","답변 대기 중","queued",run,user,"",engine,model);Turn turn=new Turn(user,reply,run,generation(),ScopedModels.copy(selection),fingerprint);db.setTransactionSuccessful();return turn;}finally{db.endTransaction();}}
    synchronized boolean start(Turn t){require();if(t.generation()!=generation())return false;ContentValues v=new ContentValues();v.put("state","running");v.put("text","답변을 준비하고 있어요");boolean yes=db.update("messages",v,"id=? AND run=? AND state='queued'",new String[]{""+t.reply(),t.run()})==1;if(yes)index(t.reply(),v.getAsString("text"));return yes;}
    synchronized boolean live(Turn t){JSONObject m=message(t.reply());return t.generation()==generation()&&m!=null&&t.run().equals(m.optString("run"))&&"running".equals(m.optString("state"));}
    /**
     * What has streamed in so far of a reply still being written, in its own column (text keeps its state line): shown
     * after a restart with 이어서 답하기, cleared when the reply ends. Only the live run's own row, while it runs.
     */
    synchronized boolean partial(Turn t,String text){require();if(t.generation()!=generation())return false;ContentValues v=new ContentValues();v.put("partial",MainChatContext.clip(text,MainChatContext.OUTPUT));return db.update("messages",v,"id=? AND run=? AND state='running'",new String[]{""+t.reply(),t.run()})==1;}
    synchronized boolean complete(Turn t,String text){return complete(t,text,"",null,"");}
    /** As complete; thinking is the provider's summary of its thinking, kept beside the answer and never part of its text. */
    synchronized boolean complete(Turn t,String text,String thinking){return complete(t,text,thinking,null,"");}
    /** As complete; usage (Usage.json) is what the answer took, shown in its foot. */
    synchronized boolean complete(Turn t,String text,JSONObject usage){return complete(t,text,"",usage,"");}
    /** As complete, with the thinking summary beside the answer and what the answer took (Usage.json) for its foot. */
    synchronized boolean complete(Turn t,String text,String thinking,JSONObject usage){return complete(t,text,thinking,usage,"");}
    /**
     * As complete; web is what came from the web with the answer (core/MainSearch.record: the pages for its source strip,
     * Google's search suggestions, whether it came without search), kept beside it: never in its text, so never sent back
     * to the model, read aloud, searched, copied or shared.
     */
    synchronized boolean complete(Turn t,String text,String thinking,String web){return complete(t,text,thinking,null,web);}
    /** As complete, with the thinking summary, what the answer took (Usage.json, null for nothing) and what came from the web, each beside the answer. */
    synchronized boolean complete(Turn t,String text,String thinking,JSONObject usage,String web){require();db.beginTransaction();try{if(!finish(t,"done",MainChatContext.clip(text,MainChatContext.OUTPUT),MainChatContext.clip(thinking==null?"":thinking,MainRequest.THINKING),web==null?"":web))return false;if(usage!=null&&usage.length()>0)db.execSQL("UPDATE messages SET usage=? WHERE id=?",new Object[]{usage.toString(),t.reply()});db.setTransactionSuccessful();return true;}finally{db.endTransaction();}}
    /**
     * The replies' prompt cache key: "gyeot-main-" and 16 hex characters of a hash of this conversation's random id, the same
     * for every reply until the conversation is cleared. No content goes into it.
     */
    synchronized String cacheKey(){require();String id=meta().optString("conversation");if(id.isBlank()){id=UUID.randomUUID().toString();db.execSQL("UPDATE meta SET conversation=? WHERE id=1",new Object[]{id});}
        try{byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(("main-chat\n"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder("gyeot-main-");for(int i=0;i<8;i++)hex.append(String.format(Locale.ROOT,"%02x",digest[i]&255));return hex.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    synchronized boolean fail(Turn t,String text){return finish(t,"error",MainChatContext.clip(text,2000),"","");}
    private boolean finish(Turn t,String state,String text,String thinking,String web){require();db.beginTransaction();try{if(!live(t))return false;updateText(t.reply(),state,text);db.execSQL("UPDATE messages SET thinking=?,web=? WHERE id=?",new Object[]{thinking,web,t.reply()});db.setTransactionSuccessful();return true;}finally{db.endTransaction();}}
    /**
     * Puts record in the index-th picture card of a saved answer (its run checked): only while that card still waits for
     * the drawing token (null: whatever it shows now) and, unless generation is -1, only in that generation of the
     * conversation, so a picture that arrives after an edit, a deletion or a memory or model change is not put in.
     */
    synchronized boolean picture(long reply,String run,int index,String token,JSONObject record,long generation){
        require();db.beginTransaction();try{
            if(generation!=-1&&generation!=generation())return false;
            JSONObject m=message(reply);if(m==null||!"done".equals(m.optString("state"))||!m.optString("run").equals(run))return false;
            String text=m.optString("text");List<JSONObject> cards=ChatImages.pictures(text);if(index<0||index>=cards.size())return false;
            if(token!=null&&(cards.get(index)==null||!token.equals(cards.get(index).optString("pending"))))return false;
            updateText(reply,"done",MainChatContext.clip(ChatImages.replace(text,index,record),MainChatContext.OUTPUT));db.setTransactionSuccessful();return true;
        }finally{db.endTransaction();}
    }
    /**
     * Puts record in the index-th calendar card of a saved answer (CalendarCards), only while that card is still the one with
     * token and in state from: a tap's outcome lands on its own card once, and a second tap finds it moved on.
     */
    synchronized boolean calendarCard(long reply,int index,String token,String from,JSONObject record){
        require();db.beginTransaction();try{
            JSONObject m=message(reply);if(m==null||!"done".equals(m.optString("state")))return false;
            String text=m.optString("text");List<JSONObject> cards=CalendarCards.cards(text);if(index<0||index>=cards.size()||cards.get(index)==null)return false;
            JSONObject card=cards.get(index);if(!token.equals(card.optString("card"))||!from.equals(card.optString("state"))||!token.equals(record.optString("card")))return false;
            updateText(reply,"done",MainChatContext.clip(CalendarCards.replace(text,index,record),MainChatContext.OUTPUT));db.setTransactionSuccessful();return true;
        }finally{db.endTransaction();}
    }
    synchronized void cancel(long reply){require();JSONObject m=message(reply);if(m!=null&&Set.of("queued","running").contains(m.optString("state")))updateText(reply,"cancelled","답변을 취소했어요. 이미 전송된 요청의 원격 종료는 확인되지 않았어요.");}
    /** The ids of every file a message still keeps (AppStore.cleanMainAttachments deletes the rest). */
    synchronized Set<String> fileIds(){require();Set<String> ids=new HashSet<>();JSONArray rows=query("SELECT files FROM messages WHERE files<>''",null);for(int i=0;i<rows.length();i++){JSONArray files=MainAttachments.of(rows.optJSONObject(i));for(int j=0;j<files.length();j++)ids.add(files.optJSONObject(j).optString("id"));}return ids;}
    synchronized JSONObject message(long id){JSONArray a=query("SELECT * FROM messages WHERE id=?",new String[]{""+id});return a.optJSONObject(0);}
    /** The newest answer of the conversation, whatever its state (0: none): 이어서 답하기 carries on only from it. */
    synchronized long latestReply(){require();try(Cursor c=db.rawQuery("SELECT id FROM messages WHERE role='assistant' ORDER BY id DESC LIMIT 1",null)){return c.moveToFirst()?c.getLong(0):0;}}
    synchronized JSONArray page(long before,int limit){return screen(query("SELECT * FROM (SELECT * FROM messages WHERE (CAST(? AS INTEGER)=0 OR id<?) ORDER BY id DESC LIMIT ?) ORDER BY id",new String[]{""+before,""+before,""+Math.max(1,Math.min(40,limit))}));}
    synchronized JSONArray search(String text,long before,int limit){String q=MainChatContext.query(text);if(q.isBlank())return arr();return screen(query("SELECT * FROM (SELECT m.* FROM messages m JOIN message_search f ON f.docid=m.id WHERE message_search MATCH ? AND (CAST(? AS INTEGER)=0 OR m.id<?) ORDER BY m.id DESC LIMIT ?) ORDER BY id",new String[]{q,""+before,""+before,""+Math.max(1,Math.min(40,limit))}));}
    /** Rows for the screen: a reply still being written is drawn from the engine's live text, so its saves never redraw the page. */
    private static JSONArray screen(JSONArray rows){for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&Set.of("queued","running").contains(row.optString("state")))row.remove("partial");}return rows;}
    private void interruptPending(String text){JSONArray pending=query("SELECT * FROM messages WHERE state IN ('queued','running')",null);for(int i=0;i<pending.length();i++)updateText(pending.optJSONObject(i).optLong("id"),"interrupted",text);}
    synchronized void invalidateContext(){require();db.beginTransaction();try{db.execSQL("UPDATE meta SET generation=generation+1,summary='',through_id=0,fingerprint='' WHERE id=1");interruptPending("대화나 기억이 바뀌어 이전 답변을 중단했어요. 새 메시지로 다시 요청해 주세요.");db.setTransactionSuccessful();}finally{db.endTransaction();}}
    /** A model or connection change stops pending replies but keeps the summary: it describes the conversation, not a model. */
    synchronized void interrupt(){require();db.beginTransaction();try{db.execSQL("UPDATE meta SET generation=generation+1 WHERE id=1");interruptPending("메인 모델이나 연결이 바뀌어 이전 답변을 중단했어요. 새 메시지로 다시 요청해 주세요.");db.setTransactionSuccessful();}finally{db.endTransaction();}}
    synchronized void edit(long id,String text){require();JSONObject old=message(id);if(old==null||!"user".equals(old.optString("role")))throw new IllegalArgumentException("사용자 메시지만 수정할 수 있어요.");if(text==null||text.isBlank()||text.length()>MainChatContext.INPUT)throw new IllegalArgumentException("1~12,000자로 적어 주세요.");db.beginTransaction();try{invalidateContext();updateText(id,"done",text.strip());db.execSQL("UPDATE messages SET omit=0 WHERE id=?",new Object[]{id});db.execSQL("UPDATE messages SET omit=1 WHERE reply_to=?",new Object[]{id});db.execSQL("DELETE FROM notes WHERE source=?",new Object[]{id});db.execSQL("UPDATE meta SET memory_through=MIN(memory_through,?) WHERE id=1",new Object[]{id-1});scrubJobs(id);db.setTransactionSuccessful();}finally{db.endTransaction();pinnedChanged();}}
    private void scrubJobs(long source){String where=source==0?"1=1":"source="+source;db.execSQL("UPDATE jobs SET hidden=1,request='',selection='{}',state=CASE WHEN state='queued' THEN 'cancelled' ELSE state END,receipt=CASE WHEN state='queued' THEN 1 ELSE receipt END WHERE "+where);}
    synchronized void delete(long id){require();db.beginTransaction();try{JSONObject target=message(id);
        // An app notice was never conversation context: removing it must not reset the summary or stop replies.
        if(target!=null&&"memory".equals(target.optString("role"))){db.execSQL("DELETE FROM message_search WHERE docid=?",new Object[]{id});db.execSQL("DELETE FROM messages WHERE id=?",new Object[]{id});db.setTransactionSuccessful();return;}
        if(target!=null&&!target.optString("job").isBlank())db.execSQL("UPDATE jobs SET hidden=1,request='',selection='{}',state=CASE WHEN state='queued' THEN 'cancelled' ELSE state END,receipt=CASE WHEN state='queued' THEN 1 ELSE receipt END WHERE id=?",new Object[]{target.optString("job")});invalidateContext();JSONArray rows=query("SELECT * FROM messages WHERE id=? OR reply_to=?",new String[]{""+id,""+id});for(int i=0;i<rows.length();i++)db.execSQL("DELETE FROM message_search WHERE docid=?",new Object[]{rows.optJSONObject(i).optLong("id")});db.execSQL("DELETE FROM messages WHERE id=? OR reply_to=?",new Object[]{id,id});db.execSQL("DELETE FROM notes WHERE source=?",new Object[]{id});scrubJobs(id);db.setTransactionSuccessful();}finally{db.endTransaction();pinnedChanged();}}
    synchronized void clear(){require();db.beginTransaction();try{invalidateContext();db.execSQL("DELETE FROM message_search");db.execSQL("DELETE FROM messages");db.execSQL("DELETE FROM notes");db.execSQL("UPDATE meta SET conversation='' WHERE id=1");scrubJobs(0);db.setTransactionSuccessful();}finally{db.endTransaction();pinnedChanged();}}
    synchronized JSONArray notes(){return query("SELECT * FROM notes ORDER BY id",null);}
    private void validateNote(String kind,String text,long excluding){if(!Set.of("제약","선호","사실").contains(kind)||text==null||text.isBlank()||text.length()>800||Memory.sensitive(text))throw new IllegalArgumentException("기억은 비밀정보 없이 1~800자로 적어 주세요.");JSONArray a=notes();int chars=text.length(),count=0;for(int i=0;i<a.length();i++){JSONObject n=a.optJSONObject(i);if(n.optLong("id")!=excluding){chars+=n.optString("text").length();count++;}}if(count>=32||chars>5000)throw new IllegalArgumentException("기억은 최대 32개·총 5,000자예요. 기존 기억을 정리해 주세요.");}
    synchronized long addNote(String kind,String text,long source){require();validateNote(kind,text,0);if(source!=0&&message(source)==null)throw new IllegalArgumentException("원문을 찾지 못했어요.");db.beginTransaction();try{invalidateContext();ContentValues v=new ContentValues();v.put("kind",kind);v.put("text",text);v.put("source",source);v.put("created",System.currentTimeMillis());long id=db.insertOrThrow("notes",null,v);db.setTransactionSuccessful();return id;}finally{db.endTransaction();pinnedChanged();}}
    private void omitNoteSource(long id){JSONArray rows=query("SELECT * FROM notes WHERE id=?",new String[]{""+id});JSONObject n=rows.optJSONObject(0);if(n!=null&&n.optLong("source")!=0)db.execSQL("UPDATE messages SET omit=1 WHERE id=? OR reply_to=?",new Object[]{n.optLong("source"),n.optLong("source")});}
    synchronized void updateNote(long id,String kind,String text){validateNote(kind,text,id);db.beginTransaction();try{omitNoteSource(id);invalidateContext();db.execSQL("UPDATE notes SET kind=?,text=?,source=0,created=? WHERE id=?",new Object[]{kind,text,System.currentTimeMillis(),id});db.setTransactionSuccessful();}finally{db.endTransaction();pinnedChanged();}}
    synchronized void removeNote(long id){require();db.beginTransaction();try{omitNoteSource(id);invalidateContext();db.execSQL("DELETE FROM notes WHERE id=?",new Object[]{id});db.setTransactionSuccessful();}finally{db.endTransaction();pinnedChanged();}}
    /** The pinned notes as every request carries them; the voice assistant and 작업창 get the same lines (AppStore.pinnedNotes). */
    private String memoryText(){return Memory.pinned(notes());}
    /** memoryText as of the last change to the notes, read without waiting on this store (the main thread starts voice with it). */
    private volatile String pinned="";
    String pinned(){return pinned;}
    /** Every change to the notes ends here, committed or not; a failed read keeps the last text. */
    private void pinnedChanged(){try{pinned=available()?memoryText():"";}catch(RuntimeException e){/* The last good text stays. */}}
    /** An app notice in the timeline (e.g. what automatic memory kept). Shown to the user, never sent as conversation. */
    synchronized long notice(String text){require();db.beginTransaction();try{long id=insertMessage("memory",MainChatContext.clip(text,600),"done","",0,"","","");db.execSQL("UPDATE messages SET omit=1 WHERE id=?",new Object[]{id});db.setTransactionSuccessful();return id;}finally{db.endTransaction();}}
    synchronized String context(Turn turn,boolean memory){return context(turn,Options.of(memory,MainChatContext.MAX));}
    /**
     * The request for one turn: the summary, then every eligible original after it (newest kept when the budget runs
     * out), explicit and long-term memory, ranked older excerpts, task and parallel-reply state, and the question last, closed
     * by the send time. Search and stored text use the question alone.
     */
    synchronized String context(Turn turn,Options o){
        JSONObject current=message(turn.user());if(current==null)throw new IllegalStateException("메시지가 삭제됐어요.");
        JSONObject meta=meta();String summary=o.memory()&&o.summary()?meta.optString("summary"):"";long start=summary.isBlank()?0:meta.optLong("through_id");
        // Attached files: the question names them; a text file's text goes in its own section after it (photos and PDFs go
        // along as the provider's file parts, MainChatEngine).
        JSONArray files=MainAttachments.of(current);
        MainChatContext.Sections fixed=new MainChatContext.Sections(MainAttachments.question(current.optString("text"),files),"",o.memory()?memoryText():"",o.memory()?o.longTerm():"",summary,"",taskState(),pendingReplies(turn),o.now()>0?MainChatContext.now(o.now()):"",o.calendar()==null?"":o.calendar(),attachedWith(MainAttachments.texts(files),o.video()));
        int room=MainChatContext.room(fixed,o.budget()),recentRoom=Math.max(0,(o.memory()?room-Math.min(MainChatContext.RETRIEVED,room/8):Math.min(room,MainChatContext.RECENT_OFF))-200);
        // Newest first so the latest turns always fit; joined oldest first. An answer the app closed on goes along with
        // what had streamed in, marked as cut off, so 이어서 답하기 can carry on from it; one too long for the room left
        // goes as far as it fits, its end kept, rather than leaving the history empty.
        ArrayList<String> lines=new ArrayList<>();int used=0;long oldest=turn.user(),cursor=turn.user();boolean full=false;
        while(!full){
            JSONArray rows=query("SELECT * FROM messages WHERE id<? AND id>? AND omit=0 AND "+SHOWN+" ORDER BY id DESC LIMIT 256",new String[]{""+cursor,""+start});
            if(rows.length()==0)break;
            for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);boolean cut="interrupted".equals(row.optString("state"));String line=MainChatContext.line(cut?MainChatContext.cutOff(row):row);if(cut&&used+line.length()>recentRoom)line=MainChatContext.cutOffLine(row,recentRoom-used);if(line.isEmpty()||used+line.length()>recentRoom||!o.memory()&&lines.size()>=MainChatContext.RECENT_ROWS_OFF){full=true;break;}lines.add(line);used+=line.length();oldest=row.optLong("id");}
            cursor=rows.optJSONObject(rows.length()-1).optLong("id");
        }
        StringBuilder recent=new StringBuilder();
        long skipped=full?count("SELECT count(*) FROM messages WHERE id<? AND id>? AND omit=0 AND "+SHOWN,""+oldest,""+start):0;
        if(skipped>0&&o.memory())recent.append("[").append(skipped).append(" older messages before #").append(oldest).append(" are not shown verbatim because of length; see structured_summary and retrieved_history]\n");
        for(int i=lines.size()-1;i>=0;i--)recent.append(lines.get(i));
        String retrieved=o.memory()?retrieve(current.optString("text"),oldest,Math.min(MainChatContext.RETRIEVED,room-recent.length())):"";
        return MainChatContext.assemble(new MainChatContext.Sections(fixed.current(),recent.toString(),fixed.notes(),fixed.memory(),fixed.summary(),retrieved,fixed.tasks(),fixed.pending(),fixed.clock(),fixed.calendar(),fixed.attached()),o.budget());
    }
    /** Older originals matching the question's words, best match first and newer first among equals, shown in order. */
    private String retrieve(String text,long before,int room){
        String q=MainChatContext.query(text);if(q.isBlank()||room<=0)return "";
        JSONArray found=query("SELECT m.* FROM messages m JOIN message_search f ON f.docid=m.id WHERE message_search MATCH ? AND m.id<? AND m.omit=0 AND m.state='done' ORDER BY m.id DESC LIMIT 200",new String[]{q,""+before});
        List<String> words=MainChatContext.tokens(text);List<JSONObject> rows=new ArrayList<>();for(int i=0;i<found.length();i++)rows.add(found.optJSONObject(i));
        Map<JSONObject,Integer> scores=new IdentityHashMap<>();for(JSONObject row:rows)scores.put(row,MainChatContext.score(row.optString("text"),words));
        rows.sort((a,b)->Integer.compare(scores.get(b),scores.get(a)));
        TreeMap<Long,String> chosen=new TreeMap<>();int used=0;
        for(JSONObject row:rows){if(chosen.size()>=40)break;String line=MainChatContext.excerpt(row,4000);if(used+line.length()>room)continue;chosen.put(row.optLong("id"),line);used+=line.length();}
        return String.join("",chosen.values());
    }
    private String taskState(){JSONArray tasks=arr();JSONArray jobs=jobs(true);for(int i=0;i<jobs.length();i++){JSONObject j=jobs.optJSONObject(i);if(j.optInt("hidden")==0)tasks.put(obj("id",j.optString("id"),"state",j.optString("state"),"request",MainChatContext.clip(j.optString("request"),400)));}return tasks.length()==0?"":tasks.toString();}
    /** Earlier replies still being prepared: answered separately, so this turn does not wait for or repeat them. */
    private String pendingReplies(Turn turn){JSONArray rows=query("SELECT id,reply_to FROM messages WHERE role='assistant' AND state IN ('queued','running') AND id<? ORDER BY id DESC LIMIT 8",new String[]{""+turn.reply()});StringBuilder b=new StringBuilder();for(int i=rows.length()-1;i>=0;i--){JSONObject r=rows.optJSONObject(i);b.append("#").append(r.optLong("id")).append(" answer to #").append(r.optLong("reply_to")).append(" is still being prepared in parallel\n");}return b.toString();}
    /**
     * The next oldest originals to fold into the summary, once the verbatim part after it passes 60% of the budget;
     * folds toward 40%, at most half the budget (60,000 characters) of excerpts per call, never the newest rows.
     */
    synchronized Summary summaryBatch(int budget){
        require();JSONObject m=meta();long through=m.optLong("through_id");
        JSONArray sizes=query("SELECT id,length(text) AS size FROM messages WHERE id>? AND omit=0 AND state='done' ORDER BY id",new String[]{""+through});
        long total=0;for(int i=0;i<sizes.length();i++)total+=sizes.optJSONObject(i).optLong("size")+40;
        int eligible=sizes.length()-KEEP_VERBATIM;if(eligible<=0||total<=budget*0.6)return null;
        long target=total-(long)(budget*0.4);int limit=Math.min(60000,Math.max(1,budget/2)),chars=0;long folded=0;
        JSONArray rows=query("SELECT * FROM messages WHERE id>? AND omit=0 AND state='done' ORDER BY id LIMIT ?",new String[]{""+through,""+Math.min(eligible,400)}),batch=arr();
        for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);String line=MainChatContext.summaryLine(row);if(batch.length()>0&&(chars+line.length()>limit||folded>=target))break;batch.put(row);chars+=line.length();folded+=row.optString("text").length()+40;}
        if(batch.length()==0)return null;
        Set<Long> allowed=new HashSet<>();String prior=through>0?m.optString("summary"):"";
        try{JSONObject previous=new JSONObject(prior);for(String key:List.of("facts","decisions","open_questions","corrections")){JSONArray a=previous.optJSONArray(key);for(int i=0;a!=null&&i<a.length();i++){JSONArray refs=a.optJSONObject(i).optJSONArray("sources");for(int j=0;refs!=null&&j<refs.length();j++)allowed.add(refs.optLong(j));}}}catch(Exception ignored){}
        for(int i=0;i<batch.length();i++)allowed.add(batch.optJSONObject(i).optLong("id"));
        return new Summary(generation(),batch.optJSONObject(batch.length()-1).optLong("id"),prior,batch,allowed);
    }
    synchronized boolean saveSummary(Summary batch,String response){
        require();if(batch.generation()!=generation())return false;
        try{
            JSONObject meta=meta();if(batch.through()<=meta.optLong("through_id"))return false;
            String text=response==null?"":response.strip();int open=text.indexOf('{'),close=text.lastIndexOf('}');if(open<0||close<=open)return false;
            JSONObject input=new JSONObject(text.substring(open,close+1));List<String> keys=List.of("facts","decisions","open_questions","corrections");
            for(String required:keys)if(input.optJSONArray(required)==null)return false;
            Map<String,List<JSONObject>> kept=new LinkedHashMap<>();
            for(String key:keys){JSONArray src=input.optJSONArray(key);List<JSONObject> dst=new ArrayList<>();for(int i=0;src!=null&&i<src.length()&&dst.size()<24;i++){JSONObject entry=src.optJSONObject(i);if(entry==null)continue;String value=entry.optString("text");JSONArray refs=entry.optJSONArray("sources");boolean valid=refs!=null&&refs.length()>0&&refs.length()<=12;for(int j=0;valid&&j<refs.length();j++)valid=batch.sources().contains(refs.optLong(j));if(valid&&!value.isBlank())dst.add(obj("text",MainChatContext.clip(value,300),"sources",refs));}kept.put(key,dst);}
            // Over the ceiling: drop the newest-listed entry of the longest list until it fits, rather than lose the whole pass.
            while(true){JSONObject clean=obj();for(String key:keys)put(clean,key,new JSONArray(kept.get(key)));String value=clean.toString();if(value.length()<=MainChatContext.SUMMARY){db.execSQL("UPDATE meta SET summary=?,through_id=? WHERE id=1 AND generation=?",new Object[]{value,batch.through(),batch.generation()});return true;}
                List<JSONObject> longest=null;for(List<JSONObject> list:kept.values())if(longest==null||list.size()>longest.size())longest=list;if(longest==null||longest.isEmpty())return false;longest.remove(longest.size()-1);}
        }catch(Exception e){return false;}
    }
    /**
     * Conversation since the last memory pass, once minimumUsers user messages have gathered or the user asked to remember.
     * Picture and calendar cards go as their short notes, never their records.
     */
    synchronized MemoryBatch memoryBatch(int minimumUsers){
        require();long through=meta().optLong("memory_through");
        JSONArray rows=query("SELECT * FROM messages WHERE id>? AND omit=0 AND state='done' AND role IN ('user','assistant') ORDER BY id LIMIT 80",new String[]{""+through});
        int users=0;boolean cue=false;StringBuilder transcript=new StringBuilder();
        for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);boolean user="user".equals(row.optString("role"));if(user){users++;cue|=MEMORY_CUE.matcher(row.optString("text")).find();}transcript.append(user?"사용자: ":"비서: ").append(MainChatContext.clip(CalendarCards.history(ChatImages.history(row.optString("text"))),1500).replace('\n',' ')).append('\n');}
        if(users==0||users<minimumUsers&&!cue)return null;
        return new MemoryBatch(generation(),rows.optJSONObject(rows.length()-1).optLong("id"),MainChatContext.clipStart(transcript.toString(),12000));
    }
    synchronized boolean memoryDone(MemoryBatch batch){require();if(batch.generation()!=generation())return false;db.execSQL("UPDATE meta SET memory_through=? WHERE id=1 AND memory_through<?",new Object[]{batch.through(),batch.through()});return true;}
    synchronized JSONObject job(String id){return query("SELECT * FROM jobs WHERE id=?",new String[]{id}).optJSONObject(0);}
    synchronized JSONArray jobs(boolean unresolved){return query("SELECT * FROM jobs WHERE "+(unresolved?"receipt=0":"hidden=0")+" ORDER BY created DESC LIMIT "+(unresolved?"8":"40"),null);}
    synchronized String createJob(long source,String request,JSONObject selection,String fingerprint){require();if(request==null||request.isBlank()||request.length()>MainChatContext.INPUT)throw new IllegalArgumentException("작업 지시는 1~12,000자예요.");db.beginTransaction();try{if(jobs(true).length()>=8)throw new IllegalStateException("진행 중인 작업이 8개예요.");String id=UUID.randomUUID().toString();if(source==0)source=insertMessage("user",request,"done","",0,"",selection.optString("engine"),"");if(message(source)==null)throw new IllegalArgumentException("원문을 찾지 못했어요.");ContentValues v=new ContentValues();v.put("id",id);v.put("source",source);v.put("request",request);v.put("selection",selection.toString());v.put("fingerprint",fingerprint);v.put("state","queued");v.put("created",System.currentTimeMillis());db.insertOrThrow("jobs",null,v);insertMessage("job","작업창으로 전달할 준비 중이에요.","done","",source,id,"","");db.setTransactionSuccessful();return id;}finally{db.endTransaction();}}
    synchronized boolean claimJob(String id){require();ContentValues v=new ContentValues();v.put("state","dispatching");return db.update("jobs",v,"id=? AND state='queued' AND hidden=0",new String[]{id})==1;}
    synchronized void attachJob(String id,String room,String run){require();db.execSQL("UPDATE jobs SET room=?,run=?,state='running' WHERE id=? AND receipt=0",new Object[]{room,run,id});}
    synchronized boolean finishJob(String id,String text,String state){require();db.beginTransaction();try{JSONObject j=job(id);if(j==null||j.optInt("receipt")!=0)return false;db.execSQL("UPDATE jobs SET receipt=1,state=?,request='',selection='{}' WHERE id=?",new Object[]{state,id});if(j.optInt("hidden")==0&&message(j.optLong("source"))!=null)insertMessage("job",MainChatContext.clip(text,MainChatContext.OUTPUT),"done",j.optString("run"),j.optLong("source"),id,"","");db.setTransactionSuccessful();return true;}finally{db.endTransaction();}}
    @Override public synchronized void close(){if(db!=null){db.close();db=null;}pinned="";}
}
