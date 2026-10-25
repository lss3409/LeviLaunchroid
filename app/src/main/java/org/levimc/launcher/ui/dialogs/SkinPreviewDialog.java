package org.levimc.launcher.ui.dialogs;

import android.animation.Animator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.levimc.launcher.R;
import org.levimc.launcher.util.DialogSizer;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** v0.4.194/197：皮肤 3D 预览弹窗（方案三：WebView + skinview3d 本地化）。
 *  v0.4.197：多皮肤支持——相册条（正面渲染缩略图，点击切换 3D 主视图）。
 *  v0.4.198：相册条移入 3D 展示区内部（覆盖底部）；缩略图改为仅头部
 *  （8,8-16,16）最近邻整数倍放大（文件管理器 upscaleThumb 同款，像素
 *  清晰不糊）；相册动画——选中项「抽出放大」（Overshoot），滚动时
 *  缩略图按距中心距离连续缩放跟随（M3 丝滑感），滚动停止吸附最近项
 *  居中并切换选中。 */
public final class SkinPreviewDialog {

    public static void show(Context context,
                            List<SkinPackPreview.SkinEntry> skins,
                            int initialIndex,
                            String title) {
        if (skins == null || skins.isEmpty()) {
            return;
        }
        int width = DialogSizer.dialogWidth(context, 380);
        float density = context.getResources().getDisplayMetrics().density;
        int accent = new org.levimc.launcher.util.PersonalizationManager(context)
                .getAccentColor();
        int accentSafe = accent != 0 ? accent : Color.WHITE;

        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_rounded_card);
        root.setPadding((int) (16 * density), (int) (12 * density),
                (int) (16 * density), (int) (12 * density));

        // 标题 + 皮肤名
        TextView titleView = new TextView(context);
        titleView.setText(title != null ? title : context.getString(R.string.skin_preview_title));
        titleView.setTextColor(context.getColor(R.color.on_surface));
        titleView.setTextSize(15f);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, (int) (10 * density));
        root.addView(titleView);

        // 3D 预览区（WebView 340dp 高；失败降级 2D）
        FrameLayout stage = new FrameLayout(context);
        stage.setBackgroundColor(Color.parseColor("#23262E"));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (340 * density));
        root.addView(stage, slp);

        ImageView fallback = new ImageView(context);
        fallback.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fallback.setVisibility(View.GONE);
        stage.addView(fallback, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final int[] current = {Math.max(0, Math.min(initialIndex, skins.size() - 1))};

        final WebView webView = new WebView(context);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        webView.setBackgroundColor(Color.TRANSPARENT);
        stage.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final boolean[] pageLoaded = {false};
        final Runnable[] updateGalleryRef = new Runnable[1];

        // v0.4.198：相册条移入 3D 展示区内部——覆盖在预览底部
        HorizontalScrollView gallery = new HorizontalScrollView(context);
        gallery.setHorizontalScrollBarEnabled(false);
        gallery.setClipToPadding(false);
        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        int stripPad = (int) (8 * density);
        strip.setPadding(stripPad, (int) (6 * density), stripPad, (int) (6 * density));
        gallery.addView(strip, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 半透明底条（顶部圆角），与展示区融合
        GradientDrawable gBg = new GradientDrawable();
        gBg.setColor(Color.parseColor("#CC1A1D23"));
        gBg.setCornerRadii(new float[]{10 * density, 10 * density, 10 * density,
                10 * density, 0, 0, 0, 0});
        gallery.setBackground(gBg);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (64 * density));
        glp.gravity = Gravity.BOTTOM;
        stage.addView(gallery, glp);

        final HorizontalScrollView fGallery = gallery;
        final ImageView[] thumbs = new ImageView[skins.size()];
        final Runnable[] snapRef = new Runnable[1];

        final java.util.function.Consumer<Integer> applySkin = idx -> {
            if (!pageLoaded[0]) {
                return;
            }
            SkinPackPreview.SkinEntry e = skins.get(idx);
            Bitmap bmp = loadSkinBitmap(e.png);
            if (bmp != null) {
                webView.evaluateJavascript(
                        "loadSkin('data:image/png;base64," + bitmapToBase64(bmp) + "');", null);
            }
            if (updateGalleryRef[0] != null) {
                updateGalleryRef[0].run();
            }
        };

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (pageLoaded[0]) {
                    return;
                }
                pageLoaded[0] = true;
                applySkin.accept(current[0]);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                view.setVisibility(View.GONE);
                showFallback2D(fallback, skins.get(current[0]).png);
            }
        });
        webView.loadUrl("file:///android_asset/skin_viewer/skin_preview.html");

        final LinearLayout fStrip = strip;
        // v0.4.198：滚动停止后吸附最近项居中；中心项 ≠ 当前选中则切换
        snapRef[0] = () -> {
            int center = fGallery.getScrollX() + fGallery.getWidth() / 2;
            int best = current[0];
            int bestDist = Integer.MAX_VALUE;
            for (int i = 0; i < thumbs.length; i++) {
                View t = thumbs[i];
                if (t == null) {
                    continue;
                }
                int d = Math.abs(t.getLeft() + t.getWidth() / 2 - center);
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            View bt = thumbs[best];
            if (bt == null) {
                return;
            }
            int targetX = bt.getLeft() + bt.getWidth() / 2 - fGallery.getWidth() / 2;
            targetX = Math.max(0, Math.min(targetX,
                    fStrip.getWidth() - fGallery.getWidth()));
            if (Math.abs(targetX - fGallery.getScrollX()) > 2) {
                fGallery.smoothScrollTo(targetX, 0);
            }
            if (best != current[0]) {
                current[0] = best;
                applySkin.accept(best);
            }
        };
        // v0.4.198：滚动时缩略图按距中心距离连续缩放——「选中一样的动画跟随」
        fGallery.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
            int center = sx + fGallery.getWidth() / 2;
            float half = Math.max(1f, fGallery.getWidth() / 2f);
            for (int i = 0; i < thumbs.length; i++) {
                View t = thumbs[i];
                if (t == null) {
                    continue;
                }
                float dist = Math.min(1f,
                        Math.abs(t.getLeft() + t.getWidth() / 2 - center) / half);
                float s = 1.15f - 0.35f * dist;
                t.animate().scaleX(s).scaleY(s)
                        .setDuration(120)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            }
            // 滚动停止 150ms 后吸附（重置计时）
            fGallery.removeCallbacks(snapRef[0]);
            fGallery.postDelayed(snapRef[0], 150);
        });

        updateGalleryRef[0] = () -> {
            fStrip.removeAllViews();
            java.util.Arrays.fill(thumbs, null);
            int thumbPx = (int) (48 * density);
            for (int i = 0; i < skins.size(); i++) {
                final int idx = i;
                ImageView thumb = new ImageView(context);
                thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
                Bitmap bmp = loadSkinBitmap(skins.get(i).png);
                if (bmp != null) {
                    // v0.4.198：仅头部（8,8-16,16）+ 最近邻整数倍放大
                    // （文件管理器 upscaleThumb 同款——像素清晰不糊）
                    thumb.setImageBitmap(headCrop(bmp, thumbPx));
                }
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        thumbPx, thumbPx);
                tlp.setMarginEnd((int) (6 * density));
                thumb.setLayoutParams(tlp);
                GradientDrawable bg = new GradientDrawable();
                if (i == current[0]) {
                    bg.setStroke((int) (2 * density), accentSafe);
                    bg.setColor(Color.parseColor("#33FFFFFF"));
                    thumb.setScaleX(1.15f);
                    thumb.setScaleY(1.15f);
                } else {
                    bg.setColor(Color.parseColor("#2A2D35"));
                    thumb.setScaleX(1f);
                    thumb.setScaleY(1f);
                }
                bg.setCornerRadius(6 * density);
                thumb.setBackground(bg);
                thumb.setOnClickListener(v -> {
                    if (idx == current[0]) {
                        return;
                    }
                    current[0] = idx;
                    // v0.4.198：选中「抽出放大」——1.0→1.3（Overshoot）回落到 1.15
                    thumb.animate().scaleX(1.3f).scaleY(1.3f)
                            .setDuration(160)
                            .setInterpolator(new OvershootInterpolator(1.6f))
                            .setListener(new Animator.AnimatorListener() {
                                @Override public void onAnimationStart(Animator a) {}
                                @Override public void onAnimationEnd(Animator a) {
                                    thumb.animate().scaleX(1.15f).scaleY(1.15f)
                                            .setDuration(120)
                                            .setInterpolator(new DecelerateInterpolator())
                                            .setListener(null)
                                            .start();
                                }
                                @Override public void onAnimationCancel(Animator a) {}
                                @Override public void onAnimationRepeat(Animator a) {}
                            })
                            .start();
                    applySkin.accept(idx);
                });
                thumbs[idx] = thumb;
                fStrip.addView(thumb);
            }
            // 重建后把选中项滚到中心
            if (thumbs[current[0]] != null && fStrip.getWidth() > 0) {
                View sel = thumbs[current[0]];
                int tx = sel.getLeft() + sel.getWidth() / 2 - fGallery.getWidth() / 2;
                tx = Math.max(0, Math.min(tx, Math.max(0,
                        fStrip.getWidth() - fGallery.getWidth())));
                fGallery.post(() -> fGallery.scrollTo(tx, 0));
            }
        };
        updateGalleryRef[0].run();

        // 底部：关闭
        TextView close = new TextView(context);
        close.setText(R.string.close);
        close.setTextColor(accentSafe);
        close.setTextSize(14f);
        close.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, (int) (10 * density), 0, 0);
        close.setOnClickListener(v -> {
            webView.destroy();
            dialog.dismiss();
        });
        root.addView(close);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setOnDismissListener(d -> webView.destroy());
        dialog.show();
    }

    private static void showFallback2D(ImageView iv, java.io.File png) {
        Bitmap bmp = loadSkinBitmap(png);
        if (bmp != null) {
            iv.setImageBitmap(bmp);
            iv.setVisibility(View.VISIBLE);
        }
    }

    /** v0.4.198：相册缩略图——仅头部（64×64 布局 8,8-16,16），最近邻
     *  整数倍放大到目标尺寸（filterBitmap=false，像素清晰）。 */
    private static Bitmap headCrop(Bitmap src, int targetPx) {
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int hw = Math.max(1, w / 8);
        int hh = Math.max(1, h / 8);
        if (hw < 2 || hh < 2) {
            return Bitmap.createScaledBitmap(src, targetPx, targetPx, false);
        }
        Bitmap head = Bitmap.createBitmap(src, w / 8, h / 8, hw, hh);
        int up = Math.max(1, targetPx / Math.max(hw, hh));
        if (up <= 1) {
            return head;
        }
        Bitmap scaled = Bitmap.createBitmap(hw * up, hh * up, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(scaled);
        Paint p = new Paint();
        p.setFilterBitmap(false); // 最近邻——8×8 像素放大清晰
        c.drawBitmap(head, null, new Rect(0, 0, hw * up, hh * up), p);
        head.recycle();
        return scaled;
    }

    /** 皮肤图加载（OOM 防护：采样解码上限 1024）。 */
    private static Bitmap loadSkinBitmap(java.io.File f) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            int sample = 1;
            while (Math.max(opts.outWidth, opts.outHeight) / sample > 1024) {
                sample *= 2;
            }
            BitmapFactory.Options load = new BitmapFactory.Options();
            load.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), load);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String bitmapToBase64(Bitmap bmp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }
}
