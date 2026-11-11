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
    private boolean overlayRequestLaunched=false;
    private boolean requestedMic=false;
    private boolean started=false;
    private boolean manuallyStopped=false;
    private TextView info;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36,52,36,24);
        info=new TextView(this);
        info.setTextSize(18);
        info.setText("日语悬浮翻译 v0.5\n\n打开应用后自动启动离线日语听音。\n首次运行仅需授予悬浮窗和麦克风权限。");
        root.addView(info);

        Button retry=new Button(this);
        retry.setText("启动 / 重新启动翻译");
        root.addView(retry);
        retry.setOnClickListener(v->{manuallyStopped=false;started=false;startIfReady();});

        Button stop=new Button(this);
        stop.setText("停止翻译");
        root.addView(stop);
        stop.setOnClickListener(v->{
            manuallyStopped=true;started=false;
            stopService(new Intent(this,FloatingService.class));
            info.setText("已停止日语录音和翻译。\n再次打开应用会重新启动。");
        });

        Button accessibility=new Button(this);
        accessibility.setText("仅首次设置：允许日文屏幕文字读取");
        root.addView(accessibility);
        accessibility.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

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
            info.setText("首次设置：请允许在其他应用上层显示。\n授权后返回，翻译器会继续启动。");
            if(!overlayRequestLaunched){
                overlayRequestLaunched=true;
                Intent intent=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:"+getPackageName()));
                startActivity(intent);
            }
            return;
        }
        if(Build.VERSION.SDK_INT>=23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            info.setText("首次设置：需要麦克风权限，才能独立识别日语声音。");
            if(!requestedMic){
                requestedMic=true;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},100);
            }
            return;
        }
        started=true;
        info.setText("日语翻译正在启动。\n请查看屏幕上方的小字幕，里面会显示真实录音与识别状态。\n\n这个版本使用独立离线日语识别，不需要 Google 语音服务。");
        Intent service=new Intent(this,FloatingService.class);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(service);
        else startService(service);
    }

    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==100){
            if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED){
                startIfReady();
            }else{
                info.setText("没有麦克风权限，无法进行日语听音。\n可以在系统设置中授权后重新打开应用。");
            }
        }
    }
}
