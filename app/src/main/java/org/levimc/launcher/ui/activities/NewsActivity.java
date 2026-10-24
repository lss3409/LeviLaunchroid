package org.levimc.launcher.ui.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LayoutAnimationController;
import android.view.animation.OvershootInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.news.NewsFeed;
import org.levimc.launcher.core.news.NewsNotificationHelper;
import org.levimc.launcher.core.news.NewsRepository;
import org.levimc.launcher.core.news.NewsState;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.util.PersonalizationManager;

public final class NewsActivity extends BaseActivity {
    public static final String EXTRA_NEWS_ID = "news_id";
    public static final String EXTRA_NEWS_URL = "news_url";
    private RecyclerView recycler;
    private NewsAdapter adapter;
    private ProgressBar progress;
    private TextView empty;
    private Button enableNotifications;
    private ImageButton refresh;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private boolean rendered;

    /** v0.4.174：当前栏位（true=LBBL，false=LeviLauncher 官方）。 */
    private boolean lbblTab = true;
    /** v0.4.174：列表入场动画控制器（fadeInUp，逐 item 延迟）。 */
    private LayoutAnimationController itemEntrance;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> updateNotificationButton());
        setContentView(R.layout.activity_news);
        setActiveNavTab(0);

        recycler = findViewById(R.id.news_recycler);
        progress = findViewById(R.id.news_progress);
        empty = findViewById(R.id.news_empty);
        enableNotifications = findViewById(R.id.news_enable_notifications);
        refresh = findViewById(R.id.news_refresh);

        adapter = new NewsAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
        recycler.setHasFixedSize(false);

        refresh.setOnClickListener(v -> refreshNews(true));
        enableNotifications.setOnClickListener(v -> requestNotificationPermission());
        DynamicAnim.applyPressScale(refresh);
        DynamicAnim.applyPressScale(enableNotifications);

        // v0.4.174：双栏位 Tab（LBBL 公告 / LeviLauncher 官方）
        // 初始底色由 AccentStyler 染成个性化 accent（选中）+ surface
        // （未选中）；切换态在 switchNewsSource 里同样走 accent。
        com.google.android.material.button.MaterialButton tabLbbl =
                findViewById(R.id.news_tab_lbbl);
        com.google.android.material.button.MaterialButton tabOfficial =
                findViewById(R.id.news_tab_official);
        org.levimc.launcher.util.AccentStyler.stylePrimary(this, tabLbbl);
        org.levimc.launcher.util.AccentStyler.stylePrimary(this, tabOfficial);
        tabLbbl.setOnClickListener(v -> switchNewsSource(true));
        tabOfficial.setOnClickListener(v -> switchNewsSource(false));
        // 默认选中 LBBL（用户要求 LBBL 公告在前）
        switchNewsSource(true);

        // v0.4.174：列表入场动画——每条新闻依次淡入 + 上移
        // （AndroidViewAnimations fadeInUp 思路；40ms/item 延迟）。
        // 只在 item 布局时播放一次，无持续开销。
        AnimationSet entrance = new AnimationSet(true);
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        TranslateAnimation rise = new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0.12f, Animation.RELATIVE_TO_SELF, 0f);
        entrance.addAnimation(fadeIn);
        entrance.addAnimation(rise);
        entrance.setDuration(280);
        entrance.setInterpolator(new DecelerateInterpolator(1.6f));
        itemEntrance = new LayoutAnimationController(entrance, 0.04f);
        itemEntrance.setOrder(LayoutAnimationController.ORDER_NORMAL);

        updateNotificationButton();
        refreshNews(false);
    }

    /** v0.4.174：双栏位切换——accent 化选中态（个性化强调色兼容）
     *  + 选中弹跳/颜色过渡动效。 */
    private void switchNewsSource(boolean lbbl) {
        if (lbbl == lbblTab && rendered) return; // 重复点当前栏位不重载
        lbblTab = lbbl;
        com.google.android.material.button.MaterialButton tabLbbl =
                findViewById(R.id.news_tab_lbbl);
        com.google.android.material.button.MaterialButton tabOfficial =
                findViewById(R.id.news_tab_official);
        int accent = new PersonalizationManager(this).getAccentColor();
        int surface = getColor(R.color.surface);
        if (tabLbbl != null) {
            animateTabState(tabLbbl, lbbl, accent, surface);
        }
        if (tabOfficial != null) {
            animateTabState(tabOfficial, !lbbl, accent, surface);
        }
        rendered = false;
        if (lbbl) {
            NewsRepository.loadCachedLbbl(this, (feed, error) -> render(feed));
            NewsRepository.refreshIfStaleLbbl(this, (feed, error) -> render(feed));
        } else {
            NewsRepository.loadCached(this, (feed, error) -> render(feed));
            NewsRepository.refreshIfStale(this, (feed, error) -> render(feed));
        }
    }

    /** v0.4.174：Tab 选中态动效——选中者 scale 弹跳 + 底色 ARGB 渐变
     *  （OvershootInterpolator，AndroidViewAnimations 弹跳风格）。 */
    private void animateTabState(com.google.android.material.button.MaterialButton tab,
                                 boolean selected, int accent, int surface) {
        int from = selected ? surface : accent;
        int to = selected ? accent : surface;
        android.animation.ValueAnimator colorAnim =
                android.animation.ValueAnimator.ofFloat(0f, 1f);
        colorAnim.setDuration(240);
        colorAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            tab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    argbMix(from, to, t)));
        });
        colorAnim.start();
        tab.setTextColor(selected ? getColor(R.color.on_primary)
                : getColor(R.color.on_surface));
        if (selected) {
            tab.setScaleX(0.94f);
            tab.setScaleY(0.94f);
            tab.animate().scaleX(1f).scaleY(1f)
                    .setDuration(320)
                    .setInterpolator(new OvershootInterpolator(2.2f))
                    .start();
        }
    }

    /** ARGB 双色线性插值（ValueAnimator 渐变用）。 */
    private static int argbMix(int from, int to, float t) {
        return Color.argb(
                (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t),
                (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        scrollToRequestedItem();
    }

    @Override
    protected void onNewsChanged() {
        if (adapter == null) return;
        if (lbblTab) {
            NewsRepository.loadCachedLbbl(this, (feed, error) -> render(feed));
        } else {
            NewsRepository.loadCached(this, (feed, error) -> render(feed));
        }
    }

    private void refreshNews(boolean userRequested) {
        refresh.setEnabled(false);
        if (!rendered) progress.setVisibility(View.VISIBLE);
        NewsRepository.Callback callback = (feed, error) -> {
            refresh.setEnabled(true);
            progress.setVisibility(View.GONE);
            render(feed);
            if (userRequested) {
                Toast.makeText(this,
                        error == null ? R.string.news_refreshed : R.string.news_refresh_failed,
                        Toast.LENGTH_SHORT).show();
            }
        };
        if (lbblTab) {
            if (userRequested) NewsRepository.refreshLbbl(this, callback);
            else NewsRepository.refreshIfStaleLbbl(this, callback);
        } else {
            if (userRequested) NewsRepository.refresh(this, callback);
            else NewsRepository.refreshIfStale(this, callback);
        }
    }

    private void render(NewsFeed feed) {
        if (feed == null) feed = new NewsFeed();
        java.util.Set<String> unreadIds = NewsState.getUnreadIds(this, feed);
        adapter.submit(feed.items, unreadIds);
        rendered = true;
        progress.setVisibility(View.GONE);
        empty.setVisibility(feed.items.isEmpty() ? View.VISIBLE : View.GONE);
        recycler.setVisibility(feed.items.isEmpty() ? View.GONE : View.VISIBLE);
        if (!feed.items.isEmpty()) {
            // v0.4.174：入场动画（每次切换源/刷新后播放一次；
            // submit 是同步 notify，schedule+requestLayout 保证在
            // 新数据布局上播放）
            recycler.setLayoutAnimation(itemEntrance);
            recycler.post(() -> {
                recycler.scheduleLayoutAnimation();
                recycler.requestLayout();
            });
        }
        NewsState.markAllRead(this, feed);
        scrollToRequestedItem();
    }

    private void scrollToRequestedItem() {
        if (adapter == null || recycler == null || getIntent() == null) return;
        String requestedId = getIntent().getStringExtra(EXTRA_NEWS_ID);
        int position = adapter.indexOf(requestedId);
        if (position >= 0) recycler.scrollToPosition(position);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void updateNotificationButton() {
        NewsNotificationHelper.initialize(this);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            enableNotifications.setVisibility(View.GONE);
            return;
        }
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        enableNotifications.setVisibility(granted ? View.GONE : View.VISIBLE);
    }
}
