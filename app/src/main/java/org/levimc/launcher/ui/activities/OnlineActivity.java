package org.levimc.launcher.ui.activities;

import android.os.Bundle;
import android.text.Editable;
import android.text.Selection;
import android.text.Spanned;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.online.InviteCode;

/**
 * 联机页（v492 第一版）：邀请码加入房间。
 * 输入自动格式化（P/ 前缀 + 短横线分组 + 去非法字符），点击加入校验
 * 模 7 校验位，通过后展示解析出的 EasyTier 网络名/密钥（连接功能
 * 待组网内核接入后实现）。
 */
public final class OnlineActivity extends BaseActivity {

    private EditText codeInput;
    private TextView statusText;
    private boolean formatting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_online);
        setActiveNavTab(R.id.nav_tab_online);

        codeInput = findViewById(R.id.online_code_input);
        statusText = findViewById(R.id.online_status_text);
        findViewById(R.id.online_join_button).setOnClickListener(v -> onJoinClicked());

        // P/ 前缀由 TextView 渲染（API 21+ setPrefix），不参与文本——手输前缀
        // 不会再与数据位混淆；无 XML 属性（android:prefix 不存在），只能代码设置。
        codeInput.setPrefix("P/");

        codeInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // 输入法组合（composing）期间整段 replace 会破坏组合区，
                // 导致字符错位/重复/输入法会话卡死——组合中一律不动文本。
                if (formatting || isComposing(s)) {
                    return;
                }
                // 只在光标位于末尾（追加输入）时整理，避免与中间编辑打架。
                if (Selection.getSelectionEnd(s) != s.length()) {
                    return;
                }
                String formatted = InviteCode.formatInput(s.toString());
                if (!formatted.contentEquals(s)) {
                    formatting = true;
                    try {
                        s.replace(0, s.length(), formatted);
                        Selection.setSelection(s, s.length());
                    } finally {
                        formatting = false;
                    }
                }
            }
        });
    }

    /** 是否存在输入法组合区（拼音/联想等尚未提交的文本）。 */
    private static boolean isComposing(Editable s) {
        for (Object span : s.getSpans(0, s.length(), Object.class)) {
            if ((s.getSpanFlags(span) & Spanned.SPAN_COMPOSING) != 0) {
                return true;
            }
        }
        return false;
    }

    private void onJoinClicked() {
        String raw = codeInput.getText() == null ? "" : codeInput.getText().toString();
        InviteCode.Result result = InviteCode.parse(raw);
        statusText.setVisibility(View.VISIBLE);
        switch (result.error) {
            case FORMAT:
                statusText.setTextColor(getResources().getColor(R.color.error, getTheme()));
                statusText.setText(getString(R.string.online_err_format));
                break;
            case CHARSET:
                statusText.setTextColor(getResources().getColor(R.color.error, getTheme()));
                statusText.setText(getString(R.string.online_err_charset));
                break;
            case CHECKSUM:
                statusText.setTextColor(getResources().getColor(R.color.error, getTheme()));
                statusText.setText(getString(R.string.online_err_checksum));
                break;
            default:
                statusText.setTextColor(getResources().getColor(R.color.primary, getTheme()));
                statusText.setText(getString(R.string.online_join_ok,
                        result.parsed.networkName, result.parsed.networkSecret));
                break;
        }
    }
}
