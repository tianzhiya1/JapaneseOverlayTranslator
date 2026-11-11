package com.example.jpoverlay;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.provider.Settings;
import android.net.Uri;
import android.widget.*;
public class MainActivity extends Activity {
 @Override public void onCreate(Bundle b){super.onCreate(b);
 LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(32,48,32,24);
 TextView info=new TextView(this);info.setText("日语悬浮翻译器 v0.2\n\n先允许悬浮窗，再点击启动悬浮球。点击悬浮球可展开面板，拖动可移动位置。\n\n小说翻译需另外开启无障碍服务；视频 OCR 和语音翻译仍在开发中。");info.setTextSize(18);layout.addView(info);
 Button overlay=new Button(this);overlay.setText("第一步：允许悬浮窗");layout.addView(overlay);overlay.setOnClickListener(v->{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));});
 Button access=new Button(this);access.setText("第二步：开启无障碍翻译服务");layout.addView(access);access.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
 Button start=new Button(this);start.setText("第三步：启动悬浮球");layout.addView(start);start.setOnClickListener(v->{if(Settings.canDrawOverlays(this)){startService(new Intent(this,FloatingService.class));Toast.makeText(this,"悬浮球已启动",Toast.LENGTH_SHORT).show();}else{Toast.makeText(this,"请先允许悬浮窗",Toast.LENGTH_LONG).show();}});
 Button stop=new Button(this);stop.setText("关闭悬浮球");layout.addView(stop);stop.setOnClickListener(v->stopService(new Intent(this,FloatingService.class)));
 setContentView(layout);
 }
}
