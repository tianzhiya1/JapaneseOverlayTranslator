package com.example.jpoverlay;
import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.speech.*;
import android.view.*;
import android.widget.*;
import com.google.mlkit.nl.translate.*;
import com.google.mlkit.common.model.DownloadConditions;
import java.util.ArrayList;
public class FloatingService extends Service {
 WindowManager wm; TextView ball,panel; WindowManager.LayoutParams bp;
 SpeechRecognizer speech; Handler handler=new Handler(Looper.getMainLooper()); boolean listening=false, destroyed=false;
 Translator translator;
 @Override public void onCreate(){super.onCreate();if(!Settings.canDrawOverlays(this)){stopSelf();return;}
 wm=(WindowManager)getSystemService(WINDOW_SERVICE);
 translator=Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.JAPANESE).setTargetLanguage(TranslateLanguage.CHINESE).build());
 translator.downloadModelIfNeeded(new DownloadConditions.Builder().build());
 ball=new TextView(this);ball.setText("译");ball.setTextSize(22);ball.setGravity(Gravity.CENTER);ball.setTextColor(0xffffffff);ball.setBackgroundColor(0xff3276d3);
 bp=new WindowManager.LayoutParams(140,140,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);bp.gravity=Gravity.TOP|Gravity.LEFT;bp.x=20;bp.y=400;
 ball.setOnTouchListener(new View.OnTouchListener(){float sx,sy;int ox,oy;boolean moved;public boolean onTouch(View v,MotionEvent e){switch(e.getAction()){case MotionEvent.ACTION_DOWN:sx=e.getRawX();sy=e.getRawY();ox=bp.x;oy=bp.y;moved=false;return true;case MotionEvent.ACTION_MOVE:bp.x=ox+(int)(e.getRawX()-sx);bp.y=oy+(int)(e.getRawY()-sy);moved|=Math.abs(e.getRawX()-sx)>12||Math.abs(e.getRawY()-sy)>12;wm.updateViewLayout(ball,bp);return true;case MotionEvent.ACTION_UP:if(!moved)togglePanel();return true;}return false;}});
 wm.addView(ball,bp);
 }
 void togglePanel(){if(panel!=null){wm.removeView(panel);panel=null;return;}showPanel("日语翻译器 v0.3\n\n使用软件内的“启动日语听音”按钮，开启麦克风识别。\n\n点击这里关闭面板。");}
 void showPanel(String message){if(wm==null)return;if(panel!=null){try{wm.removeView(panel);}catch(Exception ignored){}panel=null;}
 panel=new TextView(this);panel.setText(message);panel.setTextSize(17);panel.setTextColor(0xff111111);panel.setPadding(28,28,28,28);panel.setBackgroundColor(0xfff9f4df);panel.setOnClickListener(v->togglePanel());
 WindowManager.LayoutParams pp=new WindowManager.LayoutParams(Math.min(700,getResources().getDisplayMetrics().widthPixels-40),-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);pp.gravity=Gravity.TOP|Gravity.LEFT;pp.x=20;pp.y=180;wm.addView(panel,pp);}
 void startListening(){if(android.os.Build.VERSION.SDK_INT>=23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){showPanel("请返回软件授予麦克风权限");return;}
 if(!SpeechRecognizer.isRecognitionAvailable(this)){showPanel("手机没有可用的语音识别服务。请检查 Google 语音服务。");return;}
 listening=true;if(speech==null){speech=SpeechRecognizer.createSpeechRecognizer(this);speech.setRecognitionListener(new RecognitionListener(){
 public void onReadyForSpeech(Bundle b){}public void onBeginningOfSpeech(){}public void onRmsChanged(float rms){}public void onBufferReceived(byte[] b){}public void onEndOfSpeech(){}
 public void onError(int e){if(listening){showPanel("听音等待中（识别状态："+e+"），即将重试");retry();}}
 public void onResults(Bundle b){ArrayList<String> r=b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(r!=null&&!r.isEmpty()){String ja=r.get(0);showPanel("日语："+ja+"\n\n正在翻译……");translator.translate(ja).addOnSuccessListener(zh->showPanel("日语："+ja+"\n\n中文："+zh)).addOnFailureListener(err->showPanel("日语："+ja+"\n\n翻译失败，请联网下载模型："+err.getMessage()));}retry();}
 public void onPartialResults(Bundle b){}public void onEvent(int type,Bundle b){}
 });}recognize();}
 void recognize(){if(!listening||destroyed||speech==null)return;Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ja-JP");i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);try{speech.startListening(i);}catch(Exception e){showPanel("语音识别启动失败："+e.getMessage());retry();}}
 void retry(){handler.postDelayed(()->{if(listening&&speech!=null){speech.cancel();recognize();}},1200);}
 void stopListening(){listening=false;handler.removeCallbacksAndMessages(null);if(speech!=null){speech.cancel();speech.destroy();speech=null;}showPanel("已停止日语听音");}
 @Override public int onStartCommand(Intent i,int f,int id){if(i!=null){String action=i.getAction();if("LISTEN".equals(action))startListening();else if("STOP_LISTEN".equals(action))stopListening();}return START_STICKY;}
 @Override public android.os.IBinder onBind(Intent i){return null;}
 @Override public void onDestroy(){destroyed=true;listening=false;handler.removeCallbacksAndMessages(null);if(speech!=null)speech.destroy();if(translator!=null)translator.close();if(panel!=null)wm.removeView(panel);if(ball!=null)wm.removeView(ball);super.onDestroy();}
}
