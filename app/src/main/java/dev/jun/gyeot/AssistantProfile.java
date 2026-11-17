package dev.jun.gyeot;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.util.AtomicFile;
import android.view.*;
import android.widget.ImageView;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

/** Local app persona. Never a phone/account identity or a network upload; only the name goes into the AI instructions (AppStore.assistantName). */
final class AssistantProfile implements AutoCloseable {
    static final int MAX_BYTES=8*1024*1024, TARGET=512, MAX_FRAMES=120;
    static final long FRAME_BYTES=64L*1024*1024;
    private static final int MAGIC=0x41504631;
    static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"assistant-profile");t.setDaemon(true);return t;});
    private static final Object STORE=new Object();
    final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private String name="들퍼레";
    private ImageView view;
    private Drawable animated,still;
    private Circle displayed;
    /** The living avatar (open-bot-avatar) when one is set and valid, shown instead of the circular picture. */
    private AvatarDrawable avatar;
    private String mood = "idle";
    private boolean working;
    /** After a quiet while the profile dozes off (sleepy) until the next reply or until the screen is opened again. */
    static final long DOZE_MS = 180_000;
    /** Expression GIFs are drawn this much closer, so the face fills the small circle. */
    static final float GIF_ZOOM = 1.15f;
    private final Runnable doze = () -> { if (!working && "idle".equals(mood)) setMood("sleepy"); };
    private final Runnable calm = () -> { if (!working) { setMood("idle"); main.removeCallbacks(doze); if (this.resumed) main.postDelayed(doze, DOZE_MS); } };
    /** Expression GIFs (GPT Image 2.6) of the picture shown now, by mood, when the library has them turned on; else null. */
    private Map<String, File> gifs;
    private String gifShown;
    private int gifTicket;
    private Drawable baseAnimated, baseStill;
    private static final Set<AssistantProfile> LIVE = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean resumed;
    private volatile boolean closed;
    private int epoch;
    private final View.OnAttachStateChangeListener attachment=new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){watch();sync();}public void onViewDetachedFromWindow(View v){unwatch();stop();}};
    private final ViewTreeObserver.OnPreDrawListener visibility=()->{sync();return true;};
    AssistantProfile(Context context){this.context=context.getApplicationContext();name=readName(this.context);synchronized(LIVE){LIVE.add(this);}}
    /** Every open profile reloads: the library's expression GIFs for the shown picture changed. Any thread. */
    static void refreshAll(){List<AssistantProfile> all;synchronized(LIVE){all=new ArrayList<>(LIVE);}for(AssistantProfile p:all)p.main.post(()->{if(!p.closed)p.reload();});}
    String name(){return name;}
    static File file(Context c){return new File(c.getFilesDir(),"assistant-profile-v1.bin");}
    /** The saved name, also for the instructions (AppStore.assistantName). */
    static String readName(Context c){ // Tiny header only; committed-file rename is atomic. No decode or metadata lock on UI.
        try(DataInputStream in=new DataInputStream(new FileInputStream(file(c)))){if(in.readInt()!=MAGIC)return "들퍼레";return validName(in.readUTF());}catch(Exception e){return "들퍼레";}
    }
    static String validName(String value)throws IOException{String n=value==null?"":value.strip();if(n.isEmpty())n="들퍼레";if(n.codePointCount(0,n.length())>40||n.codePoints().anyMatch(Character::isISOControl))throw new IOException("이름은 줄바꿈 없이 40자까지 입력해 주세요.");return n;}
    void bind(ImageView target){if(closed)return;unwatch();stop();if(view!=null)view.removeOnAttachStateChangeListener(attachment);view=target;target.addOnAttachStateChangeListener(attachment);target.setScaleType(ImageView.ScaleType.FIT_XY);watch();reload();}
    void reload(){if(closed)return;name=readName(context);int ticket=++epoch;IO.execute(()->{try{if(closed)return;Saved s=read(context);if(closed)return;Decoded d=s.bytes.length==0?null:decode(s.bytes);Map<String,File> moods=moodGifs(context,s.bytes);AvatarDrawable living=moods!=null?null:living(context,s,d);main.post(()->{if(closed||ticket!=epoch)return;stop();name=s.name;animated=d==null?null:d.animation;still=d==null?null:d.first;baseAnimated=animated;baseStill=still;gifs=moods;gifShown=null;gifTicket++;displayed=null;avatar=living;if(avatar!=null)avatar.mood(mood);sync();showGif();});}catch(Exception|OutOfMemoryError e){/* Preserve last good rendered profile; corrupt private data is not overwritten. */}});}
    /**
     * The saved living avatar, or null for the plain picture: no avatar set, an unreadable one, or a picture character whose
     * picture has since changed (it was traced from other pixels).
     */
    static AvatarDrawable living(Context context,Saved saved,Decoded decoded){
        dev.jun.gyeot.core.AvatarSpec spec=AssistantAvatar.read(context);if(spec==null)return null;
        Bitmap texture=null;
        if(dev.jun.gyeot.core.AvatarSpec.PICTURE.equals(spec.kind)){
            if(saved.bytes.length==0||decoded==null||!(decoded.first instanceof BitmapDrawable picture)||!AssistantAvatar.checksum(saved.bytes).equals(spec.picture))return null;
            texture=picture.getBitmap();
        }
        try{AvatarDrawable d=new AvatarDrawable(spec,texture,true,Ui.FILL);d.density(context.getResources().getDisplayMetrics().density);return d;}catch(RuntimeException invalid){return null;}
    }
    /**
     * The avatar's mood from the assistant's work: thinking while replies are being prepared, a short happy beat when they
     * have all arrived, then idle. Pictures without a living avatar ignore it.
     */
    void working(int pending){working(pending,null);}
    /** failed is asked only when the replies have just all arrived: a failed last reply makes the profile sad for a moment instead of happy. */
    void working(int pending,java.util.function.BooleanSupplier failed){
        boolean now=pending>0;if(now==working)return;working=now;main.removeCallbacks(calm);main.removeCallbacks(doze);
        boolean sad=false;if(!now&&failed!=null)try{sad=failed.getAsBoolean();}catch(RuntimeException ignored){}
        setMood(now?"thinking":sad?"sad":"happy");
        if(!now)main.postDelayed(calm,sad?4200:2600);
    }
    private void setMood(String next){mood=dev.jun.gyeot.core.BotAvatar.mood(next);if(avatar!=null)avatar.mood(mood);showGif();}
    /** The mood shown now: one of BotAvatar.MOODS. */
    String mood(){return mood;}
    void resume(){if(closed)return;resumed=true;if(!working){if("sleepy".equals(mood))setMood("idle");if("idle".equals(mood)){main.removeCallbacks(doze);main.postDelayed(doze,DOZE_MS);}}sync();}
    void pause(){resumed=false;main.removeCallbacks(doze);stop();sync();}
    /** The picture's expression GIF files by mood, when the library keeps some for it and they are turned on. IO thread. */
    static Map<String,File> moodGifs(Context c,byte[] picture){
        try{dev.jun.gyeot.core.ProfileShelf.Entry e=ProfileLibrary.current(c,picture);if(e==null||!e.useMoods||e.moods.isEmpty())return null;
            Map<String,File> out=new HashMap<>();for(String m:e.moods){File f=new File(ProfileLibrary.dir(c),e.moodFile(m));if(f.isFile())out.put(m,f);}return out.isEmpty()?null:out;}
        catch(RuntimeException e){return null;}
    }
    /** The GIF for a mood, or the nearest one made: a lively mood falls back to happy, a quiet one to idle. */
    static String gifFor(Map<String,File> gifs,String m){
        if(gifs.containsKey(m))return m;String near=switch(m){case "love","wink","surprised"->"happy";default->"idle";};
        if(gifs.containsKey(near))return near;return gifs.containsKey("idle")?"idle":null;
    }
    /** Shows the expression GIF of the current mood (decoded off the main thread), or the picture itself when none fits. */
    private void showGif(){
        if(closed||gifs==null)return;String want=gifFor(gifs,mood);
        if(want==null){if(gifShown!=null){gifShown=null;gifTicket++;stop();animated=baseAnimated;still=baseStill;displayed=null;sync();}return;}
        if(want.equals(gifShown))return;gifShown=want;File f=gifs.get(want);int ticket=++gifTicket,load=epoch;
        IO.execute(()->{try{if(closed)return;Decoded d=decode(ProfileLibrary.bytes(f,dev.jun.gyeot.core.ProfileMotion.GIF_LIMIT));main.post(()->{if(closed||load!=epoch||ticket!=gifTicket){if(d.animation instanceof Animatable a)a.stop();return;}stop();animated=d.animation;still=d.first;displayed=null;sync();});}catch(Exception|OutOfMemoryError ignored){}});
    }
    public void close(){if(closed)return;closed=true;epoch++;resumed=false;main.removeCallbacks(calm);main.removeCallbacks(doze);synchronized(LIVE){LIVE.remove(this);}gifs=null;unwatch();stop();if(view!=null){view.removeOnAttachStateChangeListener(attachment);view.setImageDrawable(null);}view=null;animated=null;still=null;displayed=null;avatar=null;}
    private void watch(){if(view!=null&&view.getViewTreeObserver().isAlive()){view.getViewTreeObserver().removeOnPreDrawListener(visibility);view.getViewTreeObserver().addOnPreDrawListener(visibility);}}
    private void unwatch(){if(view!=null&&view.getViewTreeObserver().isAlive())view.getViewTreeObserver().removeOnPreDrawListener(visibility);}
    private void stop(){if(animated instanceof Animatable a)a.stop();if(avatar!=null)avatar.stop();}
    private void sync(){if(closed||view==null)return;boolean run=resumed&&view.isAttachedToWindow()&&view.isShown()&&view.getWindowVisibility()==View.VISIBLE&&view.getGlobalVisibleRect(new Rect())&&android.animation.ValueAnimator.areAnimatorsEnabled();
        if(avatar!=null){if(view.getDrawable()!=avatar){displayed=null;view.setImageDrawable(avatar);}view.setContentDescription(name+" · 앱 비서 프로필 · 살아있는 아바타 "+avatar.name());if(run)avatar.start();else avatar.stop();return;}
        Drawable wanted=run&&animated!=null?animated:still;
        if(wanted==null)wanted=context.getDrawable(R.drawable.ic_sparkle);
        if(displayed==null||displayed.inner!=wanted){stop();displayed=new Circle(wanted,gifShown!=null&&wanted!=baseStill&&wanted!=baseAnimated?GIF_ZOOM:1f);view.setImageDrawable(displayed);}
        view.setContentDescription(name+" · 앱 비서 프로필");if(run&&animated instanceof Animatable a){if(!a.isRunning())a.start();}else stop();
    }
    static final class Circle extends Drawable implements Drawable.Callback {
        final Drawable inner; final Path path=new Path(); final float zoom;
        Circle(Drawable inner){this(inner,1f);}
        /** zoom > 1 crops into the middle: expression GIFs keep a margin of their own, and the face should fill the circle. */
        Circle(Drawable inner,float zoom){this.inner=inner;this.zoom=Math.max(1f,zoom);if(inner!=null)inner.setCallback(this);}
        public void draw(Canvas canvas){if(inner==null)return;Rect b=getBounds();float r=Math.min(b.width(),b.height())/2f;path.reset();path.addCircle(b.exactCenterX(),b.exactCenterY(),r,Path.Direction.CW);int save=canvas.save();canvas.clipPath(path);int w=Math.max(1,inner.getIntrinsicWidth()),h=Math.max(1,inner.getIntrinsicHeight());float s=Math.max(b.width()/(float)w,b.height()/(float)h)*zoom;int tw=Math.round(w*s),th=Math.round(h*s);inner.setBounds(b.centerX()-tw/2,b.centerY()-th/2,b.centerX()-tw/2+tw,b.centerY()-th/2+th);inner.draw(canvas);canvas.restoreToCount(save);}
        public void setAlpha(int a){if(inner!=null)inner.setAlpha(a);}public void setColorFilter(ColorFilter f){if(inner!=null)inner.setColorFilter(f);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
        public void invalidateDrawable(Drawable who){invalidateSelf();}public void scheduleDrawable(Drawable who,Runnable what,long when){scheduleSelf(what,when);}public void unscheduleDrawable(Drawable who,Runnable what){unscheduleSelf(what);}
    }
    static class Request {private boolean cancelled; synchronized void cancel(){cancelled=true;}synchronized boolean cancelled(){return cancelled;}synchronized void finish(AtomicFile file,FileOutputStream out)throws IOException{check();file.finishWrite(out);}void check()throws IOException{if(cancelled()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("취소했어요. 기존 프로필은 그대로예요.");}}
    record Saved(String name,byte[] bytes){}
    static Saved read(Context c)throws IOException{synchronized(STORE){AtomicFile f=new AtomicFile(file(c));if(!f.getBaseFile().exists())return new Saved("들퍼레",new byte[0]);try(DataInputStream in=new DataInputStream(f.openRead())){if(in.readInt()!=MAGIC)throw new IOException("프로필 저장 형식 오류");String n=validName(in.readUTF());int size=in.readInt();if(size<0||size>MAX_BYTES)throw new IOException("프로필 저장 크기 오류");byte[] b=in.readNBytes(size);if(b.length!=size||in.read()!=-1)throw new IOException("프로필 저장이 불완전해요.");return new Saved(n,b);}}}
    static void save(Context c,String name,byte[] replacement,Request request)throws IOException{
        String n=validName(name);request.check();if(replacement!=null){validate(replacement);request.check();}
        synchronized(STORE){request.check();byte[] image=replacement!=null?replacement:read(c).bytes;AtomicFile target=new AtomicFile(file(c));FileOutputStream out=null;try{out=target.startWrite();DataOutputStream data=new DataOutputStream(out);data.writeInt(MAGIC);data.writeUTF(n);data.writeInt(image.length);for(int p=0;p<image.length;p+=65536){request.check();data.write(image,p,Math.min(65536,image.length-p));}data.flush();request.finish(target,out);out=null;}finally{if(out!=null)target.failWrite(out);}}
    }
    static byte[] importImage(Context c,Uri uri,Request request)throws IOException{
        request.check();SharedOutputFolder.requireContentAccess(c,uri);ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream in=c.getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("사진을 열지 못했어요.");byte[] b=new byte[65536];while(true){request.check();SharedOutputFolder.requireContentAccess(c,uri);int count=in.read(b,0,Math.min(b.length,MAX_BYTES-out.size()+1));if(count<0)break;if(count==0)continue;if(out.size()+count>MAX_BYTES)throw new IOException("프로필 파일은 8MB 이하만 사용할 수 있어요.");out.write(b,0,count);}}
        request.check();SharedOutputFolder.requireContentAccess(c,uri);byte[] data=out.toByteArray();validate(data);request.check();return data;
    }
    /**
     * A generated picture as a profile image: its centered square, at most 512px, as PNG, then checked like any imported
     * profile. Large provider images become a small file; the stored profile stays within MAX_BYTES.
     */
    static byte[] avatar(byte[] raw)throws IOException{
        if(raw==null||raw.length<12||raw.length>64*1024*1024)throw invalid();
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(raw,0,raw.length,bounds);
        if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>8192||bounds.outHeight>8192)throw new IOException("그림을 해석하지 못했어요.");
        BitmapFactory.Options options=new BitmapFactory.Options();int sample=1;while(Math.min(bounds.outWidth,bounds.outHeight)/(sample*2)>=TARGET)sample*=2;options.inSampleSize=sample;
        Bitmap source=BitmapFactory.decodeByteArray(raw,0,raw.length,options);if(source==null)throw new IOException("그림을 해석하지 못했어요.");
        Set<Bitmap> made=Collections.newSetFromMap(new IdentityHashMap<>());made.add(source);
        try{
            int side=Math.min(source.getWidth(),source.getHeight());
            Bitmap square=Bitmap.createBitmap(source,(source.getWidth()-side)/2,(source.getHeight()-side)/2,side,side);made.add(square);
            int target=Math.min(TARGET,side);Bitmap scaled=Bitmap.createScaledBitmap(square,target,target,true);made.add(scaled);
            ByteArrayOutputStream out=new ByteArrayOutputStream();if(!scaled.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("그림을 저장하지 못했어요.");
            byte[] png=out.toByteArray();inspect(png);return png;
        }finally{for(Bitmap b:made)b.recycle();}
    }
    record Decoded(Drawable first,Drawable animation){}
    private static void validate(byte[] bytes)throws IOException{Decoded d=decode(bytes);if(d.animation instanceof Animatable a)a.stop();((BitmapDrawable)d.first).getBitmap().recycle();}
    static Decoded decode(byte[] bytes)throws IOException{
        Info info=inspect(bytes);ImageDecoder.OnHeaderDecodedListener header=(decoder,image,source)->{int w=image.getSize().getWidth(),h=image.getSize().getHeight();if(w<1||h<1||w>4096||h>4096||(info.animated&&(w>1024||h>1024)))throw new IllegalArgumentException("프로필 이미지 크기 제한");double scale=Math.min(1d,TARGET/(double)Math.max(w,h));decoder.setTargetSize(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);decoder.setOnPartialImageListener(error->false);};
        try{Bitmap first=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)),header);if(first==null)throw new IOException("사진을 해석하지 못했어요.");Drawable animation=null;if(info.animated){validateWebpFrames(bytes,header);animation=ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)),header);if(!(animation instanceof AnimatedImageDrawable))throw new IOException("움직이는 이미지 형식을 확인하지 못했어요.");((AnimatedImageDrawable)animation).stop();}return new Decoded(new BitmapDrawable(null,first),animation);}catch(RuntimeException|OutOfMemoryError e){throw new IOException("손상되었거나 너무 큰 이미지예요. 기존 프로필은 그대로예요.",e);}
    }
    record Info(String format,int width,int height,int frames,boolean animated){}
    static Info inspect(byte[] b)throws IOException{
        if(b==null||b.length<12||b.length>MAX_BYTES)throw invalid();Info x;
        if(u(b,0)==137&&ascii(b,1,3).equals("PNG"))x=png(b);else if(u(b,0)==255&&u(b,1)==216)x=jpeg(b);else if(ascii(b,0,6).equals("GIF87a")||ascii(b,0,6).equals("GIF89a"))x=gif(b);else if(ascii(b,0,4).equals("RIFF")&&ascii(b,8,4).equals("WEBP"))x=webp(b);else throw new IOException("PNG·JPEG·GIF·WebP 사진만 사용할 수 있어요. 동영상은 지원하지 않아요.");
        int cap=x.animated?1024:4096;if(x.width<1||x.height<1||x.width>cap||x.height>cap||x.frames<1||x.frames>MAX_FRAMES||(x.animated&&(long)x.width*x.height*4*x.frames>FRAME_BYTES))throw new IOException("이미지 크기나 프레임 수가 너무 커요. 더 작은 사진을 선택해 주세요.");return x;
    }
    private static IOException invalid(){return new IOException("손상되었거나 지원하지 않는 이미지예요.");}
    private static int u(byte[] b,int i)throws IOException{if(i<0||i>=b.length)throw invalid();return b[i]&255;}
    private static int le16(byte[] b,int p)throws IOException{return u(b,p)|(u(b,p+1)<<8);}private static int le24(byte[] b,int p)throws IOException{return le16(b,p)|(u(b,p+2)<<16);}
    private static long le32(byte[] b,int p)throws IOException{return Integer.toUnsignedLong(le24(b,p)|(u(b,p+3)<<24));}
    private static long be32(byte[] b,int p)throws IOException{return ((long)u(b,p)<<24)|((long)u(b,p+1)<<16)|((long)u(b,p+2)<<8)|u(b,p+3);}
    private static String ascii(byte[] b,int p,int n)throws IOException{if(p<0||n<0||p>b.length-n)throw invalid();return new String(b,p,n,StandardCharsets.ISO_8859_1);}
    private static int end(byte[] b,int p,long n)throws IOException{if(n<0||n>b.length-p)throw invalid();return p+(int)n;}
    private static Info png(byte[] b)throws IOException{if(!Arrays.equals(Arrays.copyOf(b,8),new byte[]{(byte)137,80,78,71,13,10,26,10}))throw invalid();int p=8,w=0,h=0;boolean data=false;while(p<b.length){long n=be32(b,p);String type=ascii(b,p+4,4);int e=end(b,p+8,n);end(b,e,4);CRC32 crc=new CRC32();crc.update(b,p+4,(int)n+4);if(crc.getValue()!=be32(b,e))throw invalid();if(p==8){if(!type.equals("IHDR")||n!=13)throw invalid();long a=be32(b,p+8),z=be32(b,p+12);if(a>4096||z>4096)throw invalid();w=(int)a;h=(int)z;}if(type.equals("acTL"))throw invalid();if(type.equals("IDAT"))data=true;if(type.equals("IEND")){if(n!=0||e+4!=b.length||!data)throw invalid();return new Info("PNG",w,h,1,false);}p=e+4;}throw invalid();}
    private static Info jpeg(byte[] b)throws IOException{int p=2,w=0,h=0;while(p<b.length){if(u(b,p++)!=255)throw invalid();while(u(b,p)==255)p++;int marker=u(b,p++);if(marker==217)break;if(marker==218){if(w==0||u(b,b.length-2)!=255||u(b,b.length-1)!=217)throw invalid();return new Info("JPEG",w,h,1,false);}if(marker==1||(marker>=208&&marker<=215))continue;int n=(u(b,p)<<8)|u(b,p+1);if(n<2)throw invalid();int e=end(b,p,n);if((marker>=192&&marker<=195)||(marker>=197&&marker<=199)||(marker>=201&&marker<=203)||(marker>=205&&marker<=207)){h=(u(b,p+3)<<8)|u(b,p+4);w=(u(b,p+5)<<8)|u(b,p+6);}p=e;}throw invalid();}
    private static int blocks(byte[] b,int p,ByteArrayOutputStream out)throws IOException{while(true){int n=u(b,p++);if(n==0)return p;int e=end(b,p,n);if(out!=null)out.write(b,p,n);p=e;}}
    private static Info gif(byte[] b)throws IOException{int w=le16(b,6),h=le16(b,8),flags=u(b,10),p=13,frames=0;if((flags&128)!=0)p=end(b,p,3L*(1<<((flags&7)+1)));while(p<b.length){int marker=u(b,p++);if(marker==59){if(p!=b.length||frames==0)throw invalid();return new Info("GIF",w,h,frames,frames>1);}if(marker==33){p++;p=blocks(b,p,null);continue;}if(marker!=44)throw invalid();int x=le16(b,p),y=le16(b,p+2),fw=le16(b,p+4),fh=le16(b,p+6);flags=u(b,p+8);p+=9;if(fw<1||fh<1||x+fw>w||y+fh>h||++frames>MAX_FRAMES||(long)w*h*4*frames>FRAME_BYTES)throw invalid();if((flags&128)!=0)p=end(b,p,3L*(1<<((flags&7)+1)));int minimum=u(b,p++);ByteArrayOutputStream compressed=new ByteArrayOutputStream();p=blocks(b,p,compressed);gifLzw(compressed.toByteArray(),minimum,(long)fw*fh);}throw invalid();}
    private static void gifLzw(byte[] b,int min,long expected)throws IOException{if(min<2||min>8)throw invalid();int clear=1<<min,eoi=clear+1,next=clear+2,width=min+1,old=-1,bit=0;long count=0;int[] lengths=new int[4096];Arrays.fill(lengths,0,clear,1);boolean reset=false;while(bit+width<=b.length*8){int code=0;for(int i=0;i<width;i++)code|=((b[(bit+i)/8]>>((bit+i)%8))&1)<<i;bit+=width;if(code==clear){next=clear+2;width=min+1;old=-1;reset=true;continue;}if(!reset)throw invalid();if(code==eoi){if(count!=expected)throw invalid();return;}int len;if(code<next&&code!=clear&&code!=eoi)len=lengths[code];else if(code==next&&old>=0)len=lengths[old]+1;else throw invalid();if(len<1||(count+=len)>expected)throw invalid();if(old>=0&&next<4096){lengths[next++]=lengths[old]+1;if(next==(1<<width)&&width<12)width++;}old=code;}throw invalid();}
    private static Info webp(byte[] b)throws IOException{if(le32(b,4)+8!=b.length)throw invalid();int p=12,w=0,h=0,frames=0;boolean anim=false,still=false;while(p<b.length){String type=ascii(b,p,4);long n=le32(b,p+4);int s=p+8,e=end(b,s,n);if(type.equals("VP8X")){if(p!=12||n!=10)throw invalid();anim=(u(b,s)&2)!=0;w=le24(b,s+4)+1;h=le24(b,s+7)+1;}else if(type.equals("VP8 ")){if(n<10||u(b,s+3)!=157||u(b,s+4)!=1||u(b,s+5)!=42)throw invalid();if(w==0){w=le16(b,s+6)&16383;h=le16(b,s+8)&16383;}still=true;}else if(type.equals("VP8L")){if(n<5||u(b,s)!=47)throw invalid();if(w==0){long bits=le32(b,s+1);w=(int)(bits&16383)+1;h=(int)((bits>>14)&16383)+1;}still=true;}else if(type.equals("ANMF")){if(!anim||n<24)throw invalid();int x=le24(b,s)*2,y=le24(b,s+3)*2,fw=le24(b,s+6)+1,fh=le24(b,s+9)+1;if(x+fw>w||y+fh>h||++frames>MAX_FRAMES||(long)w*h*4*frames>FRAME_BYTES)throw invalid();}p=end(b,e,n&1);}if(p!=b.length||w==0||h==0||(anim?frames==0:!still))throw invalid();return new Info("WebP",w,h,anim?frames:1,anim);}
    private static void validateWebpFrames(byte[] b,ImageDecoder.OnHeaderDecodedListener header)throws IOException {
        if(!ascii(b,0,4).equals("RIFF"))return;
        for(int p=12;p<b.length;){int n=(int)le32(b,p+4),s=p+8;
            if(ascii(b,p,4).equals("ANMF")){
                // A standalone extended WebP needs VP8X before ALPH/VP8 frame subchunks.
                int length=n-16;byte[] frame=new byte[length+30];
                System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII),0,frame,0,4);
                int size=frame.length-8;for(int k=0;k<4;k++)frame[4+k]=(byte)(size>>(k*8));
                System.arraycopy("WEBPVP8X".getBytes(StandardCharsets.US_ASCII),0,frame,8,8);frame[16]=10;
                String chunk=ascii(b,s+16,4);
                boolean alpha=chunk.equals("ALPH")||(chunk.equals("VP8L")&&(le32(b,s+25)&(1L<<28))!=0);
                frame[20]=(byte)(alpha?16:0);
                System.arraycopy(b,s+6,frame,24,3);System.arraycopy(b,s+9,frame,27,3);
                System.arraycopy(b,s+16,frame,30,length);
                Bitmap decoded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(frame)),header);decoded.recycle();
            }p=s+n+(n&1);
        }
    }
}
