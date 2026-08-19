package com.pmdgaming5.ballplatformer;

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;

public final class MainActivity extends Activity {
    private GameView gameView;
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                0x00000400 | 0x00000200 | 0x00000004 | 0x00000002 | 0x00001000);
        gameView = new GameView(this);
        setContentView(gameView);
    }
    @Override protected void onResume() { super.onResume(); if (gameView != null) gameView.resumeGame(); }
    @Override protected void onPause() { if (gameView != null) gameView.pauseForLifecycle(); super.onPause(); }
}
