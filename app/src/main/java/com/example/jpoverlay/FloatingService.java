package com.example.jpoverlay;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.*;
import android.provider.Settings;
import android.speech.*;
import android.view.*;
import android.widget.*;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.*;
import java.util.ArrayList;

public class FloatingService extends Service {
    private WindowManager wm;
    private TextView subtitle;
    private WindowManager.LayoutParams params;
    private SpeechRecognizer recognizer;
    private Translator translator;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean listening = false, destroyed = false, modelReady = false;
    private String lastJapanese = "";
    private int errorStreak = 0;
    private static final int NOTIFICATION_ID = 103;

    @Override public void onCreate() {
        super.onCreate();
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return; }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        subtitle = new TextView(this);
        subtitle.setText("日语语音：准备中…");
        subtitle.setTextSize(15);
        subtitle.setTextColor(0xffffffff);
        subtitle.setPadding(24, 17, 24, 17);
        subtitle.setBackgroundColor(0xdd172b43);
        subtitle.setMaxLines(5);
        params = new WindowManager.LayoutParams(
            Math.min(getResources().getDisplayMetrics().widthPixels - 35, 820),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 16;
        params.y = 220;
        subtitle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int originX, originY; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    downX=e.getRawX(); downY=e.getRawY(); originX=params.x; originY=params.y; moved=false; return true;
                }
                if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    int dx=(int)(e.getRawX()-downX), dy=(int)(e.getRawY()-downY);
                    if (Math.abs(dx)>12 || Math.abs(dy)>12) moved=true;
                    if (moved) {
                        params.x=Math.max(0,originX+dx); params.y=Math.max(0,originY+dy);
                        try { wm.updateViewLayout(subtitle,params); } catch(Exception ignored) {}
                    }
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    if (!moved && !listening) startMicCheck();
                    return true;
                }
                return false;
            }
        });
        try { wm.addView(subtitle,params); }
        catch (Exception e) { stopSelf(); return; }

        translator = Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.CHINESE).build());
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
            .addOnSuccessListener(done -> { modelReady = true; })
            .addOnFailureListener(err -> status("翻译模型未就绪，请联网后重试："+err.getMessage()));
    }

    private void status(String s) {
        main.post(() -> { if (!destroyed && subtitle != null) subtitle.setText(s); });
    }

    private void enterForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                "japanese_audio", "日语听音状态", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "japanese_audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("日语翻译器正在尝试听音")
            .setContentText("点击可返回翻译器；停止时退出服务")
            .setContentIntent(pi)
            .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void startMicCheck() {
        if (destroyed || listening) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status("麦克风权限未授予；请打开应用设置"); return;
        }
        try { enterForeground(); }
        catch (Exception e) { status("录音前台服务启动失败："+e.getClass().getSimpleName()+" "+e.getMessage()); return; }
        listening = true;
        status("正在独立检测麦克风输入…");
        new Thread(() -> {
            AudioRecord ar = null;
            int peak = 0;
            String problem = null;
            try {
                int min = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) throw new IllegalStateException("音频缓冲区不支持");
                ar = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, 4096));
                if (ar.getState() != AudioRecord.STATE_INITIALIZED)
                    throw new IllegalStateException("AudioRecord 未初始化");
                ar.startRecording();
                if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                    throw new IllegalStateException("麦克风未进入录制状态");
                short[] buffer = new short[1024];
                long finish = System.currentTimeMillis() + 1400;
                while (!destroyed && listening && System.currentTimeMillis() < finish) {
                    int count = ar.read(buffer, 0, buffer.length);
                    if (count < 0) throw new IllegalStateException("麦克风读取错误："+count);
                    for (int i=0;i<count;i++) peak=Math.max(peak,Math.abs((int)buffer[i]));
                }
            } catch (Exception e) {
                problem = e.getClass().getSimpleName()+"："+e.getMessage();
            } finally {
                if (ar != null) {
                    try { if (ar.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING) ar.stop(); }
                    catch(Exception ignored) {}
                    ar.release();
                }
            }
            final int measuredPeak=peak;
            final String failure=problem;
            main.post(() -> {
                if (!listening || destroyed) return;
                if (failure != null) {
                    listening=false;
                    status("麦克风录制失败："+failure);
                    return;
                }
                status("麦克风录制成功，音量峰值 "+measuredPeak+"\n正在连接日语识别服务…");
                setupRecognizer();
            });
        }, "mic-signal-check").start();
    }

    private void setupRecognizer() {
        if (!listening || destroyed) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            listening=false;
            status("麦克风采集成功，但系统没有可用语音识别服务。\n搜狗语音输入不等于安卓系统语音识别。");
            return;
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) { errorStreak=0; status("🎙 日语听音中（麦克风）…"); }
                @Override public void onBeginningOfSpeech() { status("🎙 已检测到说话，正在识别日语…"); }
                @Override public void onRmsChanged(float rms) {}
                @Override public void onBufferReceived(byte[] bytes) {}
                @Override public void onEndOfSpeech() { status("正在将声音转换成日语文字…"); }
                @Override public void onError(int code) {
                    if (!listening || destroyed) return;
                    if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        listening=false; status("语音服务无法使用麦克风：权限错误 "+code); return;
                    }
                    errorStreak++;
                    status("日语语音识别失败："+explainError(code)+"（代码 "+code+"）");
                    retry(code==SpeechRecognizer.ERROR_NO_MATCH || code==SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? 1200 : 3500);
                }
                @Override public void onResults(Bundle b) {
                    if (!listening || destroyed) return;
                    ArrayList<String> results = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (results != null && !results.isEmpty() && !results.get(0).trim().isEmpty()) {
                        errorStreak=0;
                        translate(results.get(0));
                    } else status("本次没有识别到日语");
                    retry(1500);
                }
                @Override public void onPartialResults(Bundle b) {
                    ArrayList<String> results=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (results!=null && !results.isEmpty() && listening) status("日语识别中："+results.get(0));
                }
                @Override public void onEvent(int type,Bundle b) {}
            });
            startRecognition();
        } catch (Exception e) {
            listening=false;
            status("无法创建日语识别器："+e.getClass().getSimpleName()+" "+e.getMessage());
        }
    }

    private String explainError(int c) {
        switch(c) {
            case SpeechRecognizer.ERROR_AUDIO: return "音频获取异常";
            case SpeechRecognizer.ERROR_CLIENT: return "客户端异常";
            case SpeechRecognizer.ERROR_NETWORK: return "网络异常";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "网络超时";
            case SpeechRecognizer.ERROR_NO_MATCH: return "未识别出文字";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "识别服务繁忙";
            case SpeechRecognizer.ERROR_SERVER: return "识别服务器异常";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "未检测到说话";
            default: return "识别引擎返回错误";
        }
    }

    private void translate(String ja) {
        if (ja.equals(lastJapanese)) return;
        lastJapanese=ja;
        status("识别到日语："+ja+"\n正在翻译…");
        if (translator==null || !modelReady) {
            status("识别到日语："+ja+"\n翻译模型还没下载好，请联网等待");
            return;
        }
        translator.translate(ja)
            .addOnSuccessListener(zh -> { if (!destroyed) status("日语："+ja+"\n中文："+zh); })
            .addOnFailureListener(err -> status("日语："+ja+"\n翻译失败："+err.getMessage()));
    }

    private void retry(long millis) {
        main.postDelayed(() -> {
            if (listening && !destroyed && recognizer!=null) startRecognition();
        },millis);
    }

    private void startRecognition() {
        if (!listening || destroyed || recognizer==null) return;
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ja-JP");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recognizer.startListening(intent);
            status("🎙 日语识别服务启动中…");
        } catch(Exception e) {
            status("日语听音启动失败："+e.getMessage());
            retry(3500);
        }
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        String action=i==null? "START" : i.getAction();
        if ("STOP".equals(action)) { stopSelf(); return START_NOT_STICKY; }
        if (!listening) startMicCheck();
        return START_NOT_STICKY;
    }

    @Override public android.os.IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        destroyed=true; listening=false;
        main.removeCallbacksAndMessages(null);
        if (recognizer!=null) { recognizer.cancel(); recognizer.destroy(); recognizer=null; }
        if (translator!=null) { translator.close(); translator=null; }
        if (wm!=null && subtitle!=null) { try { wm.removeView(subtitle); } catch(Exception ignored) {} }
        super.onDestroy();
    }
}