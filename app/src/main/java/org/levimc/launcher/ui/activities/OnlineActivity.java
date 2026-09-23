package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.text.Editable;
import android.text.Selection;
import android.text.Spanned;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.online.EasyTierManager;
import org.levimc.launcher.core.online.InviteCode;

/**
 * 联机页：邀请码加入房间（v492）+ EasyTier 真实组网（v495）。
 * 校验通过 → VPN 授权 → 内核组网（DHCP）→ VpnService 挂 TUN → 显示虚拟 IP。
 */
public final class OnlineActivity extends BaseActivity implements EasyTierManager.Listener {

    private static final int REQ_VPN = 1001;

    private EditText codeInput;
    private TextView statusText;
    private View disconnectButton;
    private boolean formatting;
    private InviteCode.Parsed pendingJoin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_online);
        setActiveNavTab(R.id.nav_tab_online);

        codeInput = findViewById(R.id.online_code_input);
        statusText = findViewById(R.id.online_status_text);
        disconnectButton = findViewById(R.id.online_disconnect_button);
        findViewById(R.id.online_join_button).setOnClickListener(v -> onJoinClicked());
        disconnectButton.setOnClickListener(v -> onDisconnectClicked());

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
                pendingJoin = result.parsed;
                Intent vpnIntent = VpnService.prepare(this);
                if (vpnIntent != null) {
                    // 首次联机：请求系统 VPN 授权
                    statusText.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
                    statusText.setText(getString(R.string.online_vpn_needed));
                    startActivityForResult(vpnIntent, REQ_VPN);
                } else {
                    doJoin(result.parsed);
                }
                break;
        }
    }

    /** 已授权，启动组网。 */
    private void doJoin(InviteCode.Parsed parsed) {
        statusText.setTextColor(getResources().getColor(R.color.primary, getTheme()));
        statusText.setText(getString(R.string.online_connecting_kernel));
        EasyTierManager.get().join(this, parsed.networkName, parsed.networkSecret, this);
    }

    private void onDisconnectClicked() {
        EasyTierManager.get().stop(this);
        disconnectButton.setVisibility(View.GONE);
        statusText.setVisibility(View.VISIBLE);
        statusText.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
        statusText.setText(getString(R.string.online_disconnected));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VPN) {
            return;
        }
        InviteCode.Parsed p = pendingJoin;
        pendingJoin = null;
        statusText.setVisibility(View.VISIBLE);
        if (resultCode == RESULT_OK && p != null) {
            doJoin(p);
        } else {
            statusText.setTextColor(getResources().getColor(R.color.error, getTheme()));
            statusText.setText(getString(R.string.online_vpn_cancelled));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        EasyTierManager.get().setListener(this);
        EasyTierManager.State s = EasyTierManager.get().getState();
        if (s == EasyTierManager.State.CONNECTED) {
            onState(s, EasyTierManager.get().getVirtualIp());
        }
    }

    @Override
    public void onState(EasyTierManager.State state, String detail) {
        statusText.setVisibility(View.VISIBLE);
        switch (state) {
            case STARTING:
                statusText.setTextColor(getResources().getColor(R.color.primary, getTheme()));
                statusText.setText(getString(R.string.online_connecting_kernel));
                disconnectButton.setVisibility(View.GONE);
                break;
            case WAIT_IP:
                statusText.setTextColor(getResources().getColor(R.color.primary, getTheme()));
                statusText.setText(getString(R.string.online_wait_ip));
                disconnectButton.setVisibility(View.GONE);
                break;
            case CONNECTED:
                statusText.setTextColor(getResources().getColor(R.color.primary, getTheme()));
                statusText.setText(getString(R.string.online_connected_fmt, detail));
                disconnectButton.setVisibility(View.VISIBLE);
                break;
            case FAILED:
                statusText.setTextColor(getResources().getColor(R.color.error, getTheme()));
                statusText.setText(getString(R.string.online_err_connect_fmt,
                        detail == null ? "?" : detail));
                disconnectButton.setVisibility(View.GONE);
                break;
            default:
                break;
        }
    }
}
