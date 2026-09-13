package org.levimc.launcher.ui.activities;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.levimc.launcher.databinding.ActivityWebViewBinding;

public class WebViewActivity extends BaseActivity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";

    private ActivityWebViewBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityWebViewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);

        binding.webViewTitle.setText(title != null && !title.isEmpty() ? title : "");
        binding.webViewBack.setOnClickListener(v -> goBackOrFinish());
        binding.webViewClose.setOnClickListener(v -> finish());

        configureWebView();
        if (url != null && !url.isEmpty()) {
            binding.webView.loadUrl(url);
        }
    }

    @Override
    protected boolean shouldSkipNavBar() {
        // 网页页不注入启动器导航栏，避免与网页自带的返回/关闭栏叠成两条
        return true;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        // 持久化 Cookie，让网站登录状态在关闭/重启启动器后保留
        android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true);

        WebSettings settings = binding.webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        // 使用桌面版 User-Agent，让网站返回电脑版页面（如 bilibili）
        settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        binding.webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 100) {
                    binding.webViewProgress.setVisibility(View.GONE);
                } else {
                    binding.webViewProgress.setVisibility(View.VISIBLE);
                    binding.webViewProgress.setProgress(newProgress);
                }
            }
        });

        binding.webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return true;
            }
        });
    }

    private void goBackOrFinish() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        goBackOrFinish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        android.webkit.CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        android.webkit.CookieManager.getInstance().flush();
        super.onDestroy();
    }
}
