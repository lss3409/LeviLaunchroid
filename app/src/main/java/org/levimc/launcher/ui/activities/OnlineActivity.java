package org.levimc.launcher.ui.activities;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import android.widget.Toast;

import org.levimc.launcher.R;
import org.levimc.launcher.core.online.EasyTierManager;
import org.levimc.launcher.core.online.InviteCode;
import org.levimc.launcher.core.online.LanDiscovery;
import org.levimc.launcher.core.online.RelayStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 联机页（v498 重设计）：状态卡 / 房间卡 / 加入卡 / 创建卡 / 中转设置。
 * 加入交互：粘贴按钮 + 实时校验反馈 + 连接阶段进度。
 */
public final class OnlineActivity extends BaseActivity implements EasyTierManager.Listener {

    private static final int REQ_VPN = 1001;
    private static final String HOST_IPV4 = "10.144.144.144";

    private EditText codeInput;
    private TextView stateText;
    private TextView roomCode;
    private TextView relayValue;
    private TextView codeFeedback;
    private View stateDot;
    private View stateProgress;
    private View disconnectButton;
    private View roomCard;
    private boolean formatting;
    private InviteCode.Parsed pendingJoin;
    private String hostedCode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_online);
        setActiveNavTab(R.id.nav_tab_online);

        codeInput = findViewById(R.id.online_code_input);
        stateText = findViewById(R.id.online_state_text);
        roomCode = findViewById(R.id.online_room_code);
        relayValue = findViewById(R.id.online_relay_value);
        codeFeedback = findViewById(R.id.online_code_feedback);
        stateDot = findViewById(R.id.online_state_dot);
        stateProgress = findViewById(R.id.online_state_progress);
        disconnectButton = findViewById(R.id.online_disconnect_button);
        roomCard = findViewById(R.id.online_room_card);

        findViewById(R.id.online_join_button).setOnClickListener(v -> onJoinClicked());
        disconnectButton.setOnClickListener(v -> onDisconnectClicked());
        findViewById(R.id.online_create_button).setOnClickListener(v -> onCreateRoomClicked());
        findViewById(R.id.online_copy_button).setOnClickListener(v -> {
            if (hostedCode == null) {
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("invite", "P/" + hostedCode));
            Toast.makeText(this, R.string.online_copied, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.online_share_button).setOnClickListener(v -> {
            if (hostedCode == null) {
                return;
            }
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, getString(R.string.online_share_text, "P/" + hostedCode));
            startActivity(Intent.createChooser(send, getString(R.string.online_share_code)));
        });
        findViewById(R.id.online_paste_button).setOnClickListener(v -> onPasteClicked());
        findViewById(R.id.online_relay_button).setOnClickListener(v -> onRelayClicked());

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
                updateCodeFeedback();
            }
        });

        updateRelayView();
        setStateView(EasyTierManager.State.IDLE, null);
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

    /** 实时校验反馈：x/16 进度 → 完整时校验结果。 */
    private void updateCodeFeedback() {
        String text = codeInput.getText() == null ? "" : codeInput.getText().toString();
        int rawLen = 0;
        for (char ch : text.toUpperCase().toCharArray()) {
            if (InviteCode.CHARSET.indexOf(ch) >= 0) {
                rawLen++;
            }
        }
        codeFeedback.setVisibility(View.VISIBLE);
        if (rawLen == 0) {
            codeFeedback.setVisibility(View.GONE);
            return;
        }
        if (rawLen < 16) {
            codeFeedback.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
            codeFeedback.setText(getString(R.string.online_code_progress_fmt, rawLen));
            return;
        }
        InviteCode.Result r = InviteCode.parse(InviteCode.formatInput(text));
        if (r.ok()) {
            codeFeedback.setTextColor(getResources().getColor(R.color.primary, getTheme()));
            codeFeedback.setText(getString(R.string.online_code_valid));
        } else {
            codeFeedback.setTextColor(getResources().getColor(R.color.error, getTheme()));
            switch (r.error) {
                case CHARSET:
                    codeFeedback.setText(getString(R.string.online_err_charset));
                    break;
                case CHECKSUM:
                    codeFeedback.setText(getString(R.string.online_err_checksum));
                    break;
                default:
                    codeFeedback.setText(getString(R.string.online_err_format));
                    break;
            }
        }
    }

    private void onPasteClicked() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                return;
            }
            CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
            if (clip == null || clip.length() == 0) {
                return;
            }
            codeInput.setText(InviteCode.formatInput(clip.toString()));
            codeInput.setSelection(codeInput.length());
            updateCodeFeedback();
        } catch (Exception ignored) {
        }
    }

    private void onRelayClicked() {
        List<String> current = RelayStore.load(this);
        EditText input = new EditText(this);
        input.setHint(R.string.online_relay_dialog_hint);
        input.setSingleLine(false);
        input.setText(String.join(", ", current));
        new AlertDialog.Builder(this)
                .setTitle(R.string.online_relay_dialog_title)
                .setMessage(R.string.online_relay_dialog_msg)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    List<String> uris = new ArrayList<>();
                    for (String part : input.getText().toString().split("[,\\n]")) {
                        String u = RelayStore.normalize(part);
                        if (!u.isEmpty()) {
                            uris.add(u);
                        }
                    }
                    RelayStore.save(this, uris);
                    updateRelayView();
                    Toast.makeText(this, R.string.online_relay_saved, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateRelayView() {
        List<String> uris = RelayStore.load(this);
        if (uris.isEmpty()) {
            relayValue.setText(getString(R.string.online_relay_none));
        } else {
            relayValue.setText(uris.get(0) + (uris.size() > 1 ? " …" : ""));
        }
    }

    private void onJoinClicked() {
        String raw = codeInput.getText() == null ? "" : codeInput.getText().toString();
        InviteCode.Result result = InviteCode.parse(InviteCode.formatInput(raw));
        if (!result.ok()) {
            codeFeedback.setVisibility(View.VISIBLE);
            codeFeedback.setTextColor(getResources().getColor(R.color.error, getTheme()));
            switch (result.error) {
                case CHARSET:
                    codeFeedback.setText(getString(R.string.online_err_charset));
                    break;
                case CHECKSUM:
                    codeFeedback.setText(getString(R.string.online_err_checksum));
                    break;
                default:
                    codeFeedback.setText(getString(R.string.online_err_format));
                    break;
            }
            return;
        }
        pendingJoin = result.parsed;
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            setStateText(getString(R.string.online_vpn_needed), R.color.text_secondary);
            startActivityForResult(vpnIntent, REQ_VPN);
        } else {
            doJoin(result.parsed);
        }
    }

    /** 创建房间（v497）：生成邀请码 → 房主固定 IP 组网 → 局域网广播应答。 */
    private void onCreateRoomClicked() {
        EasyTierManager.get().stop(this);
        InviteCode.Generated g;
        try {
            g = InviteCode.generate();
        } catch (RuntimeException e) {
            setStateText(getString(R.string.online_err_connect_fmt, e.getMessage()), R.color.error);
            return;
        }
        hostedCode = g.code;
        roomCode.setText("P/" + g.code);
        roomCard.setVisibility(View.VISIBLE);
        LanDiscovery.startHost(g.parsed.networkName);
        // 房主也必须连中转——跨网段成员经中转才能找到房主
        List<String> relayPeers = RelayStore.load(this);
        EasyTierManager.get().host(this, g.parsed.networkName, g.parsed.networkSecret, this,
                HOST_IPV4, relayPeers);
    }

    /** 已授权，启动组网（成员：局域网发现房主 + 合并中转配置）。 */
    private void doJoin(InviteCode.Parsed parsed) {
        String net = parsed.networkName;
        String secret = parsed.networkSecret;
        setStateText(getString(R.string.online_state_discovering), R.color.primary);
        stateProgress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            List<String> peers = LanDiscovery.discover(net, 3000);
            peers.addAll(RelayStore.load(this));
            runOnUiThread(() -> EasyTierManager.get().join(this, net, secret, this, peers));
        }, "lan-discover").start();
    }

    private void onDisconnectClicked() {
        EasyTierManager.get().stop(this);
        LanDiscovery.stopHost();
        hostedCode = null;
        roomCard.setVisibility(View.GONE);
        setStateView(EasyTierManager.State.IDLE, null);
        setStateText(getString(R.string.online_disconnected), R.color.text_secondary);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VPN) {
            return;
        }
        InviteCode.Parsed p = pendingJoin;
        pendingJoin = null;
        if (resultCode == RESULT_OK && p != null) {
            doJoin(p);
        } else {
            setStateText(getString(R.string.online_vpn_cancelled), R.color.error);
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

    private void setStateText(String text, int colorRes) {
        stateText.setText(text);
        stateText.setTextColor(getResources().getColor(colorRes, getTheme()));
    }

    private void setStateDot(int drawableRes) {
        stateDot.setBackgroundResource(drawableRes);
    }

    private void setStateView(EasyTierManager.State state, String detail) {
        switch (state) {
            case STARTING:
                setStateDot(R.drawable.bg_state_dot_idle);
                setStateText(getString(R.string.online_connecting_kernel), R.color.primary);
                stateProgress.setVisibility(View.VISIBLE);
                disconnectButton.setVisibility(View.GONE);
                break;
            case WAIT_IP:
                setStateDot(R.drawable.bg_state_dot_idle);
                setStateText(getString(R.string.online_wait_ip), R.color.primary);
                stateProgress.setVisibility(View.VISIBLE);
                disconnectButton.setVisibility(View.GONE);
                break;
            case CONNECTED:
                setStateDot(R.drawable.bg_state_dot_ok);
                setStateText(getString(R.string.online_state_connected_fmt, detail == null ? "?" : detail),
                        R.color.primary);
                stateProgress.setVisibility(View.GONE);
                disconnectButton.setVisibility(View.VISIBLE);
                break;
            case FAILED:
                setStateDot(R.drawable.bg_state_dot_err);
                setStateText(getString(R.string.online_err_connect_fmt, detail == null ? "?" : detail),
                        R.color.error);
                stateProgress.setVisibility(View.GONE);
                disconnectButton.setVisibility(View.GONE);
                break;
            default:
                setStateDot(R.drawable.bg_state_dot_idle);
                setStateText(getString(R.string.online_state_idle), R.color.text_secondary);
                stateProgress.setVisibility(View.GONE);
                disconnectButton.setVisibility(View.GONE);
                break;
        }
    }

    @Override
    public void onState(EasyTierManager.State state, String detail) {
        setStateView(state, detail);
    }
}
