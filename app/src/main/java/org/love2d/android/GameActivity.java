package org.love2d.android;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Rect;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

import org.libsdl.app.SDLActivity;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class GameActivity extends SDLActivity {
    private static final String TAG = "GameActivity";
    public static final int RECORD_AUDIO_REQUEST_CODE = 3;

    private static GameActivity instance;

    private static final String ADMOB_BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111";
    private static final String ADMOB_REWARDED_UNIT_ID = "ca-app-pub-3940256099942544/5224354917";
    private static final String ADMOB_INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712";

    private AdView mAdView;
    private RewardedAd mRewardedAd;
    private InterstitialAd mInterstitialAd;

    protected Vibrator vibrator;
    protected boolean shortEdgesMode;
    protected final int[] recordAudioRequestDummy = new int[1];
    private Uri delayedUri = null;
    private String[] args;
    private boolean isFused;

    private static native void nativeSetDefaultStreamValues(int sampleRate, int framesPerBurst);

    @Override
    protected String getMainSharedObject() {
        String[] libs = getLibraries();
        return "lib" + libs[libs.length - 1] + ".so";
    }

    @Override
    protected String[] getLibraries() {
        return new String[]{"c++_shared", "SDL3", "oboe", "openal", "luajit", "liblove", "love"};
    }

    @Override
    protected String[] getArguments() { return args; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "started");
        instance = this;
        isFused = hasEmbeddedGame();
        args = new String[0];

        if (checkCallingOrSelfPermission(Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED) {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        Intent intent = getIntent();
        handleIntent(intent, true);
        if (intent != null) {
            intent.setData(null);
        }

        super.onCreate(savedInstanceState);
        if (mBrokenLibraries) { return; }

        nativeSetDefaultStreamValues(getAudioFreq(), getAudioSMP());
        hideSystemBars();

        if (android.os.Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attr = getWindow().getAttributes();
            attr.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            shortEdgesMode = true;
        }

        if (delayedUri != null) {
            sendUriAsDroppedFile(delayedUri);
            delayedUri = null;
        }

        MobileAds.initialize(this, initializationStatus -> {
            runOnUiThread(() -> {
                loadAdMobBanner();
                loadRewardedAd();
                loadInterstitialAd();
            });
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent, false);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        runOnUiThread(() -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                );
            }
        });
    }

    private void loadAdMobBanner() {
        try {
            mAdView = new AdView(this);
            mAdView.setAdSize(AdSize.BANNER);
            mAdView.setAdUnitId(ADMOB_BANNER_UNIT_ID);

            RelativeLayout.LayoutParams adParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            );
            adParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            adParams.addRule(RelativeLayout.CENTER_HORIZONTAL);

            if (mLayout != null) {
                ((ViewGroup) mLayout).addView(mAdView, adParams);
                AdRequest adRequest = new AdRequest.Builder().build();
                mAdView.loadAd(adRequest);
            }
        } catch (Exception e) {
            Log.e(TAG, "Banner Error: " + e.getMessage());
        }
    }

    private void loadRewardedAd() {
        AdRequest adRequest = new AdRequest.Builder().build();
        RewardedAd.load(this, ADMOB_REWARDED_UNIT_ID, adRequest, new RewardedAdLoadCallback() {
            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                mRewardedAd = null;
                Log.e(TAG, "RewardedAd failed to load: " + loadAdError.getMessage());
                runOnUiThread(() -> Toast.makeText(GameActivity.this, "فشل تحميل الفيديو. تأكد من الإنترنت.", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onAdLoaded(@NonNull RewardedAd rewardedAd) {
                mRewardedAd = rewardedAd;
                Log.d(TAG, "RewardedAd loaded successfully.");
                runOnUiThread(() -> Toast.makeText(GameActivity.this, "الفيديو جاهز الآن!", Toast.LENGTH_SHORT).show());
                mRewardedAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                    @Override
                    public void onAdDismissedFullScreenContent() {
                        mRewardedAd = null;
                        hideSystemBars();
                        loadRewardedAd();
                    }
                });
            }
        });
    }

    @Keep
    public void showRewardedAd() {
        runOnUiThread(() -> {
            if (mRewardedAd != null) {
                mRewardedAd.show(GameActivity.this, rewardItem -> {
                    Log.d(TAG, "Reward earned! Sending native callback.");
                    SDLActivity.onNativeDropFile("admob://reward_earned");
                });
            } else {
                Toast.makeText(GameActivity.this, "الفيديو قيد التحميل، يرجى الانتظار...", Toast.LENGTH_LONG).show();
                loadRewardedAd();
            }
        });
    }

    private void loadInterstitialAd() {
        AdRequest adRequest = new AdRequest.Builder().build();
        InterstitialAd.load(this, ADMOB_INTERSTITIAL_UNIT_ID, adRequest, new InterstitialAdLoadCallback() {
            @Override
            public void onAdLoaded(@NonNull InterstitialAd interstitialAd) {
                mInterstitialAd = interstitialAd;
                Log.d(TAG, "InterstitialAd loaded successfully.");
                mInterstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                    @Override
                    public void onAdDismissedFullScreenContent() {
                        mInterstitialAd = null;
                        hideSystemBars();
                        loadInterstitialAd();
                    }
                });
            }
            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) { 
                mInterstitialAd = null; 
                Log.e(TAG, "InterstitialAd failed to load: " + loadAdError.getMessage());
            }
        });
    }

    @Keep
    public void showInterstitialAd() {
        runOnUiThread(() -> {
            if (mInterstitialAd != null) {
                mInterstitialAd.show(GameActivity.this);
            } else {
                loadInterstitialAd();
            }
        });
    }

    @Override protected void onDestroy() { if (mAdView != null) mAdView.destroy(); if (vibrator != null) vibrator.cancel(); super.onDestroy(); }
    @Override protected void onPause() { if (mAdView != null) mAdView.pause(); if (vibrator != null) vibrator.cancel(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); hideSystemBars(); if (mAdView != null) mAdView.resume(); }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) { if (grantResults.length > 0) { if (requestCode == RECORD_AUDIO_REQUEST_CODE) { synchronized (recordAudioRequestDummy) { recordAudioRequestDummy[0] = grantResults[0]; recordAudioRequestDummy.notify(); } } else { super.onRequestPermissionsResult(requestCode, permissions, grantResults); } } }
    @Keep public boolean hasEmbeddedGame() { try { getAssets().open("main.lua").close(); return true; } catch (IOException e) { try { getAssets().open("game.love").close(); return true; } catch (IOException e2) { return false; } } }
    @Keep public void vibrate(double seconds) { if (vibrator != null) { long d = (long)(seconds * 1000.); if (android.os.Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(d, VibrationEffect.DEFAULT_AMPLITUDE)); else vibrator.vibrate(d); } }
    @Keep public boolean hasBackgroundMusic() { return ((AudioManager) getSystemService(Context.AUDIO_SERVICE)).isMusicActive(); }
    @Keep public String[] buildFileTree() { return new String[0]; }
    @Keep public float getDPIScale() { return getResources().getDisplayMetrics().density; }
    @Keep public Rect getSafeArea() { return null; }
    @Keep public String getCRequirePath() { return getApplicationInfo().nativeLibraryDir + "/?.so"; }
    @Keep public void setImmersiveMode(boolean enable) { if(enable) hideSystemBars(); }
    @Keep public boolean getImmersiveMode() { return shortEdgesMode; }
    @Keep public boolean hasRecordAudioPermission() { return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }
    @Keep public void requestRecordAudioPermission() {}
    public int getAudioSMP() { return 256; }
    public int getAudioFreq() { return 44100; }
    public boolean isNativeLibsExtracted() { return (getApplicationInfo().flags & ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0; }
    public void sendUriAsDroppedFile(Uri uri) { SDLActivity.onNativeDropFile(uri.toString()); }
    
    // 🌟 معالجة وفلترة الروابط بنظام الـ Intents لضمان الاستجابة الفورية والقصوى ومنع الحجب أو الخروج من اللعبة
    private void handleIntent(Intent intent, boolean onCreate) { 
        if (intent == null) return;
        
        Uri game = intent.getData(); 
        if (game == null) return; 

        // 🔍 كاشف السجلات البرمجي لتعقب الروابط في الـ Logcat فوراً
        Log.d(TAG, "=== Intent URI Received: " + game.toString() + " ==="); 

        // 🌟 التقاط الرابط المخصص وفك التشفير برمجياً وثبات واجهة الـ UI
        if (game.getScheme() != null && game.getScheme().equals("admobbridge")) {
            String host = game.getHost();
            Log.d(TAG, "AdMob bridge matched successfully! Host target: " + host); 
            
            runOnUiThread(() -> {
                if ("rewarded".equals(host)) {
                    showRewardedAd();
                } else if ("interstitial".equals(host)) {
                    showInterstitialAd();
                }
            });
            return; // إنهاء العملية هنا بنجاح لمنع اللعبة من الانخفاض أو الانهيار
        }

        if (onCreate) { 
            if (isFused) delayedUri = game; 
            else processOpenGame(game); 
        } else {
            sendUriAsDroppedFile(game); 
        }
    }
    
    private void processOpenGame(Uri game) { if (game.getScheme() != null && game.getScheme().equals("file")) args = new String[]{game.getPath()}; }
}