package org.levimc.launcher.ui.views;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import org.levimc.launcher.R;

import java.util.List;

public final class ContentActionPopup {

    public static final class Action {
        private final int iconRes;
        private final int labelRes;
        private final boolean destructive;
        private final Runnable callback;

        public Action(int iconRes, int labelRes, boolean destructive, Runnable callback) {
            this.iconRes = iconRes;
            this.labelRes = labelRes;
            this.destructive = destructive;
            this.callback = callback;
        }
    }

    private ContentActionPopup() {
    }

    public static void show(View anchor, CharSequence title, List<Action> actions) {
        Context context = anchor.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        View content = inflater.inflate(R.layout.popup_content_actions, null, false);
        TextView titleView = content.findViewById(R.id.action_menu_title);
        LinearLayout items = content.findViewById(R.id.action_menu_items);
        titleView.setText(title);

        View root = anchor.getRootView();
        int[] rootLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        int screenTop = rootLocation[1];
        int screenBottom = rootLocation[1] + root.getHeight();
        // v455：弹窗高度上限 = 可用屏幕高度 - 上下各 48dp 安全边距；
        // 超出时窗口定高、items 区内部滚动（小屏/横屏不再超出屏幕）
        int maxPopupHeight = Math.max(dp(context, 160),
                (screenBottom - screenTop) - dp(context, 96));
        int popupHeight = ViewGroup.LayoutParams.WRAP_CONTENT;

        // v454：按屏幕短边判断紧凑模式（v452 用 widthDp——手机横屏
        // widthDp≈800 被当平板走大行高，350dp 弹窗在横屏手机
        // ~360dp 高度里必然超出）——短边 <600dp = 手机任何方向
        android.content.res.Configuration cfg = context.getResources().getConfiguration();
        boolean compact = Math.min(cfg.screenWidthDp, cfg.screenHeightDp) < 600;
        final PopupWindow[] popupRef = new PopupWindow[1];
        for (Action action : actions) {
            View row = inflater.inflate(R.layout.item_content_action, items, false);
            ImageView icon = row.findViewById(R.id.action_icon);
            TextView label = row.findViewById(R.id.action_label);
            icon.setImageResource(action.iconRes);
            label.setText(action.labelRes);
            int color = ContextCompat.getColor(context, action.destructive ? R.color.error : R.color.on_surface);
            label.setTextColor(color);
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color));
            if (compact) {
                ViewGroup.LayoutParams icp = icon.getLayoutParams();
                icp.width = dp(context, 20);
                icp.height = dp(context, 20);
                icon.setLayoutParams(icp);
                row.setPadding(dp(context, 12), dp(context, 5),
                        dp(context, 12), dp(context, 5));
                label.setTextSize(12);
            }
            row.setOnClickListener(v -> {
                if (popupRef[0] != null) {
                    popupRef[0].dismiss();
                }
                action.callback.run();
            });
            items.addView(row);
        }

        content.measure(
                View.MeasureSpec.makeMeasureSpec(dp(context, 220), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        int measuredHeight = content.getMeasuredHeight();
        if (measuredHeight > maxPopupHeight) {
            popupHeight = maxPopupHeight;
        } else {
            popupHeight = measuredHeight;
        }
        PopupWindow popup = new PopupWindow(content, dp(context, 220), popupHeight, true);
        popupRef[0] = popup;
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setClippingEnabled(true);
        popup.setElevation(dp(context, 10));
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int xOffset = anchor.getWidth() - dp(context, 220);
        int yOffset = dp(context, 4);
        if (location[1] + anchor.getHeight() + popupHeight + yOffset > screenBottom) {
            yOffset = -popupHeight - anchor.getHeight() - dp(context, 4);
            // v452：往上翻不能超出屏幕顶——超出时贴顶显示（此前
            // 直接裁剪，手机上"显示不全"）
            int topAfter = location[1] + yOffset;
            if (topAfter < screenTop) {
                yOffset = screenTop - location[1] + dp(context, 4);
            }
        }
        popup.showAsDropDown(anchor, xOffset, yOffset);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
