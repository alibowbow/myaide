package dev.jun.gyeot;

import android.app.*;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import dev.jun.gyeot.core.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Durable explorer conversation. Models propose bounded intent; real files come only from local metadata. */
final class FileExplorerChatUi {
    private FileExplorerChatUi() {}
    private static Session owner;
    static Session show(Activity activity,FileExplorerRepository repo,String directory,Runnable changed) {
        try { Session session=new Session(activity,repo,directory,changed,null);session.show();return session; }
        catch(Exception error){new AlertDialog.Builder(activity).setTitle("탐색 대화를 열지 못했어요").setMessage(String.valueOf(error.getMessage())).setPositiveButton("확인",null).show();return null;}
    }
    static Session embed(Activity activity,FileExplorerRepository repo,String directory,Runnable changed) {
        try { Session session=new Session(activity,repo,directory,changed,null);session.embed();return session; }
        catch(Exception error){Toast.makeText(activity,"탐색 대화를 열지 못했어요. 다시 열어 주세요.",Toast.LENGTH_LONG).show();return null;}
    }
    private interface Work<T>{T run()throws Exception;}
    static final class Run {
        final String message;final String query;final FileExplorerRepository repo;final String directory;final AtomicBoolean cancelled=new AtomicBoolean();final AtomicReference<Runnable> network=new AtomicReference<>();
        Run(String message,String query,FileExplorerRepository repo,String directory){this.message=message;this.query=query;this.repo=repo;this.directory=directory;}
    }
    static final class Session implements Application.ActivityLifecycleCallbacks {
        final Activity activity;FileExplorerRepository repo;String directory;final Runnable changed;final Ui ui;final AppStore store;final FileExplorerChatStore history;
        final FileExplorerChatEngine.Completion completion;FileAiOperationsUi.Completion fileCompletion;final ExecutorService worker=Executors.newSingleThreadExecutor();final Set<AlertDialog> prompts=new HashSet<>();
        Dialog dialog;LinearLayout layout,messages,head;ScrollView scroll;EditText input;Button send,plain,stop,provider;TextView scopeLabel;Run active;volatile boolean closed,persistenceFailed,suspended;boolean embedded,compact,tiny;long promptEpoch;
        FileAiOperationsUi.Session fileSession;List<FileExplorerRepository.Entry> duplicateCandidates=List.of(),verifiedDuplicates=List.of();String engine;
        Session(Activity activity,FileExplorerRepository repo,String directory,Runnable changed,FileExplorerChatEngine.Completion completion)throws Exception {
            if(owner!=null&&!owner.closed)owner.close();this.activity=activity;this.repo=repo;this.directory=directory;this.changed=changed;this.completion=completion;ui=new Ui(activity);store=((GyeotApp)activity.getApplication()).store();history=FileExplorerChatStore.open(activity);history.recoverInterrupted();
            if(repo!=null&&directory!=null)history.setScope(repo.tree.toString(),directory);engine=ScopedModels.explorer(store).engine();
            owner=this;activity.getApplication().registerActivityLifecycleCallbacks(this);
        }
        void show(){
            build(false);dialog=new Dialog(activity);dialog.setContentView(layout);dialog.setOnCancelListener(d->close());dialog.setOnDismissListener(d->close());dialog.show();Window window=dialog.getWindow();if(window!=null){window.setLayout(-1,-1);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);layout.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(ui.dp(12)+bars.left,ui.dp(10)+bars.top,ui.dp(12)+bars.right,ui.dp(10)+bars.bottom);return insets;});layout.requestApplyInsets();}render();
        }
        void embed(){build(true);render();}
        View view(){return layout;}
        void build(boolean inline){
            embedded=inline;layout=ui.column();if(inline){layout.setBackground(ui.glass(Ui.R_CARD,false));layout.setClipToOutline(true);}else layout.setBackgroundColor(Ui.BG);layout.setPadding(ui.dp(inline?14:12),ui.dp(inline?8:10),ui.dp(inline?12:12),ui.dp(inline?8:10));
            head=ui.row();if(!inline)head.addView(ui.chip("‹ 목록",this::close));TextView title=ui.title(inline?"AI 지시":"AI 탐색 대화",inline?16:21);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);LinearLayout.LayoutParams weight=new LinearLayout.LayoutParams(0,-2,1);if(!inline)weight.leftMargin=ui.dp(8);head.addView(title,weight);
            provider=ui.chip(providerLabel(),this::chooseProvider);provider.setContentDescription("탐색 AI 제공자·세부 모델·생각 깊이 선택");
            if(inline){provider.setTextSize(12);provider.setSingleLine(true);provider.setEllipsize(android.text.TextUtils.TruncateAt.END);provider.setPadding(ui.dp(8),0,ui.dp(8),0);head.addView(provider,new LinearLayout.LayoutParams(ui.dp(132),-2));Button more=ui.chip("⋯",this::menu);more.setContentDescription("AI 지시 메뉴");head.addView(more,new LinearLayout.LayoutParams(ui.dp(48),-2));}
            layout.addView(head);
            scopeLabel=ui.label(scope()+(inline?"":"\n파일 목록은 로컬에서 · 읽기·쓰기는 연결한 들퍼레만"),inline?10:11,Ui.MUTED);if(inline){scopeLabel.setSingleLine(true);scopeLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);}ui.add(layout,scopeLabel,inline?0:6);
            if(!inline){LinearLayout tools=ui.row();tools.addView(provider,new LinearLayout.LayoutParams(0,-2,1));tools.addView(ui.chip("기록 지우기",this::clear));layout.addView(tools);}
            messages=ui.column();scroll=new ScrollView(activity);scroll.setFillViewport(false);scroll.addView(messages);layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
            input=ui.input(inline?"파일에 지시하세요 · 예: PDF 찾아줘":"예: 중복 후보 찾아줘 → 그중 PDF만",inline?2:3,1000);input.setContentDescription("파일 탐색 AI 지시 입력");if(inline){input.setMinLines(1);input.setMaxLines(2);input.setPadding(ui.dp(10),ui.dp(7),ui.dp(10),ui.dp(7));input.setTextSize(14);}input.setText(history.draft());input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){try{history.saveDraft(s.toString());}catch(Exception error){storageError(error);}}public void afterTextChanged(Editable e){}});ui.add(layout,input,inline?4:6);
            LinearLayout actions=ui.row();plain=ui.chip("이름 검색",()->submit(false));send=ui.chip("AI 보내기",()->submit(true));send.setContentDescription("AI 지시 보내기");plain.setContentDescription("파일 이름으로 로컬 검색");stop=ui.chip("취소",()->cancel("요청을 취소했어요. 자동 재실행하지 않아요."));if(inline)for(Button action:List.of(plain,send,stop)){action.setTextSize(13);action.setPadding(ui.dp(8),0,ui.dp(8),0);}
            // The AI request is the primary action of this panel; local name search stays the quiet alternative.
            send.setBackground(ui.capsule(Ui.ACCENT));send.setTextColor(Ui.ON_ACCENT);send.setTypeface(Ui.weight(680));actions.addView(plain,new LinearLayout.LayoutParams(0,-2,1));actions.addView(send,new LinearLayout.LayoutParams(0,-2,1));actions.addView(stop);layout.addView(actions);
            if(!inline)ui.add(layout,ui.label("대화·입력은 앱 내부에 보관 · AI 전송은 매번 확인",10,Ui.MUTED),3);
        }
        void compact(boolean value){if(!embedded||compact==value)return;compact=value;applySizing();}
        void fitHeight(int pixels){if(!embedded)return;boolean value=pixels>0&&pixels<ui.dp(180);if(tiny==value)return;tiny=value;applySizing();}
        private void applySizing(){head.setVisibility(tiny?View.GONE:View.VISIBLE);scopeLabel.setVisibility(compact||tiny?View.GONE:View.VISIBLE);boolean single=compact||tiny;input.setMaxLines(single?1:2);
            // Some EditText themes retain a multi-line minimum despite setMaxLines(1). Keep an actual
            // legible one-line editor under IME pressure instead of consuming the conversation area.
            LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)input.getLayoutParams();params.height=single?Math.max(ui.dp(44),input.getLineHeight()+input.getCompoundPaddingTop()+input.getCompoundPaddingBottom()):-2;input.setLayoutParams(params);
        }
        void focusInput(){if(closed||suspended)return;input.requestFocus();android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);}
        void menu(){if(closed||suspended||persistenceFailed)return;long epoch=promptEpoch;AlertDialog prompt=new AlertDialog.Builder(activity).setTitle("AI 지시 · 현재 폴더").setItems(new String[]{"범위와 전송 안내","기록 지우기"},(d,w)->{if(closed||suspended||persistenceFailed||epoch!=promptEpoch)return;if(w==0)text("탐색 대화 범위",scope()+"\n목록 검색은 기기에서 실행해요. 읽기·쓰기는 연결된 들퍼레만 가능해요. AI 전송은 요청마다 확인하며 파일 내용 전송은 별도 동의해요.");else clear();}).setNegativeButton("닫기",null).create();track(prompt);}
        void setScope(FileExplorerRepository next,String path){
            if(closed)return;String previous=repo==null?"":repo.tree.toString(),following=next==null?"":next.tree.toString();String nextPath=path==null?"":path;
            if(previous.equals(following)&&Objects.equals(directory==null?"":directory,nextPath)){repo=next;directory=path;return;}
            cancel("탐색 위치가 바뀌어 이전 요청을 중단했어요. 새 위치에서는 다시 지시해 주세요.");repo=next;directory=path;duplicateCandidates=List.of();verifiedDuplicates=List.of();
            try{if(next!=null&&path!=null)history.setScope(following,nextPath);}catch(Exception error){storageError(error);}if(scopeLabel!=null)scopeLabel.setText(scope()+(embedded?"":"\n파일 목록은 로컬에서 · 읽기·쓰기는 연결한 들퍼레만"));render();
        }
        void pause(){if(closed||suspended)return;cancel("화면을 떠나 요청을 중단했어요. 자동 재실행하지 않아요.");suspended=true;render();}
        boolean sameScope(Run run){return repo!=null&&run.repo!=null&&repo.tree.equals(run.repo.tree)&&Objects.equals(directory,run.directory);}
        boolean back(){if(closed)return false;if(active!=null||fileSession!=null&&!fileSession.closed){cancel("뒤로 가기로 요청을 취소했어요. 자동 재실행하지 않아요.");return true;}if(input!=null&&input.hasFocus()){input.clearFocus();layout.setFocusableInTouchMode(true);layout.requestFocus();android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(input.getWindowToken(),0);return true;}return false;}
        void resume(){if(closed)return;suspended=false;engine=ScopedModels.explorer(store).engine();if(provider!=null)provider.setText(providerLabel());render();}
        String scope(){if(repo==null||directory==null)return "위치를 열면 여기서 파일에 지시할 수 있어요";return "범위: "+directory.replace("primary:","내장 저장공간 / ");}
        String providerLabel(){TeamRoster roster=new TeamRoster(store,ScopedModels.explorer(store));return "AI: "+WorkBook.author(engine)+" · "+roster.label(engine,roster.selected(engine))+(store.ready(engine)?"":" · 연결 필요");}
        void chooseProvider(){
            if(closed||suspended||persistenceFailed||active!=null||fileSession!=null&&!fileSession.closed)return;
            long epoch=promptEpoch;
            FileExplorerModelUi.show(activity,store,()->!closed&&!suspended&&!persistenceFailed&&epoch==promptEpoch&&active==null&&(fileSession==null||fileSession.closed),this::track,()->{
                engine=ScopedModels.explorer(store).engine();store.settings.edit().putString("fileAiProvider",engine).apply();provider.setText(providerLabel());
            });
        }
        void render(){if(closed||messages==null)return;messages.removeAllViews();if(!history.notice().isEmpty())ui.add(messages,ui.label(history.notice(),11,Ui.WARNING),6);List<FileExplorerChatStore.Message> saved=history.messages();
            if(saved.isEmpty())ui.add(messages,ui.label(embedded?"목록을 보며 지시하세요. ‘중복 후보 찾아줘’, ‘그중 PDF만’처럼 이어서 요청할 수 있어요.":"폴더 목록을 보며 대화로 찾아보세요.\n‘중복 후보 찾아줘’, ‘그중 PDF만’, ‘노래 제목이 봄인 파일’처럼 이어서 요청할 수 있어요.\n\n내용 읽기·새 파일·수정본은 들퍼레 안에서 대상을 고르고 별도로 확인해요.",embedded?12:14,Ui.MUTED),embedded?6:14);
            for(var message:saved){LinearLayout card=ui.column();card.setPadding(ui.dp(10),ui.dp(8),ui.dp(10),ui.dp(8));card.setBackgroundColor(message.role().equals("user")?Ui.FILL:Ui.CARD);ui.add(card,ui.label((message.role().equals("user")?"나":"탐색 도우미")+" · "+status(message.status()),11,Ui.MUTED),3);if(repo==null||!message.rootUri().equals(repo.tree.toString())||!message.directory().equals(directory))ui.add(card,ui.label("이전 위치의 기록: "+message.directory(),10,Ui.MUTED),3);TextView text=ui.label(message.text(),14,Ui.TEXT);text.setTextIsSelectable(true);ui.add(card,text,4);
                if(!message.results().isEmpty()){ui.add(card,ui.label("검색 당시 메타데이터 · 현재 상태는 다시 검색해 확인",10,Ui.MUTED),4);for(var result:message.results()){TextView row=ui.label("▤ "+result.name()+"\n"+result.mime()+" · "+FileExplorerActivity.size(result.size())+" · "+date(result.modified()),12,Ui.TEXT);row.setPadding(0,ui.dp(5),0,ui.dp(5));row.setOnClickListener(v->text("검색 당시 파일 정보",result.name()+"\n종류: "+result.mime()+"\n크기: "+FileExplorerActivity.size(result.size())+"\n수정: "+date(result.modified())+"\nURI: "+result.uri()+"\n\n저장된 목록 정보예요. 내용을 열거나 변경하지 않았어요."));card.addView(row);}}
                ui.add(messages,card,8);
            }
            if(!duplicateCandidates.isEmpty()&&repo!=null&&repo.contentAllowed())ui.add(messages,ui.chip("내용까지 중복 확인 · 들퍼레만",this::exactDuplicates),8);
            if(!verifiedDuplicates.isEmpty())ui.add(messages,ui.chip("확인된 중복 · 한 개씩 휴지통 검토",()->DuplicateTrashUi.review(activity,repo,verifiedDuplicates,()->{verifiedDuplicates=List.of();changed.run();render();})),8);
            boolean busy=active!=null||fileSession!=null&&!fileSession.closed;send.setEnabled(!busy&&!persistenceFailed&&!suspended);send.setAlpha(send.isEnabled()?1f:.45f);plain.setEnabled(!busy&&!persistenceFailed&&!suspended);provider.setEnabled(!busy&&!suspended);stop.setVisibility(busy?View.VISIBLE:View.GONE);scroll.post(()->{if(!closed)scroll.scrollTo(0,messages.getHeight());});
        }
        static String status(String value){return switch(value){case "pending"->"진행/확인 대기";case "cancelled"->"취소";case "interrupted"->"중단 · 재실행 안 함";case "error"->"오류";default->"기록";};}
        static String date(long value){return value<=0?"날짜 정보 없음":Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate().toString();}
        Run begin(String query)throws Exception{history.append("user",query,"completed");String id=history.append("assistant","요청을 준비하고 있어요. 아직 검색·파일 작업을 완료하지 않았어요.","pending");Run run=new Run(id,query,repo,directory);active=run;input.setText("");duplicateCandidates=List.of();verifiedDuplicates=List.of();render();return run;}
        void submit(boolean ai){if(closed||suspended||active!=null||persistenceFailed||fileSession!=null&&!fileSession.closed)return;String query=input.getText().toString().strip();if(query.isEmpty())return;if(repo==null||directory==null){text("위치를 먼저 열어 주세요","파일 목록에서 탐색 위치를 연 뒤 대화를 시작해 주세요. 입력과 이전 대화는 보관해요.");return;}
            try{FileExplorerChatEngine.metadataScope(repo,directory);
                if(!ai){Run run=begin(query);FileSearchPlan filter=FileSearchPlan.plain(query);search(run,new FileExplorerChatEngine.Plan(FileExplorerChatEngine.Action.SEARCH,filter,FileExplorerChatEngine.Reason.NONE,FileExplorerChatEngine.filterJson(filter)));return;}
                ZonedDateTime now=ZonedDateTime.now();JSONObject context=history.modelContext(query,now);JSONObject previous=context.optJSONObject("previous_filters");FileExplorerChatEngine.Request request=new FileExplorerChatEngine.Request(query,context.optString("previous_query",""),previous==null?"":previous.toString(),now);
                FileExplorerChatEngine.Plan local=FileExplorerChatEngine.localPlan(request);
                if(local==null&&!store.ready(engine)){chooseProvider();return;}
                Run run=begin(query);
                if(local!=null){planned(run,local,true);return;}
                work(run,()->FileExplorerChatEngine.capture(store,engine),session->{String notice="받는 곳: "+session.label()+"\n\n이번 질문·이전 검색 질문·검색 조건·오늘 날짜/시간대만 전송해요. 관찰한 파일 이름·목록·URI·내용은 보내지 않아요. 자동 제공자 전환이나 외부 파일 도구는 없어요. 요금이 발생할 수 있어요.\n\n이번 질문: "+request.query()+"\n이전 검색 질문: "+request.previousQuery()+"\n이전 검색 조건: "+(request.previousFilterJson().isEmpty()?"없음":request.previousFilterJson())+"\n날짜: "+request.now().toLocalDate()+" · "+request.now().getZone()+"\n\n"+scope();confirm(run,"AI로 보낼 질문 확인",notice,"동의하고 보내기",()->work(run,()->completion==null?session.plan(request,run.cancelled::get,run.network):session.plan(request,run.cancelled::get,run.network,completion),plan->planned(run,plan)));});
            }catch(Exception error){if(active!=null)fail(active,error);else text("요청을 시작하지 못했어요",String.valueOf(error.getMessage()));}
        }
        void planned(Run run,FileExplorerChatEngine.Plan plan){planned(run,plan,false);}
        void planned(Run run,FileExplorerChatEngine.Plan plan,boolean local){if(!live(run))return;switch(plan.action()){
            case SEARCH -> {update(run,(local?"입력한 명령에서 검색 조건을 준비했어요. 외부 AI 전송 없이 확인 후 찾아요.\n":"AI가 검색 조건을 제안했어요. 아직 목록 검색 전이에요.\n")+plan.filter().summary(),"pending",List.of());confirm(run,"이 조건으로 찾을까요?",plan.filter().summary()+"\n\n"+scope()+"\n최대 2,000개·20단계·60초. 받은 날짜는 확인하지 못하며 수정 날짜만 사용해요. 노래는 이름·종류만, 중복은 크기가 같은 후보만 찾아요.","목록에서 찾기",()->search(run,plan));}
            case CLARIFY -> {try{history.rememberSearchQuery(run.query);finish(run,plan.clarification()+"\n아직 파일을 읽거나 변경하지 않았어요.","completed",List.of());}catch(Exception error){fail(run,error);}}
            case ASK,CREATE,REVISE -> handoff(run,plan.action());
        }}
        void search(Run run,FileExplorerChatEngine.Plan plan){update(run,"현재 폴더의 실제 메타데이터에서 찾고 있어요…","pending",List.of());work(run,()->FileExplorerChatEngine.search(run.repo,run.directory,plan,run.cancelled::get),result->{try{history.acceptPlan(run.query,plan.filterJson(),ZoneId.systemDefault());List<FileExplorerChatStore.ResultMetadata> rows=metadata(result.entries());if(plan.filter().duplicateCandidates&&run.repo.contentAllowed())duplicateCandidates=List.copyOf(result.entries());String report=plan.filter().summary()+"\n\n"+result.entries().size()+"개 결과 · "+result.visited()+"개 항목 확인\n"+result.notice()+(result.entries().size()>rows.size()?"\n대화에는 앞 "+rows.size()+"개만 보관해요. 더 구체적인 조건으로 좁혀 주세요.":"");finish(run,report,"completed",rows);}catch(Exception error){fail(run,error);}});}
        List<FileExplorerChatStore.ResultMetadata> metadata(List<FileExplorerRepository.Entry> entries){List<FileExplorerChatStore.ResultMetadata> rows=new ArrayList<>();for(var e:entries.subList(0,Math.min(20,entries.size())))rows.add(new FileExplorerChatStore.ResultMetadata(e.id(),e.name(),e.mime(),e.size(),e.modified(),e.uri().toString(),e.parent()));return rows;}
        void handoff(Run run,FileExplorerChatEngine.Action action){if(!run.repo.contentAllowed()){finish(run,"이 위치는 목록 탐색만 가능해요. 내용 읽기·쓰기에는 별도로 연결한 들퍼레 위치를 열어 주세요. 파일을 읽거나 변경하지 않았어요.","completed",List.of());return;}
            confirm(run,"들퍼레 텍스트 작업으로 이어가기","현재 폴더의 실제 대상은 다음 화면에서 직접 고릅니다. .txt/.md/.csv/.json/.log UTF-8 128KB 이하만 지원해요. 내용 전송은 제공자와 원문을 확인해 별도 동의하고, 수정은 원본을 보존한 새 파일로 저장해요.","대상·내용 확인",()->{finish(run,"텍스트 작업 확인 화면을 열었어요. 아직 읽기·전송·저장을 완료한 것은 아니에요.","completed",List.of());if(closed||persistenceFailed)return;FileAiOperations.Mode mode=FileAiOperations.Mode.valueOf(action.name());fileSession=FileAiOperationsUi.showChat(activity,run.repo,run.directory,mode,run.query,changed,(title,body)->{try{if(!closed&&!suspended&&!persistenceFailed&&sameScope(run))history.append("assistant",bounded(title+"\n"+body),title.contains("취소")?"cancelled":title.contains("중단")?"error":"completed");}catch(Exception error){storageError(error);}fileSession=null;render();},fileCompletion);render();});
        }
        private record Exact(List<FileExplorerRepository.Entry> matches,int checked,int skipped,int groups) {}
        void exactDuplicates(){
            if(closed||suspended||active!=null||repo==null||!repo.contentAllowed()||duplicateCandidates.isEmpty())return;
            List<FileExplorerRepository.Entry> candidates=List.copyOf(duplicateCandidates);
            try{Run run=begin("들퍼레 안 중복 후보의 내용을 기기에서만 비교");
                confirm(run,"기기에서 내용 비교","지정한 들퍼레 안에서만 파일 내용을 읽어 SHA-256을 비교해요. 외부 AI 전송·삭제·이동은 없어요. 파일당 32MB, 전체 256MB, 60초 한도이며 생략한 파일은 미확인으로 남아요.","내용 비교",()->work(run,()->{
                    run.repo.requireContentAccess(run.repo.document(run.repo.root));long deadline=System.nanoTime()+60_000_000_000L;FileExplorerRepository.ReadBudget budget=new FileExplorerRepository.ReadBudget(256L*1024*1024);Map<String,List<FileExplorerRepository.Entry>> groups=new LinkedHashMap<>();int checked=0,skipped=0;
                    for(var entry:candidates){FileExplorerRepository.check(run.cancelled::get);if(entry.size()<0||entry.size()>32L*1024*1024||budget.remaining()<=0||System.nanoTime()>=deadline){skipped++;continue;}
                        try{run.repo.fresh(entry,run.cancelled::get);String hash=run.repo.hash(entry.uri(),32L*1024*1024,()->run.cancelled.get()||System.nanoTime()>=deadline,budget);run.repo.fresh(entry,run.cancelled::get);checked++;groups.computeIfAbsent(hash,k->new ArrayList<>()).add(entry);}catch(Exception error){if(run.cancelled.get())throw error;skipped++;}}
                    List<FileExplorerRepository.Entry> matches=new ArrayList<>();int count=0;for(var group:groups.values())if(group.size()>1){count++;matches.addAll(group);}return new Exact(matches,checked,skipped,count);
                },result->{verifiedDuplicates=List.copyOf(result.matches());finish(run,(result.checked()==0?"내용 비교 미확인 · 검사한 파일이 없어요.":"검사한 "+result.checked()+"개 안에서 동일 해시 "+result.groups()+"그룹")+"\n미검사 "+result.skipped()+"개 · 생략한 파일의 중복 여부는 알 수 없어요. 삭제·이동·외부 전송은 하지 않았어요.","completed",metadata(result.matches()));}));
            }catch(Exception error){if(active!=null)fail(active,error);else text("내용 비교를 시작하지 못했어요",String.valueOf(error.getMessage()));}
        }
        <T> void work(Run run,Work<T> work,java.util.function.Consumer<T> success){worker.execute(()->{try{if(!liveWorker(run))return;T result=work.run();activity.runOnUiThread(()->{if(live(run))success.accept(result);});}catch(Exception error){activity.runOnUiThread(()->{if(live(run))fail(run,error);});}});}
        boolean liveWorker(Run run){return !closed&&!suspended&&!persistenceFailed&&!run.cancelled.get();}boolean live(Run run){return !closed&&!suspended&&!persistenceFailed&&active==run&&!run.cancelled.get();}
        void update(Run run,String text,String status,List<FileExplorerChatStore.ResultMetadata> results){if(!live(run))return;try{history.update(run.message,bounded(text),status,results);render();}catch(Exception error){storageError(error);cancel("기록 저장 오류로 요청을 중단했어요.");}}
        void finish(Run run,String text,String status,List<FileExplorerChatStore.ResultMetadata> results){if(!live(run))return;update(run,text,status,results);if(active==run)active=null;render();}
        void fail(Run run,Exception error){finish(run,"중단 · 완료되지 않았어요.\n"+String.valueOf(error.getMessage()),"error",List.of());}
        void cancel(String notice){promptEpoch++;Run run=active;active=null;if(run!=null){run.cancelled.set(true);Runnable abort=run.network.getAndSet(null);if(abort!=null)new Thread(abort,"explorer-chat-cancel").start();try{history.update(run.message,notice,"cancelled",List.of());}catch(Exception error){storageError(error);}}if(fileSession!=null){fileSession.close();fileSession=null;}for(AlertDialog p:new ArrayList<>(prompts))p.dismiss();render();}
        void confirm(Run run,String title,String body,String positive,Runnable action){AtomicBoolean decided=new AtomicBoolean();ScrollView area=new ScrollView(activity);TextView text=ui.label(body,14,Ui.TEXT);text.setPadding(ui.dp(20),ui.dp(12),ui.dp(20),ui.dp(12));area.addView(text);AlertDialog prompt=new AlertDialog.Builder(activity).setTitle(title).setView(area).setNegativeButton("취소",(d,w)->{if(decided.compareAndSet(false,true)&&live(run))cancel("확인을 취소했어요. 자동 실행하지 않아요.");}).setPositiveButton(positive,(d,w)->{if(decided.compareAndSet(false,true)&&live(run))action.run();}).create();prompt.setOnCancelListener(d->{if(decided.compareAndSet(false,true)&&live(run))cancel("확인을 취소했어요. 자동 실행하지 않아요.");});track(prompt);}
        void track(AlertDialog prompt){prompts.add(prompt);prompt.setOnDismissListener(d->prompts.remove(prompt));prompt.show();}
        void text(String title,String body){ScrollView area=new ScrollView(activity);TextView text=ui.label(body,14,Ui.TEXT);text.setTextIsSelectable(true);text.setPadding(ui.dp(18),ui.dp(12),ui.dp(18),ui.dp(12));area.addView(text);track(new AlertDialog.Builder(activity).setTitle(title).setView(area).setPositiveButton("닫기",null).create());}
        void clear(){if(closed||suspended||persistenceFailed||active!=null||fileSession!=null&&!fileSession.closed)return;long epoch=promptEpoch;track(new AlertDialog.Builder(activity).setTitle("탐색 대화 기록 지우기").setMessage("앱 내부의 탐색 대화와 임시 입력만 지워요. 실제 파일은 삭제하지 않아요.").setNegativeButton("취소",null).setPositiveButton("기록 지우기",(d,w)->{if(closed||suspended||persistenceFailed||epoch!=promptEpoch||active!=null||fileSession!=null&&!fileSession.closed)return;try{history.clear();input.setText("");duplicateCandidates=List.of();verifiedDuplicates=List.of();render();}catch(Exception error){storageError(error);}}).create());}
        void storageError(Exception error){if(persistenceFailed)return;persistenceFailed=true;Run run=active;active=null;if(run!=null){run.cancelled.set(true);Runnable abort=run.network.getAndSet(null);if(abort!=null)new Thread(abort,"chat-record-error-cancel").start();}if(fileSession!=null){fileSession.close();fileSession=null;}for(AlertDialog prompt:new ArrayList<>(prompts))prompt.dismiss();if(!closed){Toast.makeText(activity,"대화 기록 저장 오류로 중단을 요청했어요. 다시 열어 주세요. 저장 중이었다면 목록을 확인해 주세요.",Toast.LENGTH_LONG).show();render();}}
        static String bounded(String text){return text.length()<=16000?text:text.substring(0,15900)+"\n[긴 답변은 이 대화에 일부만 보관했어요.]";}
        void close(){if(closed)return;cancel("대화 화면을 닫아 중단했어요. 자동 재실행하지 않아요.");closed=true;if(owner==this)owner=null;worker.shutdownNow();for(AlertDialog p:new ArrayList<>(prompts))p.dismiss();if(dialog!=null&&dialog.isShowing())dialog.dismiss();activity.getApplication().unregisterActivityLifecycleCallbacks(this);}
        @Override public void onActivityStopped(Activity owner){if(owner==activity){if(embedded)pause();else close();}}@Override public void onActivityDestroyed(Activity owner){if(owner==activity)close();}
        @Override public void onActivityCreated(Activity owner,Bundle b){}@Override public void onActivityStarted(Activity owner){}@Override public void onActivityResumed(Activity owner){if(owner==activity&&embedded&&layout!=null&&layout.isShown())resume();}@Override public void onActivityPaused(Activity owner){}@Override public void onActivitySaveInstanceState(Activity owner,Bundle b){}
    }
}
