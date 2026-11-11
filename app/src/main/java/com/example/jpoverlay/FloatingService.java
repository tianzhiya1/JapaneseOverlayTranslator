package com.example.jpoverlay;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.*;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;

public class FloatingService extends Service {
    private static final int NOTIFICATION_ID=103;
    private static volatile FloatingService instance;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicInteger latestTranslation=new AtomicInteger();
    private volatile boolean running=false, destroyed=false;
    private volatile boolean jaReady=false, enReady=false;
    private volatile AudioRecord recorder;
    private WindowManager wm;
    private TextView subtitle, bubble;
    private WindowManager.LayoutParams bubbleParams;
    private Translator japanese, english;
    private Thread worker;
    private String previousRequest="";
    private long lastRequestAt=0;
    private Runnable clearSubtitle;
    private volatile long screenshotPriorityUntil=0;
    private final Object translationMutex=new Object();
    private boolean translationInFlight=false;
    private PendingTranslation queuedTranslation=null;
    private volatile int activeTranslation=0;

    private static final class PendingTranslation {
        final String text;
        final boolean japanese, screenshot;
        PendingTranslation(String t,boolean j,boolean s){text=t;japanese=j;screenshot=s;}
    }

    public static void showText(String msg) {
        FloatingService s=instance;
        if(s!=null && !s.destroyed)s.status(msg);
    }

    public static void translateScreenshot(String source, boolean isJapanese) {
        FloatingService s=instance;
        if(s!=null && !s.destroyed)s.translateText(source,isJapanese,true);
    }

    public static void restoreOverlays() {
        FloatingService s=instance;
        if(s!=null && !s.destroyed)s.main.post(()->{
            if(s.bubble!=null)s.bubble.setVisibility(View.VISIBLE);
            if(s.subtitle!=null)s.subtitle.setVisibility(View.VISIBLE);
        });
    }

    @Override public void onCreate() {
        super.onCreate();
        instance=this;
        if(!Settings.canDrawOverlays(this)){stopSelf();return;}
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        makeOverlays();
        japanese=Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.CHINESE).build());
        english=Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.CHINESE).build());
        DownloadConditions download=new DownloadConditions.Builder().build();
        japanese.downloadModelIfNeeded(download)
            .addOnSuccessListener(ok->jaReady=true)
            .addOnFailureListener(e->status("日语翻译模型下载失败："+e.getMessage()));
        english.downloadModelIfNeeded(download)
            .addOnSuccessListener(ok->enReady=true)
            .addOnFailureListener(e->status("英语翻译模型下载失败："+e.getMessage()));
    }

    private GradientDrawable bg(int color,int corner) {
        GradientDrawable g=new GradientDrawable();
        g.setColor(color);g.setCornerRadius(corner);return g;
    }

    private void makeOverlays() {
        subtitle=new TextView(this);
        subtitle.setText("正在准备中文翻译…");
        subtitle.setTextSize(18);subtitle.setTextColor(0xffffffff);
        subtitle.setPadding(24,15,24,15);
        subtitle.setMaxLines(3);
        subtitle.setBackground(bg(0xdd101b2c,18));
        WindowManager.LayoutParams sp=new WindowManager.LayoutParams(
            Math.max(250,getResources().getDisplayMetrics().widthPixels-48),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        sp.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;
        sp.y=140;
        wm.addView(subtitle,sp);

        bubble=new TextView(this);
        bubble.setText("译");bubble.setTextSize(21);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextColor(0xffffffff);
        bubble.setBackground(bg(0xff2275c7,100));
        bubbleParams=new WindowManager.LayoutParams(
            124,124,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        bubbleParams.gravity=Gravity.TOP|Gravity.LEFT;
        bubbleParams.x=18;bubbleParams.y=400;
        final Handler gesture=new Handler(Looper.getMainLooper());
        final boolean[] longPressed={false},moved={false};
        final float[] px={0},py={0};
        final int[] bx={0},by={0};
        final Runnable hold=()->{longPressed[0]=true;beginSelection();};
        bubble.setOnTouchListener((v,e)->{
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                longPressed[0]=false;moved[0]=false;
                px[0]=e.getRawX();py[0]=e.getRawY();bx[0]=bubbleParams.x;by[0]=bubbleParams.y;
                gesture.postDelayed(hold,650);return true;
            }
            if(e.getAction()==MotionEvent.ACTION_MOVE){
                float dx=e.getRawX()-px[0],dy=e.getRawY()-py[0];
                if(Math.abs(dx)>15||Math.abs(dy)>15){
                    moved[0]=true;gesture.removeCallbacks(hold);
                }
                if(moved[0]&&!longPressed[0]){
                    bubbleParams.x=Math.max(0,bx[0]+(int)dx);
                    bubbleParams.y=Math.max(0,by[0]+(int)dy);
                    try{wm.updateViewLayout(bubble,bubbleParams);}catch(Exception ignored){}
                }
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_UP){
                gesture.removeCallbacks(hold);
                if(!longPressed[0]&&!moved[0]){
                    if(subtitle.getVisibility()==View.VISIBLE)subtitle.setVisibility(View.GONE);
                    else subtitle.setVisibility(View.VISIBLE);
                }
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_CANCEL){gesture.removeCallbacks(hold);return true;}
            return false;
        });
        wm.addView(bubble,bubbleParams);
    }

    private void beginSelection() {
        if(destroyed)return;
        screenshotPriorityUntil=SystemClock.elapsedRealtime()+18000;
        subtitle.setVisibility(View.INVISIBLE);
        bubble.setVisibility(View.INVISIBLE);
        main.postDelayed(()->{
            if(!destroyed)TranslateAccessibilityService.captureForSelection();
        },250);
    }

    private void status(String msg) {
        main.post(()->{
            if(destroyed||subtitle==null)return;
            subtitle.setVisibility(View.VISIBLE);
            subtitle.setText(msg);
        });
    }

    private void subtitleChinese(String text,boolean fromScreenshot){
        main.post(()->{
            if(destroyed||subtitle==null)return;
            subtitle.setVisibility(View.VISIBLE);
            subtitle.setText(text);
            if(clearSubtitle!=null)main.removeCallbacks(clearSubtitle);
            clearSubtitle=()->{
                if(!destroyed&&subtitle!=null&&text.contentEquals(subtitle.getText()))
                    subtitle.setVisibility(View.GONE);
            };
            main.postDelayed(clearSubtitle,fromScreenshot?13000:7500);
        });
    }

    private void startForegroundMic() {
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26)
            nm.createNotificationChannel(new NotificationChannel("japanese_audio",
                "日语语音翻译",NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,"japanese_audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("实时中文字幕正在运行")
            .setContentText("长按悬浮「译」按钮，可框选日文或英文")
            .setContentIntent(pi).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=29)
            startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(NOTIFICATION_ID,n);
    }

    @Override public int onStartCommand(Intent i,int flags,int startId) {
        if(i!=null&&"STOP".equals(i.getAction())){stopSelf();return START_NOT_STICKY;}
        if(!running)startListening();
        return START_NOT_STICKY;
    }

    private synchronized void startListening() {
        if(running||destroyed)return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            status("请授予麦克风权限");return;
        }
        try{startForegroundMic();}
        catch(Exception e){status("录音启动失败："+e.getMessage());return;}
        running=true;
        status("正在启动离线日语听音…");
        worker=new Thread(this::recognitionLoop,"JapaneseVosk");
        worker.start();
    }

    private void recognitionLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        AudioRecord rec=null;
        try{
            File modelDir=installModel();
            if(!running)return;
            try(Model model=new Model(modelDir.getAbsolutePath());
                Recognizer speech=new Recognizer(model,16000f)) {
                int min=AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if(min<=0)throw new IOException("不支持16kHz录音");
                rec=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min,8192));
                recorder=rec;
                if(rec.getState()!=AudioRecord.STATE_INITIALIZED)
                    throw new IOException("麦克风未成功初始化");
                rec.startRecording();
                if(rec.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)
                    throw new IOException("录音未启动（可能被其他软件占用）");
                status("已启动独立日语识别，正在等待声音…");
                short[] buffer=new short[3200];
                long lastPartial=0;
                while(running&&!destroyed){
                    int n=rec.read(buffer,0,buffer.length);
                    if(n<0)throw new IOException("录音读取异常："+n);
                    if(speech.acceptWaveForm(buffer,n)){
                        String result=new JSONObject(speech.getResult()).optString("text","");
                        maybeTranslate(result,true);
                    }else{
                        long now=SystemClock.elapsedRealtime();
                        if(now-lastPartial>=2200){
                            lastPartial=now;
                            String partial=new JSONObject(speech.getPartialResult())
                                .optString("partial","");
                            maybeTranslate(partial,false);
                        }
                    }
                }
            }
        }catch(Throwable e){
            if(running&&!destroyed){
                status("听音异常："+e.getClass().getSimpleName()+": "+e.getMessage());
                running=false;
            }
        }finally{
            if(rec!=null){
                try{if(rec.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)rec.stop();}
                catch(Exception ignored){}
                try{rec.release();}catch(Exception ignored){}
            }
            recorder=null;
        }
    }

    // Vosk Japanese output is often token-separated (e.g. 「私 は 行く」).
    // Strip unnatural spaces before sending it to translation.
    private String normalizeJapanese(String source){
        if(source==null)return "";
        String s=source.trim();
        s=s.replaceAll("(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])\\s+(?=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])","");
        s=s.replaceAll("(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}])\\s+(?=[ぁ-んァ-ン])","");
        return s;
    }

    private void maybeTranslate(String raw,boolean isFinal) {
        if(SystemClock.elapsedRealtime()<screenshotPriorityUntil)return;
        String ja=normalizeJapanese(raw);
        if(ja.length()<3)return;
        if(ja.length()>130)ja=ja.substring(ja.length()-130);
        long now=SystemClock.elapsedRealtime();
        if(!isFinal && (now-lastRequestAt<2400 || ja.length()<5))return;
        if(ja.equals(previousRequest))return;
        previousRequest=ja;lastRequestAt=now;
        translateText(ja,true,false);
    }

    private void translateText(String source,boolean fromJapanese,boolean screenshot){
        String text=source==null?"":source.trim();
        if(text.isEmpty())return;
        if(screenshot)screenshotPriorityUntil=SystemClock.elapsedRealtime()+16000;
        Translator t=fromJapanese?japanese:english;
        if(t==null || !(fromJapanese?jaReady:enReady)){
            if(screenshot)status("翻译模型仍在下载，联网后请重试截图");
            return;
        }
        PendingTranslation request=new PendingTranslation(text,fromJapanese,screenshot);
        synchronized(translationMutex){
            if(translationInFlight){
                if(screenshot || queuedTranslation==null || !queuedTranslation.screenshot)
                    queuedTranslation=request;
                return;
            }
            translationInFlight=true;
        }
        submitTranslation(request);
    }

    private void submitTranslation(PendingTranslation request){
        final Translator t=request.japanese?japanese:english;
        if(t==null || destroyed){finishTranslation(activeTranslation);return;}
        final int generation=latestTranslation.incrementAndGet();
        activeTranslation=generation;
        t.translate(request.text)
            .addOnSuccessListener(zh->{
                if(!destroyed && activeTranslation==generation && !zh.trim().isEmpty())
                    subtitleChinese(zh,request.screenshot);
                finishTranslation(generation);
            })
            .addOnFailureListener(e->{
                if(request.screenshot && activeTranslation==generation)
                    status("选区翻译失败："+e.getMessage());
                finishTranslation(generation);
            });
        main.postDelayed(()->{
            if(!destroyed && activeTranslation==generation){
                if(request.screenshot)status("翻译响应超时，请换短一点的选区");
                finishTranslation(generation);
            }
        },12500);
    }

    private void finishTranslation(int generation){
        PendingTranslation next=null;
        synchronized(translationMutex){
            if(activeTranslation!=generation)return;
            activeTranslation=0;
            if(queuedTranslation!=null){
                next=queuedTranslation;queuedTranslation=null;
                translationInFlight=true;
            }else{
                translationInFlight=false;
            }
        }
        if(next!=null)submitTranslation(next);
    }

    private File installModel()throws IOException {
        File dest=new File(getFilesDir(),"vosk-ja-model");
        if(new File(dest,".complete").isFile()&&new File(dest,"conf/model.conf").isFile())return dest;
        if(dest.exists())removeRecursively(dest);
        if(!dest.mkdirs()&&!dest.isDirectory())throw new IOException("无法创建模型目录");
        copyAssets(getAssets(),"model-ja",dest);
        if(!new File(dest,"conf/model.conf").isFile())throw new IOException("日语模型未打包");
        if(!new File(dest,".complete").createNewFile())throw new IOException("模型写入失败");
        return dest;
    }
    private void removeRecursively(File f)throws IOException{
        if(f.isDirectory()){
            File[] files=f.listFiles();
            if(files!=null)for(File c:files)removeRecursively(c);
        }
        if(!f.delete())throw new IOException("无法删除旧模型");
    }
    private void copyAssets(AssetManager manager,String name,File dest)throws IOException{
        String[] entries=manager.list(name);
        if(entries==null||entries.length==0){
            try(InputStream in=manager.open(name);OutputStream out=new BufferedOutputStream(new FileOutputStream(dest))){
                byte[] b=new byte[16384];int n;
                while((n=in.read(b))!=-1)out.write(b,0,n);
            }
            return;
        }
        if(!dest.exists()&&!dest.mkdirs())throw new IOException("无法创建模型文件夹");
        for(String entry:entries){
            String next=name+"/"+entry;
            String[] inner=manager.list(next);
            File target=new File(dest,entry);
            if(inner!=null&&inner.length>0)copyAssets(manager,next,target);
            else try(InputStream in=manager.open(next);OutputStream out=new BufferedOutputStream(new FileOutputStream(target))){
                byte[] b=new byte[16384];int n;
                while((n=in.read(b))!=-1)out.write(b,0,n);
            }
        }
    }

    @Override public IBinder onBind(Intent i){return null;}

    @Override public void onDestroy(){
        destroyed=true;running=false;instance=null;
        AudioRecord r=recorder;
        if(r!=null)try{r.stop();}catch(Exception ignored){}
        if(worker!=null)worker.interrupt();
        main.removeCallbacksAndMessages(null);
        if(japanese!=null){japanese.close();japanese=null;}
        if(english!=null){english.close();english=null;}
        if(wm!=null&&bubble!=null)try{wm.removeView(bubble);}catch(Exception ignored){}
        if(wm!=null&&subtitle!=null)try{wm.removeView(subtitle);}catch(Exception ignored){}
        super.onDestroy();
    }
}