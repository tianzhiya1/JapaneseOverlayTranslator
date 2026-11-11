package com.example.jpoverlay;
import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.graphics.PixelFormat;
import android.view.*;
import android.widget.*;
import android.content.*;
import com.google.mlkit.nl.translate.*;
import com.google.mlkit.common.model.DownloadConditions;
public class TranslateAccessibilityService extends AccessibilityService {
 private WindowManager wm; private TextView bubble; private Translator translator; private String last="";
 @Override public void onServiceConnected(){
 wm=(WindowManager)getSystemService(WINDOW_SERVICE);
 TranslatorOptions options=new TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.JAPANESE).setTargetLanguage(TranslateLanguage.CHINESE).build();
 translator=Translation.getClient(options);
 translator.downloadModelIfNeeded(new DownloadConditions.Builder().build());
 }
 @Override public void onAccessibilityEvent(AccessibilityEvent e){
 if(e.getPackageName()!=null && e.getPackageName().toString().equals(getPackageName()))return;
 if(e.getEventType()!=AccessibilityEvent.TYPE_VIEW_CLICKED && e.getEventType()!=AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)return;
 AccessibilityNodeInfo n=e.getSource();if(n==null)return;
 CharSequence c=n.getText();if(c==null)return;
 String s=c.toString().trim();
 if(s.length()<2||s.length()>1200||s.equals(last)||!s.matches("(?s).*[\\u3040-\\u30ff\\u3400-\\u9fff].*"))return;
 last=s;
 if(translator==null)return;
 translator.translate(s).addOnSuccessListener(this::showBubble).addOnFailureListener(err->showBubble("翻译暂不可用，请联网完成模型下载："+err.getMessage()));
 }
 private void showBubble(String s){
 if(wm==null||!SettingsCompat.canOverlay(this))return;
 if(bubble!=null){try{wm.removeView(bubble);}catch(Exception ignored){}bubble=null;}
 bubble=new TextView(this);bubble.setText(s+"\n\n点击关闭");bubble.setTextSize(17);
 bubble.setTextColor(0xff111111);bubble.setBackgroundColor(0xeefff7dc);bubble.setPadding(24,20,24,20);
 bubble.setOnClickListener(v->{if(bubble!=null){wm.removeView(bubble);bubble=null;}});
 WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
 p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.y=120;
 try{wm.addView(bubble,p);}catch(Exception ignored){}
 }
 @Override public void onInterrupt(){}
 @Override public void onDestroy(){if(bubble!=null){try{wm.removeView(bubble);}catch(Exception ignored){}}if(translator!=null)translator.close();super.onDestroy();}
 static class SettingsCompat {static boolean canOverlay(Context c){return android.provider.Settings.canDrawOverlays(c);}}
}
