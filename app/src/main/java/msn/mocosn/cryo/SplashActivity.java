package msn.mocosn.cryo;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.appcompat.app.AppCompatActivity;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

public class SplashActivity extends AppCompatActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable launchRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // Pre-initialize Python in background to eliminate startup lag in MainActivity
        new Thread(() -> {
            try {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(getApplicationContext()));
                }
            } catch (Exception ignored) {}
        }).start();

        // Smooth YouTube-style logo scale and fade-in animation
        View logoContainer = findViewById(R.id.splashLogoContainer);
        View bottomContainer = findViewById(R.id.splashBottomContainer);

        if (logoContainer != null) {
            logoContainer.setAlpha(0f);
            logoContainer.setScaleX(0.88f);
            logoContainer.setScaleY(0.88f);
            logoContainer.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(450)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }

        if (bottomContainer != null) {
            bottomContainer.setAlpha(0f);
            bottomContainer.animate()
                    .alpha(1f)
                    .setDuration(500)
                    .setStartDelay(150)
                    .start();
        }

        // Navigate to MainActivity after 1.2s splash display
        launchRunnable = () -> {
            if (!isFinishing() && !isDestroyed()) {
                Intent intent = new Intent(SplashActivity.this, MainActivity.class);
                if (getIntent() != null) {
                    if (getIntent().getAction() != null) {
                        intent.setAction(getIntent().getAction());
                    }
                    if (getIntent().getType() != null) {
                        intent.setType(getIntent().getType());
                    }
                    if (getIntent().getExtras() != null) {
                        intent.putExtras(getIntent().getExtras());
                    }
                    if (getIntent().getData() != null) {
                        intent.setData(getIntent().getData());
                    }
                }
                startActivity(intent);
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            }
        };

        handler.postDelayed(launchRunnable, 1200);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (launchRunnable != null) {
            handler.removeCallbacks(launchRunnable);
        }
    }
}
