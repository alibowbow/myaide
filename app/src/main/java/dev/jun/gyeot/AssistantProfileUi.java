package dev.jun.gyeot;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.jun.gyeot.Ui.*;

/**
 * Chooses only metadata-reached children of the selected 들퍼레 tree (no arbitrary picker URI), or makes a new picture with
 * GPT image from a description the user writes: a picture, an SVG drawing or an animated GIF (GPT Image 2.6). Only that
 * description goes to OpenAI/Codex; the current photo never does (except as the reference of 표정 GIF, when the user says so).
 * Every made or chosen picture is kept in the library (ProfileLibrary), so any of them can be the profile again.
 */
final class AssistantProfileUi {
    private AssistantProfileUi(){}
    /** Draws one picture ({data (base64), …}); replaced in tests. Runs on a background thread. */
    interface Painter { org.json.JSONObject draw(AppStore store,org.json.JSONObject args,java.util.concurrent.atomic.AtomicReference<Runnable> cancel,boolean codex)throws Exception; }
    static volatile Painter painter=(store,args,cancel,codex)->PictureMaker.draw(store,args,new org.json.JSONArray(),cancel,()->{},codex);
    /** The image route Work uses: the Codex image account for a plan, else an API key the Images API has not refused; "" when neither. */
    static String route(AppStore store){if(store.codexImages.connected())return "codex";return store.openai.connected()&&!store.openai.usesPlan()&&!store.openai.refused(dev.jun.gyeot.core.Drawing.OPENAI_REFUSAL)?"api":"";}
    static void edit(Activity activity,Runnable changed){if(!activity.isFinishing()&&!activity.isDestroyed())new Editor(activity,changed).show();}
    static final class Editor {
        final Activity activity;final Runnable changed;final Ui ui;final Ui.Sheet sheet;final AssistantProfile profile;
        final Handler main=new Handler(Looper.getMainLooper());final AssistantProfile.Request request=new AssistantProfile.Request();
        final AtomicInteger selection=new AtomicInteger();final EditText name;final TextView state,shelfNote;final Button choose,make,living,moods;
        final LinearLayout shelfRow;
        byte[] replacement;String replacementName="";dev.jun.gyeot.core.ProfileShelf.Entry chosen;List<Thumb> shelf=new ArrayList<>();int shelfTicket;
        boolean closed,busy;Ui.Sheet picker,maker;
        /** One library picture in the strip, with a small decoded thumbnail. */
        record Thumb(dev.jun.gyeot.core.ProfileShelf.Entry entry,android.graphics.Bitmap picture){}
        final Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
            public void onActivityResumed(Activity a){if(a==activity&&!closed)profile.resume();}
            public void onActivityPaused(Activity a){if(a==activity)profile.pause();}
            public void onActivityStopped(Activity a){if(a==activity)profile.pause();}
            public void onActivityDestroyed(Activity a){if(a==activity)sheet.dismiss();}
            public void onActivityCreated(Activity a,Bundle b){} public void onActivityStarted(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}
        };
        Editor(Activity activity,Runnable changed){
            this.activity=activity;this.changed=changed;ui=new Ui(activity);profile=new AssistantProfile(activity);
            sheet=ui.selectionSheet("앱 비서 프로필","이 앱에만 저장해요 · 이름은 AI에게 알려 주고, 사진은 업로드하지 않아요");
            ImageView avatar=new ImageView(activity);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(ui.dp(96),ui.dp(96));ap.gravity=android.view.Gravity.CENTER_HORIZONTAL;ap.bottomMargin=ui.dp(12);sheet.body.addView(avatar,ap);profile.bind(avatar);
            name=ui.input("비서 이름",1,80);name.setText(profile.name());name.setContentDescription("앱 비서 이름");sheet.body.addView(name);
            state=ui.footer("GPT 이미지로 새로 만들거나, 들퍼레 폴더 안의 사진(PNG·JPEG·GIF·WebP, 최대 8MB)을 골라요. 동영상은 지원하지 않아요.");sheet.body.addView(state);
            sheet.body.addView(ui.sectionHeader("보관함"));
            shelfRow=ui.row();HorizontalScrollView strip=new HorizontalScrollView(activity);strip.setHorizontalScrollBarEnabled(false);strip.addView(shelfRow);sheet.body.addView(strip);
            shelfNote=ui.footer("만들거나 고른 사진이 여기 모여요.");sheet.body.addView(shelfNote);
            make=ui.button("GPT 이미지로 만들기",true,this::create);make.setContentDescription("GPT 이미지로 프로필 사진 만들기");LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,-2);mp.topMargin=ui.dp(12);sheet.body.addView(make,mp);
            choose=ui.button("들퍼레 폴더에서 사진 선택",false,this::choose);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.topMargin=ui.dp(8);sheet.body.addView(choose,cp);
            moods=ui.button("표정 GIF 자동 만들기 · GPT 이미지 2.6",false,this::moods);moods.setContentDescription("표정마다 움직이는 GIF 만들기");LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,-2);gp.topMargin=ui.dp(8);sheet.body.addView(moods,gp);
            // The profile as a living open-bot-avatar character: a ready shape, an AI-made one, or this very picture.
            living=ui.button("살아있는 아바타 · 움직이는 캐릭터",false,()->{if(alive()&&!busy)AssistantAvatarUi.edit(activity,()->{profile.reload();changed.run();});});living.setContentDescription("살아있는 아바타 꾸미기");LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=ui.dp(8);sheet.body.addView(living,lp);
            sheet.primary.setText("프로필 저장");sheet.primary.setOnClickListener(v->save());
            activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
            sheet.setOnDismissListener(d->{closed=true;activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);selection.incrementAndGet();request.cancel();if(picker!=null)picker.dismiss();if(maker!=null)maker.dismiss();profile.close();});
            loadShelf();
        }
        /** The library strip: newest first, each a small round thumbnail. Tap to pick (then 프로필 저장), long-press to delete. */
        void loadShelf(){int mine=++shelfTicket;AssistantProfile.IO.execute(()->{List<Thumb> items=new ArrayList<>();
            try{ProfileLibrary.adopt(activity);for(dev.jun.gyeot.core.ProfileShelf.Entry e:ProfileLibrary.read(activity).entries()){android.graphics.Bitmap t=null;try{t=thumbnail(ProfileLibrary.image(activity,e));}catch(Exception|OutOfMemoryError ignored){}items.add(new Thumb(e,t));}}catch(Exception ignored){}
            main.post(()->{if(closed||mine!=shelfTicket)return;shelf=items;showShelf();});});}
        static android.graphics.Bitmap thumbnail(byte[] b){android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options();o.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(b,0,b.length,o);int sample=1;while(Math.max(o.outWidth,o.outHeight)/(sample*2)>=128)sample*=2;android.graphics.BitmapFactory.Options d=new android.graphics.BitmapFactory.Options();d.inSampleSize=sample;return android.graphics.BitmapFactory.decodeByteArray(b,0,b.length,d);}
        static String kind(dev.jun.gyeot.core.ProfileShelf.Entry e){String k=switch(e.source){case "gpt"->"GPT";case "svg"->"SVG";case "gif"->"GIF";case "upload"->"사진";default->"이전";};return e.moods.isEmpty()?k:k+" · 표정 "+e.moods.size();}
        void showShelf(){
            shelfRow.removeAllViews();
            if(shelf.isEmpty()){shelfNote.setText("아직 보관한 사진이 없어요. GPT 이미지로 만들거나 폴더에서 고르면 여기 모여요.");return;}
            shelfNote.setText("사진 "+shelf.size()+"/"+dev.jun.gyeot.core.ProfileShelf.LIMIT+" · 눌러서 고르고 ‘프로필 저장’ · 길게 누르면 지워요");
            for(Thumb t:shelf){
                boolean on=chosen!=null&&chosen.id.equals(t.entry().id);
                LinearLayout cell=ui.column();cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);cell.setPadding(ui.dp(2),ui.dp(2),ui.dp(2),ui.dp(2));
                ImageView v=new ImageView(activity);if(t.picture()!=null)v.setImageDrawable(new AssistantProfile.Circle(new android.graphics.drawable.BitmapDrawable(activity.getResources(),t.picture())));
                android.graphics.drawable.GradientDrawable ring=new android.graphics.drawable.GradientDrawable();ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);ring.setStroke(ui.dp(on?3:1),on?ACCENT:FILL);v.setBackground(ring);v.setPadding(ui.dp(3),ui.dp(3),ui.dp(3),ui.dp(3));
                TextView caption=ui.text(kind(t.entry()),11,on?700:500,on?TEXT:MUTED);caption.setGravity(android.view.Gravity.CENTER);caption.setSingleLine(true);
                cell.addView(v,new LinearLayout.LayoutParams(ui.dp(58),ui.dp(58)));cell.addView(caption,new LinearLayout.LayoutParams(-2,-2));
                cell.setContentDescription("보관함 "+t.entry().name+" · "+kind(t.entry())+(on?", 선택됨":""));
                cell.setOnClickListener(x->pick(t.entry()));cell.setOnLongClickListener(x->{forget(t.entry());return true;});
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ui.dp(72),-2);lp.rightMargin=ui.dp(4);shelfRow.addView(cell,lp);
            }
        }
        void pick(dev.jun.gyeot.core.ProfileShelf.Entry e){if(!alive()||busy)return;int token=selection.incrementAndGet();setBusy(true);
            AssistantProfile.IO.execute(()->{try{byte[] bytes=ProfileLibrary.image(activity,e);AssistantProfile.decode(bytes);main.post(()->{if(!alive()||selection.get()!=token)return;replacement=bytes;replacementName=e.name;chosen=e;setBusy(false);state.setText(e.name+" · "+kind(e)+"\n보관함 사진 · 저장을 눌러야 바뀌어요.");showShelf();});}catch(Exception|OutOfMemoryError x){error(token,x);}});}
        void forget(dev.jun.gyeot.core.ProfileShelf.Entry e){if(!alive()||busy)return;
            ui.confirm("‘"+e.name+"’을(를) 보관함에서 지울까요?"+(e.moods.isEmpty()?"":" 표정 GIF "+e.moods.size()+"개도 함께 지워져요.")+" 지금 프로필 사진은 그대로예요.","지우기",()->AssistantProfile.IO.execute(()->{
                try{ProfileLibrary.remove(activity,e.id);}catch(Exception ignored){}main.post(()->{if(!alive())return;if(chosen!=null&&chosen.id.equals(e.id)){chosen=null;replacement=null;state.setText("보관함에서 지웠어요.");}loadShelf();});})).show();}
        /** 표정 GIF: the picked library picture, else the profile picture shown now. */
        void moods(){if(!alive()||busy)return;
            AppStore store=activity.getApplication() instanceof GyeotApp app?app.store():null;if(store==null)return;
            dev.jun.gyeot.core.ProfileShelf.Entry pickedNow=chosen;
            AssistantProfile.IO.execute(()->{dev.jun.gyeot.core.ProfileShelf.Entry e=null;try{ProfileLibrary.adopt(activity);e=pickedNow!=null?ProfileLibrary.read(activity).find(pickedNow.id):ProfileLibrary.current(activity,AssistantProfile.read(activity).bytes());}catch(Exception ignored){}
                dev.jun.gyeot.core.ProfileShelf.Entry target=e;main.post(()->{if(!alive())return;if(target==null){state.setText("먼저 프로필 사진을 정하거나 보관함에서 사진을 골라 주세요.");return;}
                    ProfilePackUi.show(activity,store,target,()->{if(!closed){profile.reload();loadShelf();}changed.run();});});});}
        void show(){sheet.getWindow().getDecorView().addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener(){
            public void onViewAttachedToWindow(android.view.View v){}
            public void onViewDetachedFromWindow(android.view.View v){request.cancel();profile.pause();}
        });sheet.show();profile.resume();}
        boolean alive(){return !closed&&!activity.isFinishing()&&!activity.isDestroyed()&&!request.cancelled();}
        void setBusy(boolean value){busy=value;choose.setEnabled(!value);make.setEnabled(!value);living.setEnabled(!value);moods.setEnabled(!value);sheet.primary.setEnabled(!value);name.setEnabled(!value);}
        void choose(){if(!alive()||busy)return;int token=selection.incrementAndGet();setBusy(true);state.setText("들퍼레 폴더를 확인하는 중…");AssistantProfile.IO.execute(()->{try{android.net.Uri tree=SharedOutputFolder.selectedTree(activity);FileExplorerRepository repository=new FileExplorerRepository(activity,tree);main.post(()->{if(!alive()||selection.get()!=token)return;setBusy(false);openFolder(repository,repository.root,new ArrayDeque<>());});}catch(Exception e){error(token,e);}});}
        void openFolder(FileExplorerRepository repo,String parent,ArrayDeque<String> trail){
            if(!alive())return;if(picker!=null)picker.dismiss();Ui.Sheet list=ui.selectionSheet("프로필 사진 선택","들퍼레 폴더 내부 · 원본 파일은 바꾸지 않아요");picker=list;list.primary.setText("취소");list.primary.setOnClickListener(v->list.dismiss());list.body.addView(ui.footer("폴더 목록을 불러오는 중…"));
            int token=selection.incrementAndGet();list.setOnDismissListener(d->{if(picker==list){picker=null;selection.incrementAndGet();setBusy(false);}});list.show();
            AssistantProfile.IO.execute(()->{try{SharedOutputFolder.requireContentAccess(activity,repo.document(parent));List<FileExplorerRepository.Entry> entries=repo.list(parent,()->!alive()||selection.get()!=token);main.post(()->{if(!alive()||picker!=list||selection.get()!=token)return;list.body.removeAllViews();
                if(!trail.isEmpty())list.body.addView(ui.listRow(R.drawable.ic_back,MUTED,"상위 폴더",null,ui.chevron(),()->{ArrayDeque<String> next=new ArrayDeque<>(trail);String up=next.removeLast();openFolder(repo,up,next);}));
                int shown=0;for(FileExplorerRepository.Entry entry:entries){if(!entry.directory()&&!candidate(entry.name()))continue;shown++;String detail=entry.directory()?"폴더":entry.size()<0?"크기는 읽을 때 확인해요":String.format(Locale.ROOT,"%.1f MB",entry.size()/1048576d);
                    list.body.addView(ui.listRow(entry.directory()?R.drawable.ic_folder:R.drawable.ic_gallery,ACCENT,entry.name(),detail,ui.chevron(),()->{if(entry.directory()){ArrayDeque<String> next=new ArrayDeque<>(trail);next.addLast(parent);openFolder(repo,entry.id(),next);}else select(repo,entry,list);}));}
                if(shown==0)list.body.addView(ui.footer("사용할 사진이 없어요. PNG·JPEG·GIF·WebP 파일을 들퍼레 폴더에 넣어 주세요."));
            });}catch(Exception e){main.post(()->{if(alive()&&picker==list&&selection.get()==token){list.body.removeAllViews();list.body.addView(ui.footer(message(e)));}});}});
        }
        void select(FileExplorerRepository repo,FileExplorerRepository.Entry entry,Ui.Sheet list){if(!alive()||picker!=list||busy)return;list.dismiss();int token=selection.incrementAndGet();setBusy(true);state.setText("사진의 형식과 크기를 확인하는 중…");AssistantProfile.IO.execute(()->{try{if(entry.size()>AssistantProfile.MAX_BYTES)throw new java.io.IOException("프로필 파일은 8MB 이하만 사용할 수 있어요.");SharedOutputFolder.requireContentAccess(activity,entry.uri());repo.fresh(entry,request::cancelled);byte[] bytes=AssistantProfile.importImage(activity,entry.uri(),request);main.post(()->{if(!alive()||selection.get()!=token)return;replacement=bytes;replacementName=entry.name();chosen=null;setBusy(false);showShelf();state.setText(entry.name()+"\n검사 완료 · 저장을 눌러야 바뀌어요.");});}catch(Exception|OutOfMemoryError e){error(token,e);}});}
        void save(){if(!alive()||busy)return;final String selected;try{selected=AssistantProfile.validName(name.getText().toString());}catch(Exception e){state.setText(message(e));return;}setBusy(true);state.setText("이 기기에 저장하는 중…");byte[] bytes=replacement;String label=replacementName;AssistantProfile.IO.execute(()->{try{AssistantProfile.save(activity,selected,bytes,request);if(bytes!=null)keep(bytes,"upload",label,"",null);main.post(()->{if(!alive())return;sheet.dismiss();changed.run();});}catch(Exception|OutOfMemoryError e){error(selection.get(),e);}});}
        /** Keeps a made or chosen picture in the library; the profile works without it, so a full library only skips this. IO thread. */
        dev.jun.gyeot.core.ProfileShelf.Entry keep(byte[] bytes,String source,String label,String prompt,String svg){
            try{return ProfileLibrary.add(activity,bytes,source,label,prompt,svg);}catch(Exception|OutOfMemoryError e){return null;}
        }
        static String label(String description){String t=description==null?"":description.replaceAll("\\s+"," ").strip();return t.length()>24?t.substring(0,24)+"…":t;}
        /** What one drawing made: the bytes the profile keeps (PNG or GIF), how it was made, and the SVG it came from. */
        record Made(byte[] bytes,String source,String svg,AssistantProfile.Decoded decoded){}
        /**
         * GPT image: a description and a style, one square picture, a circular preview, then 바로 적용 saves it as the
         * profile (with the name in the editor) and closes. As a picture (GPT image), an SVG drawing or an animated GIF
         * (GPT Image 2.6); a GIF can bring the other eight expressions along, made in the background after 적용.
         * Every made picture is kept in the library. Closing the sheet stops the drawing; nothing is applied late.
         */
        void create(){
            if(!alive()||busy)return;
            AppStore store=activity.getApplication() instanceof GyeotApp app?app.store():null;String via=store==null?"":route(store);
            if(via.isEmpty()){state.setText("GPT 이미지를 쓰려면 설정 → ChatGPT에서 이미지용 Codex 계정이나 API 키를 연결해 주세요.\n기존 프로필은 그대로예요.");return;}
            if(maker!=null)maker.dismiss();boolean codex=via.equals("codex");
            Ui.Sheet s=ui.selectionSheet("GPT 이미지로 만들기",(codex?"ChatGPT 이미지(Codex)":"OpenAI 이미지 API")+" · 정사각형 한 장");maker=s;
            ImageView preview=new ImageView(activity);preview.setContentDescription("만든 프로필 그림 미리보기");preview.setVisibility(android.view.View.GONE);
            LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(ui.dp(132),ui.dp(132));pp.gravity=android.view.Gravity.CENTER_HORIZONTAL;pp.bottomMargin=ui.dp(14);s.body.addView(preview,pp);
            EditText describe=ui.input("어떤 모습으로 만들까요? 예: 안경 쓴 웃는 여우, 따뜻한 주황 배경",3,dev.jun.gyeot.core.ProfilePicture.LIMIT);describe.setContentDescription("만들 프로필 그림 설명");s.body.addView(describe);
            TextView styleTitle=ui.text("스타일",FOOTNOTE,620,MUTED);styleTitle.setPadding(ui.dp(4),ui.dp(14),0,ui.dp(8));s.body.addView(styleTitle);
            int[] style={0};s.body.addView(ui.pills(dev.jun.gyeot.core.ProfilePicture.STYLES,0,i->style[0]=i));
            String plain="적은 설명만 "+(codex?"ChatGPT(Codex)":"OpenAI")+"로 보내 그림을 만들어요. 지금 프로필 사진은 보내지 않아요. 보통 30초~1분 걸리고 사용량이 들 수 있어요.";
            TextView status=ui.footer(plain);
            TextView formatTitle=ui.text("형식",FOOTNOTE,620,MUTED);formatTitle.setPadding(ui.dp(4),ui.dp(14),0,ui.dp(8));s.body.addView(formatTitle);
            int[] format={0};boolean[] pack={true};
            android.view.View packChoice=ui.pills(new String[]{"나머지 표정 8개도 자동으로","이 GIF만"},0,i->pack[0]=i==0);packChoice.setVisibility(android.view.View.GONE);
            s.body.addView(ui.pills(new String[]{"그림","SVG 벡터","움직이는 GIF"},0,i->{format[0]=i;packChoice.setVisibility(i==2?android.view.View.VISIBLE:android.view.View.GONE);
                status.setText(i==0?plain:i==1?"GPT 이미지 2.6이 SVG 벡터로 그리고, 휴대폰이 프로필 그림으로 바꿔요. SVG 원본은 보관함에 함께 남아요. 사용량이 들어요."
                    :"GPT 이미지 2.6이 움직이는 GIF(평소 표정)를 만들어요. ‘자동으로’를 고르면 적용한 뒤 생각 중·기뻐요·놀람·슬퍼요·졸려요·윙크·좋아요·화나요 GIF도 이어서 만들어요(GIF마다 사용량이 들어요).");}));
            s.body.addView(packChoice);
            s.body.addView(status);
            Button again=ui.button("다시 만들기",false,()->{});again.setVisibility(android.view.View.GONE);LinearLayout.LayoutParams ag=new LinearLayout.LayoutParams(-1,-2);ag.bottomMargin=ui.dp(8);s.footer.addView(again,0,ag);
            Made[] result={null};int[] ticket={0};int[] madeAs={0};String[] madeFrom={""};java.util.concurrent.atomic.AtomicReference<Runnable> cancel=new java.util.concurrent.atomic.AtomicReference<>();
            android.graphics.drawable.Drawable[] playing={null};
            Runnable draw=()->{
                if(!alive()||maker!=s)return;
                int kind=format[0];String text=describe.getText().toString();
                org.json.JSONObject args=null;String ask;
                try{
                    ask=dev.jun.gyeot.core.ProfilePicture.prompt(text,style[0]);
                    if(kind==0)args=dev.jun.gyeot.core.Drawing.directArguments(new org.json.JSONObject().put("prompt",ask).put("aspect","square").put("edit",false).put("codex",codex).toString());
                    else if(kind==1)ask=dev.jun.gyeot.core.ProfileMotion.svgPrompt(text,style[0]);
                    else ask=(text.strip()+" "+dev.jun.gyeot.core.ProfilePicture.hint(style[0])).strip();
                }catch(Exception e){status.setText(message(e));return;}
                org.json.JSONObject picture=args;String request=ask;
                int mine=++ticket[0];result[0]=null;s.primary.setEnabled(false);s.primary.setText("그리는 중…");again.setVisibility(android.view.View.GONE);describe.setEnabled(false);
                status.setText(kind==2?"움직이는 GIF를 만드는 중이에요. 1분쯤 걸릴 수 있어요. 이 창을 닫으면 멈춰요.":"그리는 중이에요. 이 창을 닫으면 멈춰요.");
                Thread worker=new Thread(()->{
                    try{
                        Made made;
                        if(kind==0){org.json.JSONObject drawn=painter.draw(store,picture,cancel,codex);byte[] avatar=AssistantProfile.avatar(java.util.Base64.getMimeDecoder().decode(drawn.optString("data")));made=new Made(avatar,"gpt",null,AssistantProfile.decode(avatar));}
                        else if(kind==1){String svg=ProfileArt.maker.svg(store,request,cancel,codex);byte[] avatar=AssistantProfile.avatar(ProfileArt.render(svg,1024));made=new Made(avatar,"svg",svg,AssistantProfile.decode(avatar));}
                        else{byte[] gif=ProfileArt.maker.gif(store,request,dev.jun.gyeot.core.ProfileMotion.mood("idle"),null,cancel,codex,new ProfileArt.Route());made=new Made(gif,"gif",null,AssistantProfile.decode(gif));}
                        main.post(()->{
                            if(!alive()||maker!=s||ticket[0]!=mine){if(made.decoded().animation() instanceof android.graphics.drawable.Animatable a)a.stop();return;}
                            result[0]=made;madeAs[0]=kind;madeFrom[0]=request;
                            if(playing[0] instanceof android.graphics.drawable.Animatable a)a.stop();
                            playing[0]=made.decoded().animation()!=null?made.decoded().animation():made.decoded().first();
                            preview.setImageDrawable(new AssistantProfile.Circle(playing[0]));preview.setVisibility(android.view.View.VISIBLE);
                            if(playing[0] instanceof android.graphics.drawable.Animatable a&&android.animation.ValueAnimator.areAnimatorsEnabled())a.start();
                            s.primary.setEnabled(true);s.primary.setText("바로 적용");again.setVisibility(android.view.View.VISIBLE);describe.setEnabled(true);
                            status.setText("마음에 들면 ‘바로 적용’을 눌러요. 보관함에도 넣어 뒀어요. 설명을 고쳐 다시 만들 수도 있어요.");
                            String kept=text;AssistantProfile.IO.execute(()->{keep(made.bytes(),made.source(),label(kept),kept,made.svg());main.post(()->{if(!closed)loadShelf();});});
                        });
                    }catch(Exception|OutOfMemoryError e){main.post(()->{if(!alive()||maker!=s||ticket[0]!=mine)return;s.primary.setEnabled(true);s.primary.setText("다시 시도");describe.setEnabled(true);status.setText(message(e)+"\n기존 프로필은 그대로예요.");});}
                },"profile-picture");worker.setDaemon(true);worker.start();
            };
            again.setOnClickListener(v->draw.run());
            s.primary.setText("만들기");s.primary.setOnClickListener(v->{if(result[0]!=null)apply(s,result[0].bytes(),status,madeAs[0]==2&&pack[0]?madeFrom[0]:null,codex);else draw.run();});
            s.setOnDismissListener(d->{ticket[0]++;Runnable stop=cancel.getAndSet(null);if(stop!=null)try{stop.run();}catch(RuntimeException ignored){}if(playing[0] instanceof android.graphics.drawable.Animatable a)a.stop();if(maker==s)maker=null;});
            s.show();
        }
        /**
         * Saves the made picture with the editor's name, then closes both sheets and refreshes whoever opened the editor.
         * packFrom (a made GIF's description) also starts the other expressions in the background: the GIF is the idle one.
         */
        void apply(Ui.Sheet s,byte[] bytes,TextView status){apply(s,bytes,status,null,false);}
        void apply(Ui.Sheet s,byte[] bytes,TextView status,String packFrom,boolean codex){
            if(!alive()||busy||maker!=s)return;final String selected;try{selected=AssistantProfile.validName(name.getText().toString());}catch(Exception e){status.setText(message(e));return;}
            setBusy(true);s.primary.setEnabled(false);s.primary.setText("적용하는 중…");
            AppStore store=activity.getApplication() instanceof GyeotApp app?app.store():null;
            AssistantProfile.IO.execute(()->{try{AssistantProfile.save(activity,selected,bytes,request);
                    boolean started=false;
                    if(packFrom!=null&&store!=null)try{
                        dev.jun.gyeot.core.ProfileShelf.Entry e=keep(bytes,"gif",label(packFrom),packFrom,null);
                        if(e!=null){e=ProfileLibrary.saveMood(activity,e.id,"idle",bytes);if(!ProfilePack.busy()){ProfilePack.start(activity,store,e,e.missingMoods(),packFrom,true,codex);started=true;}}
                    }catch(Exception|OutOfMemoryError ignored){}
                    boolean packing=started;
                    main.post(()->{if(!alive())return;s.dismiss();sheet.dismiss();changed.run();
                        if(packing)android.widget.Toast.makeText(activity,"나머지 표정 GIF 8개를 이어서 만들고 있어요. 다 되면 프로필이 표정을 바꿔요.",android.widget.Toast.LENGTH_LONG).show();});}
                catch(Exception|OutOfMemoryError e){main.post(()->{if(!alive())return;setBusy(false);s.primary.setEnabled(true);s.primary.setText("바로 적용");status.setText(message(e)+"\n기존 프로필은 그대로예요.");});}});
        }
        void error(int token,Throwable error){main.post(()->{if(!alive()||selection.get()!=token)return;setBusy(false);state.setText(message(error)+"\n기존 프로필은 그대로예요.");});}
        static boolean candidate(String name){String n=name==null?"":name.toLowerCase(Locale.ROOT);return n.endsWith(".png")||n.endsWith(".jpg")||n.endsWith(".jpeg")||n.endsWith(".gif")||n.endsWith(".webp");}
        static String message(Throwable e){return e.getMessage()==null?"사진을 처리하지 못했어요.":e.getMessage();}
    }
}
