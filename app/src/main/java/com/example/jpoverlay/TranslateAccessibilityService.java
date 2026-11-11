package com.example.jpoverlay;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.HardwareBuffer;
import android.os.Build;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

public class TranslateAccessibilityService extends AccessibilityService {
    private static volatile TranslateAccessibilityService active;

    @Override public void onServiceConnected(){
        active=this;
    }

    // User explicitly long-presses the overlay before a screenshot is taken.
    public static void captureForSelection(){
        TranslateAccessibilityService s=active;
        if(s==null){
            FloatingService.restoreOverlays();
            FloatingService.showText("请先在系统设置中启用翻译器的无障碍服务，才能长按截图");
            return;
        }
        if(Build.VERSION.SDK_INT<30){
            FloatingService.restoreOverlays();
            FloatingService.showText("系统版本不支持无障碍截图（需要安卓11或更新）");
            return;
        }
        try{
            s.takeScreenshot(Display.DEFAULT_DISPLAY,s.getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback(){
                    @Override public void onSuccess(AccessibilityService.ScreenshotResult result){
                        Bitmap image=null;
                        HardwareBuffer buffer=result.getHardwareBuffer();
                        try{
                            Bitmap hardware=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());
                            if(hardware!=null)image=hardware.copy(Bitmap.Config.ARGB_8888,false);
                        }catch(Exception e){
                            FloatingService.restoreOverlays();
                            FloatingService.showText("截图转换失败："+e.getMessage());
                        }finally{buffer.close();}
                        if(image==null){
                            FloatingService.restoreOverlays();
                            FloatingService.showText("系统没有返回可用截图");
                            return;
                        }
                        ScreenshotSelectionActivity.open(s,image);
                    }
                    @Override public void onFailure(int errorCode){
                        FloatingService.restoreOverlays();
                        FloatingService.showText("截图失败（代码 "+errorCode+"）；受保护画面可能禁止截图");
                    }
                });
        }catch(Exception e){
            FloatingService.restoreOverlays();
            FloatingService.showText("无法请求屏幕截图："+e.getMessage());
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e){}
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){
        if(active==this)active=null;
        super.onDestroy();
    }
}
