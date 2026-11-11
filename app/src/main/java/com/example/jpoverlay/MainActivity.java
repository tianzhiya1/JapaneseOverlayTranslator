package com.example.jpoverlay;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.provider.Settings;
import android.net.Uri;
import android.view.View;
import android.widget.*;
public class MainActivity extends Activity {
 @Override public void onCreate(Bundle b){super.onCreate(b);
 LinearLayout layout=new LinearLayout(this);layout.setOrientation(1);layout.setPadding(32,48,32,24);
 TextView info=new TextView(this);info.setText("日语悬浮翻译器 v0.1\n\n原型功能：点击或选中其他应用中可读取的日语文字，弹出中文翻译。首次使用需要联网下载翻译模型。\n\n暂不支持视频语音、视频字幕 OCR 或图片文字。");info.setTextSize(18);layout.addView(info);
 Button overlay=new Button(this);overlay.setText("第一步：允许悬浮窗");layout.addView(overlay);overlay.setOnClickListener(v->{Intent i=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()));startActivity(i);});
 Button access=new Button(this);access.setText("第二步：开启无障碍翻译服务");layout.addView(access);access.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
 setContentView(layout);
 }
}
