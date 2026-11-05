package org.levimc.launcher.ui.activities;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.interpolator.view.animation.LinearOutSlowInInterpolator;

import org.levimc.launcher.R;
import org.levimc.launcher.databinding.ActivitySplashBinding;
import org.levimc.launcher.preloader.PreloaderSignatureRulesManager;
import org.levimc.launcher.util.LauncherStorage;
import org.levimc.launcher.util.PersonalizationManager;

import java.util.Locale;

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends BaseActivity {

    @Override
    protected boolean shouldSkipNavBar() { return true; }

    private ActivitySplashBinding binding;
    private boolean navigated = false;
    private ValueAnimator orbitAnimator;
    private ObjectAnimator progressAnimator;
    private ValueAnimator glowPulseAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        syncSystemLocale();
        super.onCreate(savedInstanceState);
        // v0.4.41：进程热启动（MainActivity 仍在栈中）跳过动画——
        // 桌面点图标不再每次走 Splash（此前用户误以为后台被杀）
        org.levimc.launcher.ui.activities.MainActivity main =
                org.levimc.launcher.ui.activities.MainActivity.sInstance;
        if (main != null && !main.isDestroyed() && !main.isFinishing()) {
            finish();
            overridePendingTransition(0, 0);
            return;
        }
        // v549：启动器活跃时间戳（详情卡「最近在线」随心跳广播）
        org.levimc.launcher.core.online.PlayerIdentity.touchLastActive(this);
        // v564：联机调试文件日志初始化（vivo logcat 抓不到应用日志）
        org.levimc.launcher.util.OnlineDebugLog.init(this);
        binding = ActivitySplashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        applySplashTheme();

        binding.imgLeaf.setAlpha(0f);
        binding.imgLeaf.setScaleX(0.8f);
        binding.imgLeaf.setScaleY(0.8f);
        binding.logoGlow.setAlpha(0f);
        binding.logoGlow.setScaleX(0.5f);
        binding.logoGlow.setScaleY(0.5f);
        binding.orbitRing.setAlpha(0f);
        binding.orbitDot.setAlpha(0f);
        binding.tvAppName.setAlpha(0f);
        binding.tvAppName.setTranslationY(20f);
        binding.loadingBar.setAlpha(0f);
        binding.loadingBar.setProgress(0);
        binding.tvPreparing.setAlpha(0f);

        warmUpStorageAndVersions();

        binding.getRoot().post(this::startSplashSequence);
    }

    private void warmUpStorageAndVersions() {
        Context appContext = getApplicationContext();
        new Thread(() -> {
            LauncherStorage.ensureNoMedia(appContext);
        }, "launcher-storage-warmup").start();
        PreloaderSignatureRulesManager.refreshOnLauncherStart(appContext);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelAnimators();
    }

    private void cancelAnimators() {
        if (orbitAnimator != null) orbitAnimator.cancel();
        if (progressAnimator != null) progressAnimator.cancel();
        if (glowPulseAnimator != null) glowPulseAnimator.cancel();
    }

    private void startSplashSequence() {
        binding.logoGlow.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(600)
            .setInterpolator(new LinearOutSlowInInterpolator())
            .start();

        binding.imgLeaf.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setStartDelay(200)
            .setInterpolator(new LinearOutSlowInInterpolator())
            .start();

        binding.orbitRing.post(() -> {
            float centerX = binding.orbitRing.getX() + binding.orbitRing.getWidth() / 2f;
            float centerY = binding.orbitRing.getY() + binding.orbitRing.getHeight() / 2f;
            float radius = binding.orbitRing.getWidth() / 2f - binding.orbitDot.getWidth() / 2f;
            
            double radians = Math.toRadians(-90);
            float x = (float) (centerX + radius * Math.cos(radians) - binding.orbitDot.getWidth() / 2f);
            float y = (float) (centerY + radius * Math.sin(radians) - binding.orbitDot.getHeight() / 2f);
            
            binding.orbitDot.setX(x);
            binding.orbitDot.setY(y);
        });

        binding.orbitRing.animate()
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(400)
            .start();

        binding.orbitDot.animate()
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(400)
            .withEndAction(this::startOrbitAnimation)
            .start();

        binding.tvAppName.postDelayed(() -> {
            binding.tvAppName.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(400)
                .setInterpolator(new LinearOutSlowInInterpolator())
                .start();
            // v0.4.132：LBBL 手写艺术字描边动画（苹果风）
            binding.tvAppName.startHandwriting(1500);
        }, 500);

        binding.loadingBar.postDelayed(() -> {
            binding.loadingBar.animate().alpha(1f).setDuration(300).start();
            binding.loadingBar.startWaving();
            binding.tvPreparing.animate().alpha(0.7f).setDuration(300).start();
            startLoadingAnimation();
        }, 700);

        binding.logoGlow.postDelayed(this::startGlowPulse, 600);
    }

    private void applySplashTheme() {
        int accent = resolveAccentColor();
        binding.tvAppName.setTextColor(accent);
        binding.logoGlow.setBackground(createRadialGlow(accent));
        binding.orbitRing.setBackground(createOrbitRing(accent));
        binding.orbitDot.setBackground(createOrbitDot(accent));
        // v0.4.132：波浪进度条（WaveProgressView）+ 手写字（HandwritingTextView）
        binding.loadingBar.setWaveColor(accent);
        binding.tvAppName.setTextColor(accent);
        binding.tvAppName.setText(getString(R.string.app_name));
        binding.tvPreparing.setTextColor(blendColors(
            getColor(R.color.text_secondary),
            accent,
            isDarkMode() ? 0.24f : 0.18f
        ));
    }

    private int resolveAccentColor() {
        int accent = new PersonalizationManager(this).getAccentColor();
        return accent != 0 ? accent : getColor(R.color.primary);
    }

    private GradientDrawable createRadialGlow(int accent) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        drawable.setGradientRadius(dp(90));
        drawable.setColors(new int[]{
            withAlpha(accent, isDarkMode() ? 42 : 30),
            withAlpha(accent, isDarkMode() ? 18 : 14),
            Color.TRANSPARENT
        });
        return drawable;
    }

    private GradientDrawable createOrbitRing(int accent) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(Color.TRANSPARENT);
        drawable.setStroke(dp(1), withAlpha(accent, isDarkMode() ? 52 : 70));
        return drawable;
    }

    private GradientDrawable createOrbitDot(int accent) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        drawable.setGradientRadius(dp(8));
        drawable.setColors(new int[]{
            blendColors(accent, Color.WHITE, 0.28f),
            withAlpha(accent, 190),
            withAlpha(accent, 0)
        });
        return drawable;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(
            Math.max(0, Math.min(alpha, 255)),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        );
    }

    private int blendColors(int from, int to, float ratio) {
        float boundedRatio = Math.max(0f, Math.min(ratio, 1f));
        float inverse = 1f - boundedRatio;
        return Color.rgb(
            (int) (Color.red(from) * inverse + Color.red(to) * boundedRatio),
            (int) (Color.green(from) * inverse + Color.green(to) * boundedRatio),
            (int) (Color.blue(from) * inverse + Color.blue(to) * boundedRatio)
        );
    }

    private boolean isDarkMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private int dp(float value) {
        return Math.max(1, Math.round(value * getResources().getDisplayMetrics().density));
    }

    private void startOrbitAnimation() {
        View dot = binding.orbitDot;
        View ring = binding.orbitRing;
        
        float centerX = ring.getX() + ring.getWidth() / 2f;
        float centerY = ring.getY() + ring.getHeight() / 2f;
        float radius = ring.getWidth() / 2f - dot.getWidth() / 2f;

        orbitAnimator = ValueAnimator.ofFloat(0f, 360f);
        orbitAnimator.setDuration(3000);
        orbitAnimator.setRepeatCount(ValueAnimator.INFINITE);
        orbitAnimator.setInterpolator(new LinearInterpolator());
        orbitAnimator.addUpdateListener(animation -> {
            float angle = (float) animation.getAnimatedValue();
            double radians = Math.toRadians(angle - 90);
            float x = (float) (centerX + radius * Math.cos(radians) - dot.getWidth() / 2f);
            float y = (float) (centerY + radius * Math.sin(radians) - dot.getHeight() / 2f);
            dot.setX(x);
            dot.setY(y);
        });
        orbitAnimator.start();
    }

    private void startGlowPulse() {
        glowPulseAnimator = ValueAnimator.ofFloat(1f, 1.1f, 1f);
        glowPulseAnimator.setDuration(2000);
        glowPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        glowPulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        glowPulseAnimator.addUpdateListener(animation -> {
            float scale = (float) animation.getAnimatedValue();
            binding.logoGlow.setScaleX(scale);
            binding.logoGlow.setScaleY(scale);
        });
        glowPulseAnimator.start();
    }

    private void startLoadingAnimation() {
        // v0.4.132：波浪进度条 0→1（ValueAnimator 驱动 setProgress）
        progressAnimator = ObjectAnimator.ofFloat(binding.loadingBar, "progress", 0f, 1f);
        progressAnimator.setDuration(2200);
        progressAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        progressAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                playIconTransition();
            }
        });
        progressAnimator.start();
    }

    /** v0.4.132：Levi 图标过渡——进度完成后中央图标缩小飞向
     *  左上角（主页图标位置），其余元素淡出后进主页。 */
    private void playIconTransition() {
        if (navigated) return;
        navigated = true;
        cancelAnimators();

        float curCx = binding.imgLeaf.getX() + binding.imgLeaf.getWidth() / 2f;
        float curCy = binding.imgLeaf.getY() + binding.imgLeaf.getHeight() / 2f;
        float tx = dp(72) - curCx;
        float ty = dp(72) - curCy;

        binding.orbitRing.animate().alpha(0f).setDuration(250).start();
        binding.orbitDot.animate().alpha(0f).setDuration(250).start();
        binding.tvAppName.animate().alpha(0f).setDuration(250).start();
        binding.loadingBar.animate().alpha(0f).setDuration(250).start();
        binding.tvPreparing.animate().alpha(0f).setDuration(250).start();
        binding.logoGlow.animate().alpha(0f).setDuration(300).start();

        binding.imgLeaf.animate()
            .translationX(tx)
            .translationY(ty)
            .scaleX(0.4f)
            .scaleY(0.4f)
            .setDuration(650)
            // v0.4.133：Overshoot 回弹插值器——图标过渡 Q 弹
            // （弹簧过冲效果，落位后轻微回弹）
            .setInterpolator(new android.view.animation.OvershootInterpolator(1.35f))
            .withEndAction(() -> {
                Intent intent = new Intent(SplashActivity.this, MainActivity.class);
                startActivity(intent);
                finish();
                overridePendingTransition(0, 0);
            })
            .start();
    }

    private void navigateToMain() {
        playIconTransition();
    }

    private void syncSystemLocale() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                android.app.LocaleManager localeManager = getSystemService(android.app.LocaleManager.class);
                if (localeManager != null) {
                    android.os.LocaleList appLocales = localeManager.getApplicationLocales();
                    android.os.LocaleList systemLocales = localeManager.getSystemLocales();
                    if (!appLocales.isEmpty()) {
                        localeManager.setApplicationLocales(android.os.LocaleList.getEmptyLocaleList());
                    }
                    if (!systemLocales.isEmpty()) {
                        Locale.setDefault(systemLocales.get(0));
                    }
                }
            } catch (Exception ignored) {}
        }
    }
}
