package org.levimc.launcher.ui.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.Base64;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.levimc.launcher.R;
import org.levimc.launcher.util.DialogSizer;

import java.io.ByteArrayOutputStream;
import java.io.File;

/** v0.4.194：皮肤 3D 预览弹窗（方案三：WebView + skinview3d 本地化）。
 *  皮肤 PNG → base64 注入 skin_preview.html 的 loadSkin()；WebGL
 *  不可用时降级 2D 平铺显示。弹出后自动旋转，支持拖拽旋转/滚轮缩放。 */
public final class SkinPreviewDialog {

    /** 打开皮肤预览（skinPng 为皮肤图片文件；可能为 null 时仅显示 2D）。 */
    public static void show(Context context, File skinPng, String title) {
        int width = DialogSizer.dialogWidth(context, 360);
        float density = context.getResources().getDisplayMetrics().density;

        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_rounded_card);
        root.setPadding((int) (16 * density), (int) (12 * density),
                (int) (16 * density), (int) (12 * density));

        // 标题行
        TextView titleView = new TextView(context);
        titleView.setText(title != null ? title : context.getString(R.string.skin_preview_title));
        titleView.setTextColor(context.getColor(R.color.on_surface));
        titleView.setTextSize(15f);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, (int) (10 * density));
        root.addView(titleView);

        // 3D 预览区（WebView 320dp 高；失败降级 2D ImageView）
        FrameLayout stage = new FrameLayout(context);
        stage.setBackgroundColor(Color.parseColor("#23262E"));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (320 * density));
        root.addView(stage, slp);

        ImageView fallback = new ImageView(context);
        fallback.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fallback.setVisibility(android.view.View.GONE);
        stage.addView(fallback, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final Bitmap bmp = skinPng != null && skinPng.isFile()
                ? loadSkinBitmap(skinPng) : null;

        final WebView webView = new WebView(context);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        webView.setBackgroundColor(Color.TRANSPARENT);
        stage.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final boolean[] loaded = {false};
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (loaded[0]) {
                    return;
                }
                loaded[0] = true;
                if (bmp != null) {
                    String b64 = bitmapToBase64(bmp);
                    view.evaluateJavascript(
                            "loadSkin('data:image/png;base64," + b64 + "');", null);
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                // WebView 不可用 → 2D 降级
                view.setVisibility(android.view.View.GONE);
                if (bmp != null) {
                    fallback.setImageBitmap(bmp);
                }
                fallback.setVisibility(android.view.View.VISIBLE);
            }
        });
        webView.loadUrl("file:///android_asset/skin_viewer/skin_preview.html");

        // 底部：关闭按钮（M3 文字按钮 accent）
        TextView close = new TextView(context);
        close.setText(R.string.close);
        close.setTextColor(new org.levimc.launcher.util.PersonalizationManager(context)
                .getAccentColor());
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

    /** 皮肤图加载（OOM 防护：采样解码上限 1024）。 */
    private static Bitmap loadSkinBitmap(File f) {
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
