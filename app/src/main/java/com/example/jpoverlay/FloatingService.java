package com.example.jpoverlay;
import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
public class FloatingService extends Service {
 WindowManager wm; TextView ball; TextView panel; WindowManager.LayoutParams bp;
 @Override public void onCreate(){super.onCreate();if(!Settings.canDrawOverlays(this)){stopSelf();return;}wm=(WindowManager)getSystemService(WINDOW_SERVICE);
 ball=new TextView(this);ball.setText("译");ball.setTextSize(22);ball.setGravity(Gravity.CENTER);ball.setTextColor(0xffffffff);ball.setBackgroundTintList(null);ball.setBackgroundColor(0xff3276d3);
 bp=new WindowManager.LayoutParams(140,140,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);bp.gravity=Gravity.TOP|Gravity.LEFT;bp.x=20;bp.y=400;
 ball.setOnTouchListener(new View.OnTouchListener(){float sx,sy;int ox,oy;boolean moved;public boolean onTouch(View v,android.view.MotionEvent e){switch(e.getAction()){case MotionEvent.ACTION_DOWN:sx=e.getRawX();sy=e.getRawY();ox=bp.x;oy=bp.y;moved=false;return true;case MotionEvent.ACTION_MOVE:bp.x=ox+(int)(e.getRawX()-sx);bp.y=oy+(int)(e.getRawY()-sy);moved|=Math.abs(e.getRawX()-sx)>12||Math.abs(e.getRawY()-sy)>12;wm.updateViewLayout(ball,bp);return true;case MotionEvent.ACTION_UP:if(!moved)togglePanel();return true;}return false;}});
 wm.addView(ball,bp);
 }
 void togglePanel(){if(panel!=null){wm.removeView(panel);panel=null;return;}panel=new TextView(this);panel.setText("日语翻译器运行中\n\n在其他应用点击可读取的日语文字，可显示中文翻译。\n\n视频 OCR 和语音翻译尚未加入。\n\n点击此处关闭面板");panel.setTextSize(17);panel.setTextColor(0xff111111);panel.setPadding(28,28,28,28);panel.setBackgroundColor(0xfff9f4df);panel.setOnClickListener(v->togglePanel());WindowManager.LayoutParams pp=new WindowManager.LayoutParams(Math.min(620,getResources().getDisplayMetrics().widthPixels-40),-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);pp.gravity=Gravity.TOP|Gravity.LEFT;pp.x=20;pp.y=180;wm.addView(panel,pp);}
 @Override public int onStartCommand(Intent i,int f,int id){return START_STICKY;}
 @Override public android.os.IBinder onBind(Intent i){return null;}
 @Override public void onDestroy(){if(panel!=null)wm.removeView(panel);if(ball!=null)wm.removeView(ball);super.onDestroy();}
}
