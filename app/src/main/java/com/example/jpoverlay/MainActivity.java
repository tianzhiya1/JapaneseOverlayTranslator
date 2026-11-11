package com.example.jpoverlay;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.provider.Settings;
import android.net.Uri;
import android.Manifest;
import android.content.pm.PackageManager;
import android.widget.*;
public class MainActivity extends Activity {
 @Override public void onCreate(Bundle b){super.onCreate(b);
 LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(32,48,32,24);
 TextView info=new TextView(this);info.setText("日语悬浮翻译器 v0.3\n\n本版新增：通过麦克风识别日语声音，并在悬浮窗翻译成中文。\n\n测试方法：打开麦克风听音，然后用手机外放日语视频。注意：不是内部音频捕获；需要手机语音识别服务，可能需要联网。");info.setTextSize(17);layout.addView(info);
 Button overlay=new Button(this);overlay.setText("1. 允许悬浮窗");layout.addView(overlay);overlay.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))));
 Button access=new Button(this);access.setText("2. 开启文字翻译无障碍服务");layout.addView(access);access.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
 Button start=new Button(this);start.setText("3. 启动悬浮球");layout.addView(start);start.setOnClickListener(v->startOverlay(null));
 Button mic=new Button(this);mic.setText("4. 启动日语听音（麦克风）");layout.addView(mic);mic.setOnClickListener(v->{if(android.os.Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10);}else startOverlay("LISTEN");});
 Button stopmic=new Button(this);stopmic.setText("停止听音");layout.addView(stopmic);stopmic.setOnClickListener(v->startOverlay("STOP_LISTEN"));
 Button stop=new Button(this);stop.setText("关闭悬浮球");layout.addView(stop);stop.setOnClickListener(v->stopService(new Intent(this,FloatingService.class)));
 setContentView(layout);
 }
 void startOverlay(String action){if(!Settings.canDrawOverlays(this)){Toast.makeText(this,"请先允许悬浮窗",Toast.LENGTH_LONG).show();return;}Intent i=new Intent(this,FloatingService.class);if(action!=null)i.setAction(action);startService(i);}
 @Override public void onRequestPermissionsResult(int req,String[] perms,int[] grants){super.onRequestPermissionsResult(req,perms,grants);if(req==10&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startOverlay("LISTEN");}
}
