package com.example.jpoverlay;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.*;

public class TranslateAccessibilityService extends AccessibilityService {
    private Translator translator;
    private String last="";
    private volatile boolean modelReady=false;

    @Override public void onServiceConnected() {
        translator=Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.CHINESE).build());
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
            .addOnSuccessListener(x->modelReady=true);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(event.getPackageName()!=null && getPackageName().contentEquals(event.getPackageName())) return;
        int type=event.getEventType();
        if(type!=AccessibilityEvent.TYPE_VIEW_CLICKED
           &&type!=AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
           &&type!=AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)return;
        AccessibilityNodeInfo node=event.getSource();
        if(node==null||node.getText()==null)return;
        String original=node.getText().toString().trim();
        // Chinese Han characters alone are not proof of Japanese.
        if(original.length()<2||original.length()>1200||original.equals(last)
          ||!original.matches("(?s).*[\\u3040-\\u30ff].*"))return;
        last=original;
        if(!modelReady||translator==null)return;
        translator.translate(original)
            .addOnSuccessListener(zh->FloatingService.showText("日语文字："+original+"\n中文："+zh));
    }

    @Override public void onInterrupt(){}
    @Override public void onDestroy(){
        if(translator!=null)translator.close();
        super.onDestroy();
    }
}
