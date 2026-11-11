package com.example.jpoverlay;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.graphics.PixelFormat;
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

public class FloatingService extends Service {
    private static final int NOTIFICATION_ID = 103;
    private static final String CHANNEL = "japanese_audio";
    private static volatile FloatingService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private volatile boolean destroyed = false;
    private volatile AudioRecord audioRecord;
    private volatile long recentResult = 0L;
    private volatile boolean modelReady = false;
    private WindowManager wm;
    private TextView subtitle;
    private WindowManager.LayoutParams params;
    private Translator translator;
    private Thread worker;
    private String lastJa = "";

    public static void showText(String text) {
        FloatingService current = instance;
        if (current != null && !current.destroyed) current.status(text);
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return; }
        subtitle = new TextView(this);
        subtitle.setText("正在启动日语听音…");
        subtitle.setTextSize(16);
        subtitle.setTextColor(0xffffffff);
        subtitle.setPadding(25, 18, 25, 18);
        subtitle.setBackgroundColor(0xe91b293f);
        subtitle.setMaxLines(5);
        params = new WindowManager.LayoutParams(
            Math.max(250, Math.min(790, getResources().getDisplayMetrics().widthPixels - 36)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x=16; params.y=180;
        subtitle.setOnTouchListener(new View.OnTouchListener() {
            float downX,downY; int baseX,baseY; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch(e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downX=e.getRawX(); downY=e.getRawY(); baseX=params.x; baseY=params.y; moved=false; return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx=(int)(e.getRawX()-downX),dy=(int)(e.getRawY()-downY);
                        if (Math.abs(dx)>12 || Math.abs(dy)>12) moved=true;
                        if (moved) {
                            params.x=Math.max(0,baseX+dx);params.y=Math.max(0,baseY+dy);
                            try {wm.updateViewLayout(subtitle,params);}catch(Exception ignored){}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if(!moved && !running) startListening();return true;
                }
                return false;
            }
        });
        try {wm.addView(subtitle,params);}catch(Exception e){stopSelf();return;}
        translator = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.CHINESE).build());
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
            .addOnSuccessListener(x -> modelReady = true)
            .addOnFailureListener(err -> status("翻译模型下载失败（听音仍可使用）："+err.getMessage()));
    }

    private void status(final String value) {
        main.post(() -> { if(!destroyed && subtitle!=null) subtitle.setText(value); });
    }

    private void startAsForeground() {
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(
            new NotificationChannel(CHANNEL,"日语语音翻译",NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("日语语音翻译正在运行")
            .setContentText("手机麦克风持续识别日语；可返回应用关闭")
            .setContentIntent(pi).setOngoing(true).build();
        if(Build.VERSION.SDK_INT>=29)
            startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(NOTIFICATION_ID,n);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && "STOP".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(!running) startListening();
        return START_NOT_STICKY;
    }

    private synchronized void startListening() {
        if(running || destroyed) return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            status("麦克风权限不足，请回到软件授予录音权限");return;
        }
        try {startAsForeground();}
        catch(Exception e){status("麦克风前台服务失败："+e.getMessage());return;}
        running=true;
        status("正在加载独立日语离线识别模型…\n无需 Google 语音服务");
        worker=new Thread(this::runRecognition,"JapaneseOfflineRecognizer");
        worker.start();
    }

    private File installModel() throws IOException {
        File destination=new File(getFilesDir(),"vosk-ja-model");
        File marker=new File(destination,".complete");
        if(marker.isFile() && new File(destination,"conf/model.conf").exists()) return destination;
        if(destination.exists()) removeRecursively(destination);
        if(!destination.mkdirs() && !destination.isDirectory())
            throw new IOException("无法创建模型目录");
        copyAssetDirectory(getAssets(),"model-ja",destination);
        if(!new File(destination,"conf/model.conf").exists())
            throw new IOException("安装包中缺少日语识别模型，请检查编译步骤");
        if(!marker.createNewFile()) throw new IOException("模型完成标记写入失败");
        return destination;
    }

    private void removeRecursively(File file) throws IOException {
        if(file.isDirectory()) {
            File[] list=file.listFiles();
            if(list!=null)for(File child:list)removeRecursively(child);
        }
        if(!file.delete())throw new IOException("旧模型清理失败："+file.getName());
    }

    private void copyAssetDirectory(AssetManager manager,String assetPath,File destination) throws IOException {
        String[] entries=manager.list(assetPath);
        if(entries==null || entries.length==0) {
            try(InputStream in=manager.open(assetPath);
                OutputStream out=new BufferedOutputStream(new FileOutputStream(destination))) {
                byte[] buffer=new byte[16384];int n;
                while((n=in.read(buffer))!=-1) out.write(buffer,0,n);
            }
            return;
        }
        if(!destination.exists() && !destination.mkdirs())
            throw new IOException("无法创建模型文件夹："+destination.getName());
        for(String entry:entries) {
            String path=assetPath+"/"+entry;
            String[] sub=manager.list(path);
            File target=new File(destination,entry);
            if(sub!=null && sub.length>0) copyAssetDirectory(manager,path,target);
            else try(InputStream in=manager.open(path);
                     OutputStream out=new BufferedOutputStream(new FileOutputStream(target))) {
                byte[] buf=new byte[16384];int n;
                while((n=in.read(buf))!=-1)out.write(buf,0,n);
            }
        }
    }

    private void runRecognition() {
        AudioRecord localRecord=null;
        try {
            File modelPath=installModel();
            if(!running)return;
            status("正在初始化日语识别引擎…");
            try(Model model=new Model(modelPath.getAbsolutePath());
                Recognizer recognizer=new Recognizer(model,16000.0f)) {
                int minBuffer=AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if(minBuffer<=0)throw new IOException("麦克风不支持 16kHz PCM");
                localRecord=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuffer,8192));
                audioRecord=localRecord;
                if(localRecord.getState()!=AudioRecord.STATE_INITIALIZED)
                    throw new IOException("麦克风 AudioRecord 初始化失败");
                localRecord.startRecording();
                if(localRecord.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)
                    throw new IOException("麦克风未进入真实录制状态（可能被其他 App 占用）");
                status("🎙 麦克风已实际开始录音\n正在独立识别日语；不依赖搜狗或 Google");
                short[] buffer=new short[2048];
                long lastProbe=System.currentTimeMillis();
                long lastPartial=0;
                int peak=0;
                while(running && !destroyed) {
                    int n=localRecord.read(buffer,0,buffer.length);
                    if(n<0)throw new IOException("麦克风读取失败："+n);
                    for(int i=0;i<n;i++)peak=Math.max(peak,Math.abs((int)buffer[i]));
                    if(recognizer.acceptWaveForm(buffer,n)){
                        String result=new JSONObject(recognizer.getResult()).optString("text","").trim();
                        if(!result.isEmpty())translate(result);
                    }else if(System.currentTimeMillis()-lastPartial>1100){
                        String partial=new JSONObject(recognizer.getPartialResult()).optString("partial","").trim();
                        if(!partial.isEmpty()){
                            recentResult=System.currentTimeMillis();
                            status("🎙 识别中（日语）："+partial);
                        }
                        lastPartial=System.currentTimeMillis();
                    }
                    if(System.currentTimeMillis()-lastProbe>=3500){
                        if(System.currentTimeMillis()-recentResult>3500)
                            status("🎙 麦克风已录制 | 声音峰值 "+peak+"\n等待日语讲话…");
                        peak=0;lastProbe=System.currentTimeMillis();
                    }
                }
                if(running){
                    String last=new JSONObject(recognizer.getFinalResult()).optString("text","").trim();
                    if(!last.isEmpty())translate(last);
                }
            }
        } catch(Throwable e){
            if(running&&!destroyed){
                status("独立听音失败："+e.getClass().getSimpleName()+
                    "\n"+(e.getMessage()==null?"请检查模型和录音服务":e.getMessage()));
                running=false;
            }
        } finally {
            if(localRecord!=null){
                try{if(localRecord.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)
                    localRecord.stop();}catch(Exception ignored){}
                try{localRecord.release();}catch(Exception ignored){}
            }
            audioRecord=null;
        }
    }

    private synchronized void translate(String ja){
        if(ja.isEmpty() || ja.equals(lastJa))return;
        lastJa=ja;
        recentResult=System.currentTimeMillis();
        status("已识别日语："+ja+"\n翻译中…");
        if(!modelReady || translator==null){
            status("已识别日语："+ja+"\n中文翻译模型尚未准备好，请联网下载");
            return;
        }
        translator.translate(ja)
            .addOnSuccessListener(zh->status("日语："+ja+"\n中文："+zh))
            .addOnFailureListener(e->status("已识别日语："+ja+"\n翻译错误："+e.getMessage()));
    }

    @Override public IBinder onBind(Intent intent){return null;}

    @Override public void onDestroy(){
        destroyed=true;running=false;instance=null;
        AudioRecord rec=audioRecord;
        if(rec!=null)try{rec.stop();}catch(Exception ignored){}
        if(worker!=null)worker.interrupt();
        main.removeCallbacksAndMessages(null);
        if(translator!=null){translator.close();translator=null;}
        if(wm!=null && subtitle!=null)try{wm.removeView(subtitle);}catch(Exception ignored){}
        super.onDestroy();
    }
}