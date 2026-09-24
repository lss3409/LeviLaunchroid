package org.levimc.launcher.ui.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.animation.ValueAnimator;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.animation.DynamicAnim;

public class CustomAlertDialog extends Dialog {

    private String mTitle;
    private String mMessage;
    private String mPositiveText;
    private String mNegativeText;
    private String mNeutralText;
    private View.OnClickListener mPositiveListener;
    private View.OnClickListener mNegativeListener;
    private View.OnClickListener mNeutralListener;
    private Button mPositiveButton;
    private String[] mItems;
    private DialogInterface.OnClickListener mItemClickListener;
    private View mCustomView;
    private boolean mBlurBackground;
    private int mTitleColor = 0;
    private boolean mUseBorderedBackground;
    private boolean mDismissing;
    private Runnable mDismissAnimationEndListener;
    /** v539：点外部关闭开关（onCreate 里硬编码 false 会覆盖外部设置，改用字段）。 */
    private boolean mCanceledOnTouchOutside = false;
    /** v548：弹窗最大宽度（dp），内容少的弹窗可调小让背景"刚刚好"。
     *  v552：默认 340dp（平板规范值），手机自动按 DialogSizer 缩小。 */
    private int mMaxWidthDp = 340;

    public CustomAlertDialog(Context context) {
        // v549：固定 LeviDialogTheme——游戏进程（MinecraftActivity 非 AppCompat）
        // 里 Dialog 继承宿主主题导致 Button attr 解析失败崩溃（tombstone 547）
        super(context, R.style.LeviDialogTheme);
    }

    public CustomAlertDialog setTitleText(String title) {
        this.mTitle = title;
        return this;
    }

    public CustomAlertDialog setMessage(String message) {
        this.mMessage = message;
        return this;
    }

    public CustomAlertDialog setPositiveButton(String text, View.OnClickListener listener) {
        this.mPositiveText = text;
        this.mPositiveListener = listener;
        return this;
    }

    public CustomAlertDialog setNegativeButton(String text, View.OnClickListener listener) {
        this.mNegativeText = text;
        this.mNegativeListener = listener;
        return this;
    }

    public CustomAlertDialog setNeutralButton(String text, View.OnClickListener listener) {
        this.mNeutralText = text;
        this.mNeutralListener = listener;
        return this;
    }

    public CustomAlertDialog setItems(String[] items, DialogInterface.OnClickListener listener) {
        this.mItems = items;
        this.mItemClickListener = listener;
        return this;
    }

    public CustomAlertDialog setCustomView(View view) {
        this.mCustomView = view;
        return this;
    }

    public CustomAlertDialog setBlurBackground(boolean blur) {
        this.mBlurBackground = blur;
        return this;
    }

    public CustomAlertDialog setTitleColor(int color) {
        this.mTitleColor = color;
        return this;
    }

    public CustomAlertDialog setUseBorderedBackground(boolean bordered) {
        this.mUseBorderedBackground = bordered;
        return this;
    }

    public CustomAlertDialog setOnDismissAnimationEndListener(Runnable listener) {
        this.mDismissAnimationEndListener = listener;
        return this;
    }

    /** v539：记录字段，onCreate 里重新应用（否则 show 前设置会被覆盖）。 */
    @Override
    public void setCanceledOnTouchOutside(boolean cancel) {
        mCanceledOnTouchOutside = cancel;
        super.setCanceledOnTouchOutside(cancel);
    }

    /** v548：设置弹窗最大宽度（dp），默认 400。 */
    public CustomAlertDialog setMaxWidthDp(int maxWidthDp) {
        this.mMaxWidthDp = maxWidthDp;
        return this;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.alert_dialog_custom);
        // v539：用字段（默认 false 保持原行为；分享卡弹窗显式开启）
        setCanceledOnTouchOutside(mCanceledOnTouchOutside);

        if (mUseBorderedBackground) {
            View root = findViewById(android.R.id.content);
            if (root != null) {
                View dialogRoot = ((ViewGroup) root).getChildAt(0);
                if (dialogRoot != null) {
                    dialogRoot.setBackgroundResource(R.drawable.dialog_background_black_border);
                }
            }
        }

        TextView tvTitle = findViewById(R.id.tv_title);
        TextView tvMessage = findViewById(R.id.tv_message);
        Button btnPositive = findViewById(R.id.btn_positive);
        Button btnNegative = findViewById(R.id.btn_negative);
        Button btnNeutral = findViewById(R.id.btn_neutral);
        mPositiveButton = btnPositive;
        View spacingNegNeu = findViewById(R.id.btn_spacing_neg_neu);
        View spacingNeuPos = findViewById(R.id.btn_spacing_neu_pos);

        tvTitle.setText(mTitle != null ? mTitle : "");
        if (mTitleColor != 0) {
            tvTitle.setTextColor(mTitleColor);
        }
        tvMessage.setText(mMessage != null ? mMessage : "");

        RecyclerView itemsRecyclerView = findViewById(R.id.items_recycler_view);
        View messageScrollView = findViewById(R.id.message_scroll_view);
        LinearLayout customViewContainer = findViewById(R.id.custom_view_container);
        
        if (mCustomView != null) {
            messageScrollView.setVisibility(View.GONE);
            itemsRecyclerView.setVisibility(View.GONE);
            // v553：custom view 外层 ScrollView 必须一起显示——v552 包 ScrollView
            // 后漏设，弹窗内容整个不可见（表现为空白弹窗/点不动）
            View customScroll = findViewById(R.id.custom_scroll_view);
            if (customScroll != null) {
                customScroll.setVisibility(View.VISIBLE);
            }
            if (customViewContainer != null) {
                customViewContainer.setVisibility(View.VISIBLE);
                customViewContainer.addView(mCustomView);
            }
        } else if (mItems != null && mItems.length > 0) {
            messageScrollView.setVisibility(View.GONE);
            itemsRecyclerView.setVisibility(View.VISIBLE);
            itemsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
            itemsRecyclerView.setAdapter(new ItemsAdapter(mItems, (position) -> {
                if (mItemClickListener != null) {
                    mItemClickListener.onClick(this, position);
                }
                dismiss();
            }));
        } else {
            itemsRecyclerView.setVisibility(View.GONE);
        }

        boolean hasThreeButtons = mPositiveText != null && mNegativeText != null && mNeutralText != null;
        LinearLayout btnContainer = findViewById(R.id.btn_container);

        if (hasThreeButtons && btnContainer != null) {
            btnContainer.setOrientation(LinearLayout.VERTICAL);

            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnParams.topMargin = (int) (8 * getContext().getResources().getDisplayMetrics().density);

            LinearLayout.LayoutParams firstBtnParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

            btnPositive.setLayoutParams(firstBtnParams);
            btnNeutral.setLayoutParams(btnParams);
            btnNegative.setLayoutParams(btnParams);

            if (spacingNegNeu != null) spacingNegNeu.setVisibility(View.GONE);
            if (spacingNeuPos != null) spacingNeuPos.setVisibility(View.GONE);

            btnContainer.removeAllViews();
            btnContainer.addView(btnPositive);
            btnContainer.addView(btnNeutral);
            btnContainer.addView(btnNegative);
        }

        if (mNegativeText != null) {
            btnNegative.setText(mNegativeText);
            btnNegative.setVisibility(View.VISIBLE);
        } else {
            btnNegative.setVisibility(View.GONE);
        }

        if (mNeutralText != null) {
            btnNeutral.setText(mNeutralText);
            btnNeutral.setVisibility(View.VISIBLE);
            if (!hasThreeButtons) {
                if (spacingNegNeu != null) spacingNegNeu.setVisibility(mNegativeText != null ? View.VISIBLE : View.GONE);
                if (spacingNeuPos != null) spacingNeuPos.setVisibility(mPositiveText != null ? View.VISIBLE : View.GONE);
            }
        } else {
            btnNeutral.setVisibility(View.GONE);
            if (spacingNegNeu != null) spacingNegNeu.setVisibility(View.GONE);
            if (spacingNeuPos != null) spacingNeuPos.setVisibility(mNegativeText != null && mPositiveText != null ? View.VISIBLE : View.GONE);
        }

        if (mPositiveText != null) {
            btnPositive.setText(mPositiveText);
            btnPositive.setVisibility(View.VISIBLE);
        } else {
            btnPositive.setVisibility(View.GONE);
        }

        btnPositive.setOnClickListener(v -> {
            if (mPositiveListener != null) mPositiveListener.onClick(v);
            dismiss();
        });

        btnNegative.setOnClickListener(v -> {
            if (mNegativeListener != null) mNegativeListener.onClick(v);
            dismiss();
        });

        btnNeutral.setOnClickListener(v -> {
            if (mNeutralListener != null) mNeutralListener.onClick(v);
            dismiss();
        });

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            float density = getContext().getResources().getDisplayMetrics().density;
            // v552：统一弹窗尺寸规范（DialogSizer：平板比例为主，手机自动
            // 缩小 0.85，个性化 ui_scale 已通过 densityDpi 联动）
            int dialogWidth = org.levimc.launcher.util.DialogSizer.dialogWidth(
                    getContext(), mMaxWidthDp);
            // v550：主题的 windowMinWidthMajor（Alert 主题默认 ~65% 屏幕）会在
            // 某些设备上覆盖 setLayout 的宽度——显式写 attributes 强制生效
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = dialogWidth;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(lp);
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT);
            // v552：高度上限 = 屏幕 78%（根布局 maxHeight 兜底，防按钮被挤出屏）
            View root = findViewById(R.id.dialog_root);
            if (root != null) {
                root.setMinimumHeight(0);
            }

            if (mBlurBackground) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams params = window.getAttributes();
                params.dimAmount = 0.6f;
                window.setAttributes(params);
            }
        }

        View content = findViewById(android.R.id.content);
        if (content != null) {
            DynamicAnim.animateDialogShow(content);
        }
        DynamicAnim.applyPressScale(btnPositive);
        DynamicAnim.applyPressScale(btnNegative);
        DynamicAnim.applyPressScale(btnNeutral);

        try {
            org.levimc.launcher.util.PersonalizationManager pm = new org.levimc.launcher.util.PersonalizationManager(getContext());
            int accent = pm.getAccentColor();
            if (accent != 0) {
                btnPositive.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
                btnPositive.setTextColor(Color.WHITE);
                btnNegative.setTextColor(accent);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void show() {
        Window window = getWindow();
        if (window != null) {
            window.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        super.show();
        if (window != null) {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    @Override
    public void dismiss() {
        try {
            if (!isShowing() || mDismissing) {
                return;
            }
            mDismissing = true;
            Window window = getWindow();
            if (window == null || window.getDecorView().getParent() == null) {
                try {
                    super.dismiss();
                } catch (Exception ignored) {
                } finally {
                    notifyDismissAnimationEnd();
                }
                return;
            }
            animateBackdropDismiss(window);

            View content = findViewById(android.R.id.content);
            if (content != null) {
                DynamicAnim.animateDialogDismiss(content, () -> {
                    try {
                        if (isShowing()) {
                            Window w = getWindow();
                            if (w != null && w.getDecorView().getParent() != null) {
                                clearBackdrop(w);
                                CustomAlertDialog.super.dismiss();
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        notifyDismissAnimationEnd();
                    }
                });
            } else {
                clearBackdrop(window);
                super.dismiss();
                notifyDismissAnimationEnd();
            }
        } catch (Exception ignored) {}
    }

    private void notifyDismissAnimationEnd() {
        Runnable listener = mDismissAnimationEndListener;
        mDismissAnimationEndListener = null;
        if (listener != null) {
            listener.run();
        }
    }

    private void animateBackdropDismiss(Window window) {
        WindowManager.LayoutParams initialParams = window.getAttributes();
        float startDimAmount = initialParams.dimAmount;
        if (startDimAmount <= 0f) {
            return;
        }

        ValueAnimator dimAnimator = ValueAnimator.ofFloat(startDimAmount, 0f);
        dimAnimator.setDuration(160L);
        dimAnimator.addUpdateListener(animation -> {
            Window currentWindow = getWindow();
            if (currentWindow == null || currentWindow.getDecorView().getParent() == null) {
                return;
            }
            WindowManager.LayoutParams params = currentWindow.getAttributes();
            params.dimAmount = (float) animation.getAnimatedValue();
            currentWindow.setAttributes(params);
        });
        dimAnimator.start();
    }

    private void clearBackdrop(Window window) {
        if (mBlurBackground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams params = window.getAttributes();
        params.dimAmount = 0f;
        window.setAttributes(params);
    }

    public void dismissImmediately() {
        try {
            if (isShowing()) {
                Window window = getWindow();
                if (window != null) {
                    clearBackdrop(window);
                }
                super.dismiss();
            }
        } catch (Exception ignored) {}
    }

    public Button getPositiveButton() {
        return mPositiveButton;
    }

    private static class ItemsAdapter extends RecyclerView.Adapter<ItemsAdapter.ViewHolder> {
        private final String[] items;
        private final OnItemClickListener listener;

        interface OnItemClickListener {
            void onClick(int position);
        }

        ItemsAdapter(String[] items, OnItemClickListener listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView textView = new TextView(parent.getContext());
            textView.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));
            int padding = (int) (16 * parent.getContext().getResources().getDisplayMetrics().density);
            textView.setPadding(padding, padding, padding, padding);
            textView.setTextSize(14);
            textView.setTextColor(parent.getContext().getResources().getColor(R.color.text_primary, null));
            textView.setBackgroundResource(android.R.drawable.list_selector_background);
            return new ViewHolder(textView);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.textView.setText(items[position]);
            holder.textView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onClick(position);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items != null ? items.length : 0;
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView textView;

            ViewHolder(TextView itemView) {
                super(itemView);
                this.textView = itemView;
            }
        }
    }
}
