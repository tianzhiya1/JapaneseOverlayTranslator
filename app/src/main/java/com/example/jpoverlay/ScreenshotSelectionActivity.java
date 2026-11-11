package com.example.jpoverlay;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

public class ScreenshotSelectionActivity extends Activity {
    private static volatile Bitmap pending;
    private Bitmap screenshot;
    private boolean busy=false;

    public static void open(Context context, Bitmap image) {
        pending=image;
        Intent intent=new Intent(context,ScreenshotSelectionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            context.startActivity(intent);
        }catch(Exception e){
            pending=null;
            FloatingService.restoreOverlays();
            FloatingService.showText("无法打开截图选区："+e.getMessage());
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        screenshot=pending;
        pending=null;
        if(screenshot==null){finish();return;}
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(new SelectionView());
    }

    @Override public void onBackPressed(){
        FloatingService.restoreOverlays();
        super.onBackPressed();
    }

    private void recognize(Bitmap part){
        FloatingService.restoreOverlays();
        FloatingService.showText("正在识别选区文字…");
        final TextRecognizer jp=TextRecognition.getClient(
            new JapaneseTextRecognizerOptions.Builder().build());
        InputImage input=InputImage.fromBitmap(part,0);
        jp.process(input)
            .addOnSuccessListener(jaResult->{
                String japanese=jaResult.getText().trim();
                if(hasKanaOrHan(japanese)){
                    jp.close();
                    deliver(japanese,true);
                    part.recycle();
                }else{
                    jp.close();
                    recognizeLatin(part,japanese);
                }
            })
            .addOnFailureListener(e->{
                jp.close();
                recognizeLatin(part,"");
            });
    }

    private boolean hasKanaOrHan(String s){
        return s.matches("(?s).*[\\u3040-\\u30ff\\u3400-\\u9fff].*");
    }

    private void recognizeLatin(Bitmap part,String japaneseFallback){
        final TextRecognizer latin=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        latin.process(InputImage.fromBitmap(part,0))
            .addOnSuccessListener(result->{
                String en=result.getText().trim();
                String chosen=en.length()>1?en:japaneseFallback;
                if(chosen.length()<2)FloatingService.showText("没有识别到日语或英语，请框选更完整的文字");
                else deliver(chosen,hasKanaOrHan(chosen));
                latin.close();
                part.recycle();
            })
            .addOnFailureListener(e->{
                latin.close();
                part.recycle();
                if(japaneseFallback.length()>1)deliver(japaneseFallback,false);
                else FloatingService.showText("文字识别失败："+e.getMessage());
            });
    }

    private void deliver(String text,boolean japanese){
        String cleaned=text.replaceAll("\\s*\\n\\s*"," ").trim();
        if(cleaned.length()>900)cleaned=cleaned.substring(0,900);
        FloatingService.translateScreenshot(cleaned,japanese);
    }

    private final class SelectionView extends View {
        private final Paint paint=new Paint(3);
        private final RectF selected=new RectF();
        private float sx,sy,ex,ey;
        private boolean dragging=false;

        SelectionView(){super(ScreenshotSelectionActivity.this);}

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            if(screenshot==null)return;
            c.drawBitmap(screenshot,null,new Rect(0,0,getWidth(),getHeight()),paint);
            paint.setColor(0x99000000);
            c.drawRect(0,0,getWidth(),getHeight(),paint);
            if(dragging){
                selected.set(Math.min(sx,ex),Math.min(sy,ey),Math.max(sx,ex),Math.max(sy,ey));
                c.save();
                c.clipRect(selected);
                paint.setColor(0xffffffff);
                c.drawBitmap(screenshot,null,new Rect(0,0,getWidth(),getHeight()),paint);
                c.restore();
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(4);
                paint.setColor(0xff44baff);
                c.drawRect(selected,paint);
                paint.setStyle(Paint.Style.FILL);
            }
            paint.setColor(0xee172c46);
            c.drawRoundRect(12,20,getWidth()-12,88,18,18,paint);
            paint.setColor(0xffffffff);
            paint.setTextSize(30);
            c.drawText("拖动框选文字 / 点按定位 / 返回取消",26,65,paint);
            paint.setColor(0xffffffff);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e){
            if(busy)return true;
            switch(e.getAction()){
                case MotionEvent.ACTION_DOWN:
                    sx=e.getX();sy=e.getY();ex=sx;ey=sy;dragging=true;invalidate();return true;
                case MotionEvent.ACTION_MOVE:
                    ex=e.getX();ey=e.getY();invalidate();return true;
                case MotionEvent.ACTION_UP:
                    ex=e.getX();ey=e.getY();dragging=false;
                    float left,top,right,bottom;
                    if(Math.abs(ex-sx)<30&&Math.abs(ey-sy)<30){
                        float rx=Math.min(260,getWidth()*.30f);
                        float ry=Math.min(125,getHeight()*.055f);
                        left=sx-rx;right=sx+rx;top=sy-ry;bottom=sy+ry;
                    }else{
                        left=Math.min(sx,ex);right=Math.max(sx,ex);
                        top=Math.min(sy,ey);bottom=Math.max(sy,ey);
                    }
                    left=Math.max(0,left);top=Math.max(0,top);
                    right=Math.min(getWidth(),right);bottom=Math.min(getHeight(),bottom);
                    if(right-left<10||bottom-top<10){
                        FloatingService.restoreOverlays();finish();return true;
                    }
                    int x=(int)(left*screenshot.getWidth()/getWidth());
                    int y=(int)(top*screenshot.getHeight()/getHeight());
                    int w=(int)((right-left)*screenshot.getWidth()/getWidth());
                    int h=(int)((bottom-top)*screenshot.getHeight()/getHeight());
                    x=Math.max(0,Math.min(x,screenshot.getWidth()-1));
                    y=Math.max(0,Math.min(y,screenshot.getHeight()-1));
                    w=Math.max(1,Math.min(w,screenshot.getWidth()-x));
                    h=Math.max(1,Math.min(h,screenshot.getHeight()-y));
                    busy=true;
                    try{
                        Bitmap part=Bitmap.createBitmap(screenshot,x,y,w,h);
                        recognize(part);
                    }catch(Exception error){
                        FloatingService.restoreOverlays();
                        FloatingService.showText("选区失败："+error.getMessage());
                    }
                    finish();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging=false;invalidate();return true;
            }
            return true;
        }
    }
}
