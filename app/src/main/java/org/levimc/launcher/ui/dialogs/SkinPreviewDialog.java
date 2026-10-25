package org.levimc.launcher.ui.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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
 *  v0.4.197：多皮肤支持——底部相册条（正面渲染缩略图，点击切换
 *  3D 主视图）；WebGL 不可用降级 2D 平铺。 */
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

        // v0.4.197：底部相册条（正面渲染缩略图 + 当前高亮）
        HorizontalScrollView gallery = new HorizontalScrollView(context);
        gallery.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (76 * density));
        glp.topMargin = (int) (8 * density);
        gallery.setLayoutParams(glp);
        gallery.addView(strip, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(gallery);

        final LinearLayout fStrip = strip;
        updateGalleryRef[0] = () -> {
            fStrip.removeAllViews();
            for (int i = 0; i < skins.size(); i++) {
                final int idx = i;
                ImageView thumb = new ImageView(context);
                thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
                thumb.setBackgroundColor(Color.parseColor("#2A2D35"));
                Bitmap bmp = loadSkinBitmap(skins.get(i).png);
                if (bmp != null) {
                    // 「3D 正面转二维」观感：皮肤图裁出头部+身体正面
                    // 区域（64×64 布局的头+胸腹中部），视觉接近模型正面
                    thumb.setImageBitmap(faceCrop(bmp));
                }
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        (int) (56 * density), (int) (64 * density));
                tlp.setMarginEnd((int) (6 * density));
                thumb.setLayoutParams(tlp);
                if (i == current[0]) {
                    GradientDrawable sel = new GradientDrawable();
                    sel.setStroke((int) (2 * density), accent != 0 ? accent : Color.WHITE);
                    sel.setCornerRadius(6 * density);
                    thumb.setBackground(sel);
                } else {
                    GradientDrawable un = new GradientDrawable();
                    un.setColor(Color.parseColor("#2A2D35"));
                    un.setCornerRadius(6 * density);
                    thumb.setBackground(un);
                }
                thumb.setOnClickListener(v -> {
                    current[0] = idx;
                    applySkin.accept(idx);
                    updateGalleryRef[0].run();
                });
                fStrip.addView(thumb);
            }
        };
        updateGalleryRef[0].run();

        // 底部：关闭
        TextView close = new TextView(context);
        close.setText(R.string.close);
        close.setTextColor(accent != 0 ? accent : Color.WHITE);
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

    /** 「模型正面」缩略图：取皮肤图头部+胸腹中部竖条（64×64 布局：
     *  头 8,8-16,16、身体 20,20-28,32 区域），上下拼接成正面观感。 */
    private static Bitmap faceCrop(Bitmap src) {
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        if (w < 64 || h < 64) {
            return Bitmap.createScaledBitmap(src, 56, 64, true);
        }
        // 头（8,8-16,16）+ 身体正面（20,20-28,32）→ 2 段拼接
        Bitmap head = Bitmap.createBitmap(src, w / 8, h / 8, w / 8, h / 8);
        Bitmap body = Bitmap.createBitmap(src, w * 20 / 64, h * 20 / 64,
                w * 8 / 64, h * 12 / 64);
        Bitmap out = Bitmap.createBitmap(w / 8, h / 8 + h * 12 / 64,
                Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        cv.drawBitmap(head, 0, 0, p);
        cv.drawBitmap(body, 0, h / 8f, p);
        head.recycle();
        body.recycle();
        return out;
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
