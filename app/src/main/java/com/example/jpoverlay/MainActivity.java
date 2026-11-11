package com.example.jpoverlay;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;

public class MainActivity extends Activity {
    private boolean overlayRequestLaunched=false, requestedMic=false, started=false, manuallyStopped=false;
    private TextView info;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36,52,36,24);
        info=new TextView(this);
        info.setTextSize(17);
        info.setText("日语悬浮翻译 v0.6\n\n启动后只显示中文翻译。\n长按悬浮「译」按钮：截图框选日文或英文，翻译成中文。\n首次使用截图需要开启无障碍权限。");
        root.addView(info);
        Button start=new Button(this);start.setText("启动 / 重新启动");root.addView(start);
        start.setOnClickListener(v->{manuallyStopped=false;started=false;startIfReady();});
        Button stop=new Button(this);stop.setText("停止翻译");root.addView(stop);
        stop.setOnClickListener(v->{
            manuallyStopped=true;started=false;
            stopService(new Intent(this,FloatingService.class));
            info.setText("已停止语音识别和悬浮翻译。");
        });
        Button access=new Button(this);access.setText("首次设置：开启截图权限（无障碍服务）");root.addView(access);
        access.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        setContentView(root);
        new Handler(Looper.getMainLooper()).post(this::startIfReady);
    }

    @Override protected void onResume(){
        super.onResume();
        new Handler(Looper.getMainLooper()).postDelayed(()->{
            if(!started&&!manuallyStopped&&Settings.canDrawOverlays(this))startIfReady();
        },300);
    }

    private void startIfReady(){
        if(started||manuallyStopped)return;
        if(!Settings.canDrawOverlays(this)){
            info.setText("首次设置：请允许悬浮窗显示。\n返回后自动继续启动。");
            if(!overlayRequestLaunched){
                overlayRequestLaunched=true;
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:"+getPackageName())));
            }
            return;
        }
        if(Build.VERSION.SDK_INT>=23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            info.setText("首次设置：请授予麦克风权限。");
            if(!requestedMic){
                requestedMic=true;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},100);
            }
            return;
        }
        started=true;
        info.setText("已请求启动中文悬浮字幕。\n长按「译」按钮可截图框选日文或英文。\n如果长按提示未启用截图权限，请点击下方的首次设置按钮。");
        Intent service=new Intent(this,FloatingService.class);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(code,permissions,grants);
        if(code==100){
            if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)startIfReady();
            else info.setText("麦克风权限未授权，不能运行语音翻译。");
        }
    }
}
