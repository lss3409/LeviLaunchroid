package org.levimc.launcher.ui.dialogs;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.levimc.launcher.R;
import org.levimc.launcher.util.PersonalizationManager;

public class LoadingDialog extends Dialog {
    private TextView messageView;
    private TextView detailView;
    private TextView hideView;
    private ProgressBar progressBar;
    private CharSequence pendingMessage;
    private CharSequence pendingDetail;

    public interface OnHideListener {
        void onHide();
    }

    private OnHideListener hideListener;

    public LoadingDialog(Context context) {
        super(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        View view = LayoutInflater.from(getContext()).inflate(R.layout.dialog_loading, null);
        setContentView(view);
        setCancelable(false);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.dimAmount = 0.6f;
        getWindow().setAttributes(params);

        messageView = view.findViewById(R.id.tv_message);
        detailView = view.findViewById(R.id.tv_detail);
        hideView = view.findViewById(R.id.tv_hide);
        progressBar = view.findViewById(R.id.progress_bar);

        if (pendingMessage != null) {
            messageView.setText(pendingMessage);
        }
        if (pendingDetail != null) {
            detailView.setText(pendingDetail);
        }
        if (hideView != null) {
            hideView.setOnClickListener(v -> {
                if (hideListener != null) {
                    hideListener.onHide();
                } else {
                    // 默认行为：隐藏弹窗，后台操作（导入/备份/登录）继续，
                    // 操作完成后仍会弹出结果提示。
                    dismiss();
                }
            });
        }

        try {
            PersonalizationManager pm = new PersonalizationManager(getContext());
            int accent = pm.getAccentColor();
            if (accent != 0) {
                if (progressBar != null) {
                    progressBar.setIndeterminateTintList(ColorStateList.valueOf(accent));
                }
                // 隐藏按钮文字跟随个性化 accent 色（默认是主题色 primary）
                if (hideView != null) {
                    hideView.setTextColor(accent);
                }
            }
        } catch (Exception ignored) {}
    }

    public void setMessage(CharSequence message) {
        if (messageView != null) {
            messageView.setText(message);
        } else {
            pendingMessage = message;
        }
    }

    public void setDetail(CharSequence detail) {
        if (detailView != null) {
            detailView.setText(detail);
        } else {
            pendingDetail = detail;
        }
    }

    public void setOnHideListener(OnHideListener listener) {
        this.hideListener = listener;
        if (hideView != null && listener != null) {
            hideView.setOnClickListener(v -> listener.onHide());
        }
    }
}