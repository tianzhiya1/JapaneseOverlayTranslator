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
 boolean pendingStart=false;
 @Override public void onCreate(Bundle b){super.onCreate(b);
 LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(32,48,32,24);
 TextView info=new TextView(this);info.setText("日语悬浮翻译器 v0.4 测试版\n\n一个按钮启动悬浮窗与日语听音。文字识别需单独开启一次无障碍服务。\n\n注意：语音识别仍通过麦克风，并非视频内部音频。");info.setTextSize(17);layout.addView(info);
 Button start=new Button(this);start.setText("启动悬浮翻译（全部启动）");layout.addView(start);start.setOnClickListener(v->begin());
 Button access=new Button(this);access.setText("首次设置：开启文字读取权限");layout.addView(access);access.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
 Button stop=new Button(this);stop.setText("停止悬浮翻译");layout.addView(stop);stop.setOnClickListener(v->stopService(new Intent(this,FloatingService.class)));
 setContentView(layout);
 }
 void begin(){pendingStart=true;if(!Settings.canDrawOverlays(this)){startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));return;}
 if(android.os.Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10);return;}
 pendingStart=false;Intent i=new Intent(this,FloatingService.class);i.setAction("LISTEN");startService(i);Toast.makeText(this,"悬浮窗和日语听音已请求启动",Toast.LENGTH_LONG).show();}
 @Override protected void onResume(){super.onResume();if(pendingStart&&Settings.canDrawOverlays(this)){new android.os.Handler(getMainLooper()).postDelayed(()->{if(pendingStart)begin();},300);}}
 @Override public void onRequestPermissionsResult(int req,String[] perms,int[] grants){super.onRequestPermissionsResult(req,perms,grants);if(req==10){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)begin();else{pendingStart=false;Toast.makeText(this,"未获得麦克风权限，无法启动听音",Toast.LENGTH_LONG).show();}}}
}
