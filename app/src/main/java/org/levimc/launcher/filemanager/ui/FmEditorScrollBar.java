package org.levimc.launcher.filemanager.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 编辑器可拖动滚动条（v680 重构）：
 * 不自己绘制——滚动条显示交给 EditText 系统滚动条（滑动时自动显示/自动更新）。
 * 本 View 只是一个覆盖在右侧的透明触摸热区，负责把按下/拖动映射为滚动比例。
 */
public class FmEditorScrollBar extends View {

    public interface Listener {
        /** 按到/拖动到比例位置（0..1）。 */
        void onDragRatio(float ratio);
    }

    private Listener listener;

    public FmEditorScrollBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float h = getHeight();
        if (h <= 0) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                if (listener != null) {
                    float ratio = Math.min(1f, Math.max(0f, event.getY() / h));
                    listener.onDragRatio(ratio);
                }
                return true;
            }
            default:
                return true;
        }
    }
}
