package org.levimc.launcher.ui.activities;

import android.os.Bundle;
import android.text.Editable;
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

        codeInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (formatting) {
                    return;
                }
                String formatted = InviteCode.formatInput(s.toString());
                if (!formatted.contentEquals(s)) {
                    formatting = true;
                    s.replace(0, s.length(), formatted);
                    formatting = false;
                }
            }
        });
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
