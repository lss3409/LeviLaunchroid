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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.levimc.launcher.R;
import org.levimc.launcher.core.online.EasyTierManager;
import org.levimc.launcher.core.online.InviteCode;
import org.levimc.launcher.core.online.LanBridge;
import org.levimc.launcher.core.online.LanDiscovery;
import org.levimc.launcher.core.online.PlayerIdentity;
import org.levimc.launcher.core.online.QrUtils;
import org.levimc.launcher.core.online.RelayStore;
import org.levimc.launcher.core.online.RoomCenter;

import java.util.ArrayList;
import java.util.List;

/**
 * 联机页（v500 按提示词重设计）：
 * 首页（状态条+创建/加入并排卡片+最近房间+中转入口）/ 创建视图 / 房间视图；
 * 加入房间为弹窗（输入+实时校验+连接步骤条动画 解析→组网→发现房主→握手）。
 */
public final class OnlineActivity extends BaseActivity implements EasyTierManager.Listener {

    private static final int REQ_VPN = 1001;
    private static final String HOST_IPV4 = "10.144.144.144";
    private static final int GAME_PORT = 19132;
    private static final String PREFS_RECENT = "levimc_recent";
    private static final String KEY_RECENT = "rooms";

    private View homeView;
    private View createView;
    private View roomView;
    private TextView stateText;
    private TextView relayValue;
    private View stateDot;
    private View stateProgress;
    private View disconnectButton;
    private TextView roomCodeText;
    private TextView roomCodeText2;
    private TextView roomState;
    private TextView roomLatency;
    private TextView hostAvatar;
    private TextView hostName;
    private TextView joinGameHint;
    private LinearLayout playersContainer;
    private TextView createStatus;

    private boolean formatting;
    private InviteCode.Parsed pendingJoin;
    private String currentCode; // 当前房间码（房主生成/成员加入）
    private String hostAddress; // 房主虚拟 IP:游戏端口
    private AlertDialog joinDialog;
    private boolean isHost;
    private final List<InviteCode.Parsed> pendingParsed = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_online);
        setActiveNavTab(R.id.nav_tab_online);

        homeView = findViewById(R.id.online_home_view);
        createView = findViewById(R.id.online_create_view);
        roomView = findViewById(R.id.online_room_view);
        stateText = findViewById(R.id.online_state_text);
        relayValue = findViewById(R.id.online_relay_value);
        stateDot = findViewById(R.id.online_state_dot);
        stateProgress = findViewById(R.id.online_state_progress);
        disconnectButton = findViewById(R.id.online_disconnect_button);
        roomCodeText = findViewById(R.id.online_room_code);
        roomCodeText2 = findViewById(R.id.online_room_code2);
        roomState = findViewById(R.id.online_room_state);
        roomLatency = findViewById(R.id.online_room_latency);
        hostAvatar = findViewById(R.id.online_host_avatar);
        hostName = findViewById(R.id.online_host_name);
        joinGameHint = findViewById(R.id.online_join_game_hint);
        playersContainer = findViewById(R.id.online_players_container);
        createStatus = findViewById(R.id.online_create_status);

        findViewById(R.id.online_create_card).setOnClickListener(v -> onCreateRoomClicked());
        findViewById(R.id.online_join_card).setOnClickListener(v -> showJoinDialog());
        findViewById(R.id.online_disconnect_button).setOnClickListener(v -> onLeaveClicked());
        findViewById(R.id.online_leave_button).setOnClickListener(v -> onLeaveClicked());
        findViewById(R.id.online_copy_button).setOnClickListener(v -> copyCurrentCode());
        findViewById(R.id.online_room_copy_button).setOnClickListener(v -> copyCurrentCode());
        findViewById(R.id.online_share_button).setOnClickListener(v -> shareCurrentCode());
        findViewById(R.id.online_room_share_button).setOnClickListener(v -> shareCurrentCode());
        findViewById(R.id.online_qr_button).setOnClickListener(v -> showQrDialog());
        findViewById(R.id.online_back_home_button).setOnClickListener(v -> showHome());
        findViewById(R.id.online_join_game_button).setOnClickListener(v -> {
            if (hostAddress != null) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("host", hostAddress));
                Toast.makeText(this, getString(R.string.online_copied), Toast.LENGTH_SHORT).show();
            }
        });

        updateRelayView();
        refreshRecent();
        setHomeState(EasyTierManager.State.IDLE, null);

        handleDebugJoinIntent(getIntent());
    }

    /**
     * 调试后门（v511）：部分设备（vivo OriginOS）系统拦截调试广播且 adb 输入乱码，
     * 改用 Activity 启动参数直接触发加入流程（Activity 启动不受广播拦截影响）：
     * adb shell am start -n org.levimc.launcher/.ui.activities.OnlineActivity \
     *   --es debug_join_code 8ZOZMCJDZFFAL450 --es debug_join_peer tcp://111.230.150.198:11010
     * debug_join_peer 可选；不传则走局域网发现 + 固定中转。
     */
    private void handleDebugJoinIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        // v516 调试建房：固定邀请码 TEST-TEST-TEST-TES5（免 OCR 读码）
        if (intent.getBooleanExtra("debug_host", false)) {
            InviteCode.Result hr = InviteCode.parse(InviteCode.formatInput("TESTTESTTESTTES5"));
            if (!hr.ok()) {
                hr = InviteCode.parse(InviteCode.formatInput("TESTTESTTESTTESC"));
            }
            if (!hr.ok()) {
                Toast.makeText(this, "debug_host 固定码解析失败", Toast.LENGTH_SHORT).show();
                return;
            }
            EasyTierManager.get().stop(this);
            currentCode = rawToCode(hr.parsed);
            isHost = true;
            roomCodeText.setText("P/" + currentCode);
            createStatus.setText(getString(R.string.online_connecting_kernel));
            showCreate();
            LanDiscovery.startHost(hr.parsed.networkName);
            List<String> relayPeers = RelayStore.load(this);
            EasyTierManager.get().host(this, hr.parsed.networkName, hr.parsed.networkSecret, this,
                    HOST_IPV4, relayPeers);
            return;
        }
        String code = intent.getStringExtra("debug_join_code");
        if (code == null || code.isEmpty()) {
            return;
        }
        InviteCode.Result r = InviteCode.parse(InviteCode.formatInput(code));
        if (!r.ok()) {
            Toast.makeText(this, "debug_join_code 无效", Toast.LENGTH_SHORT).show();
            return;
        }
        String peer = intent.getStringExtra("debug_join_peer");
        currentCode = rawToCode(r.parsed);
        isHost = false;
        // 与 UI 加入流程一致：先过 VpnService.prepare 授权（未授权时 TUN 建不起来，
        // 内核侧却会照常"连上"——v512 在 vivo 上踩过的坑），授权后走 REQ_VPN 回调
        // doJoinFromDialog（v511 起已合并固定中转，异地可用）。
        pendingParsed.clear();
        pendingParsed.add(r.parsed);
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            Toast.makeText(this, "请允许 VPN 连接以完成联机", Toast.LENGTH_SHORT).show();
            startActivityForResult(vpnIntent, REQ_VPN);
        } else if (peer != null && !peer.isEmpty()) {
            debugJoin(r.parsed, peer);
        } else {
            doJoinFromDialog(r.parsed);
        }
    }

    /** 调试路径的显式 peer 直连加入（无授权弹窗时用）。 */
    private void debugJoin(InviteCode.Parsed parsed, String explicitPeer) {
        new Thread(() -> {
            List<String> peers = new ArrayList<>();
            peers.add(explicitPeer);
            for (String p : RelayStore.load(this)) {
                if (!peers.contains(p)) {
                    peers.add(p);
                }
            }
            runOnUiThread(() -> EasyTierManager.get().join(this,
                    parsed.networkName, parsed.networkSecret, this, peers));
        }, "debug-join").start();
    }

    // ---------- 视图切换 ----------

    /** 是否存在输入法组合区（拼音/联想等尚未提交的文本）。 */
    private static boolean isComposing(Editable s) {
        for (Object span : s.getSpans(0, s.length(), Object.class)) {
            if ((s.getSpanFlags(span) & Spanned.SPAN_COMPOSING) != 0) {
                return true;
            }
        }
        return false;
    }

    private void showHome() {
        homeView.setVisibility(View.VISIBLE);
        createView.setVisibility(View.GONE);
        roomView.setVisibility(View.GONE);
    }

    private void showCreate() {
        homeView.setVisibility(View.GONE);
        createView.setVisibility(View.VISIBLE);
        roomView.setVisibility(View.GONE);
    }

    private void showRoom() {
        homeView.setVisibility(View.GONE);
        createView.setVisibility(View.GONE);
        roomView.setVisibility(View.VISIBLE);
        roomView.setAlpha(0f);
        roomView.animate().alpha(1f).setDuration(220).start();
        populateRoom();
    }

    /** 房间视图数据填充。 */
    private void populateRoom() {
        roomCodeText2.setText(currentCode == null ? "" : "P/" + currentCode);
        String nick = PlayerIdentity.getNickname(this);
        if (isHost) {
            hostAvatar.setText(firstChar(nick));
            hostName.setText(getString(R.string.online_host_you, nick));
        } else {
            // 成员视角：房主昵称由玩家列表心跳获取（isRoomHost），未获取前占位
            hostAvatar.setText("房");
            hostName.setText(getString(R.string.online_host_unknown));
        }
        // Xbox 头像覆盖（有 URL 时）
        String avatarUrl = PlayerIdentity.getAvatarUrl(this);
        android.widget.ImageView hostImg = findViewById(R.id.online_host_avatar_img);
        if (hostImg != null) {
            hostImg.setImageDrawable(null);
            if (avatarUrl != null && !avatarUrl.isEmpty()) {
                com.bumptech.glide.Glide.with(this).load(avatarUrl).circleCrop().into(hostImg);
            }
        }
        hostAddress = (isHost ? EasyTierManager.get().getVirtualIp() : HOST_IPV4) + ":" + GAME_PORT;
        if (hostAddress.startsWith("null")) {
            hostAddress = HOST_IPV4 + ":" + GAME_PORT;
        }
        joinGameHint.setText(getString(R.string.online_join_game_steps)
                + "\n\n" + getString(R.string.online_join_game_hint, hostAddress));
        playersContainer.removeAllViews();
        // 初始占位（心跳回调后重建）：房主行已展示自己，列表不再重复
        TextView empty = new TextView(this);
        empty.setText(R.string.online_players_empty);
        empty.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
        empty.setTextSize(12);
        playersContainer.addView(empty);
    }

    private static String firstChar(String s) {
        return s == null || s.isEmpty() ? "?" : s.substring(0, 1);
    }

    /** 邀请码二维码弹窗（v505）。 */
    private void showQrDialog() {
        if (currentCode == null) {
            return;
        }
        android.graphics.Bitmap qr = QrUtils.generate("P/" + currentCode, 480);
        if (qr == null) {
            Toast.makeText(this, "二维码生成失败", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.setPadding(48, 32, 48, 16);
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(qr);
        wrap.addView(iv);
        TextView label = new TextView(this);
        label.setText("P/" + currentCode);
        label.setTextSize(14);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setTextColor(getResources().getColor(R.color.on_surface, getTheme()));
        label.setPadding(0, 12, 0, 0);
        wrap.addView(label);
        new AlertDialog.Builder(this)
                .setTitle(R.string.online_qr)
                .setView(wrap)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 更新连接模式徽章（v505：P2P 直连绿 / 中继模式黄；v519：未知灰——
     *  房主端此前检测不到数据时"未知"冒充了 P2P 绿，现如实显示）。 */
    private void updateConnModeBadge() {
        EasyTierManager.ConnMode m = EasyTierManager.get().getConnMode();
        if (m == EasyTierManager.ConnMode.RELAY) {
            roomState.setText(getString(R.string.online_step_relay));
            roomState.setTextColor(getResources().getColor(R.color.warning, getTheme()));
        } else if (m == EasyTierManager.ConnMode.P2P) {
            roomState.setText(getString(R.string.online_step_p2p));
            roomState.setTextColor(getResources().getColor(R.color.primary, getTheme()));
        } else {
            roomState.setText(getString(R.string.online_step_unknown));
            roomState.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
        }
    }

    /** 玩家行（v502）：首字头像 + 昵称 + 房主皇冠 + 自己高亮。 */
    private void addPlayerRow(String name, boolean isSelf, boolean isRoomHost) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 10, 0, 10);

        int avatarSize = (int) (34 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout avatarFrame = new android.widget.FrameLayout(this);
        TextView avatar = new TextView(this);
        avatar.setText(firstChar(name));
        avatar.setGravity(Gravity.CENTER);
        avatar.setTextColor(getResources().getColor(R.color.on_primary, getTheme()));
        avatar.setTextSize(13);
        avatar.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        avatar.setBackgroundResource(R.drawable.bg_avatar);
        avatarFrame.addView(avatar, new android.widget.FrameLayout.LayoutParams(
                avatarSize, avatarSize));
        // Xbox 头像（有 URL 时 Glide 覆盖首字底）
        String avatarUrl = PlayerIdentity.getAvatarUrl(this);
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            com.bumptech.glide.Glide.with(this).load(avatarUrl).circleCrop().into(iv);
            avatarFrame.addView(iv, new android.widget.FrameLayout.LayoutParams(
                    avatarSize, avatarSize));
        }
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        row.addView(avatarFrame, alp);

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(14);
        label.setTextColor(getResources().getColor(
                isSelf ? R.color.primary : R.color.on_surface, getTheme()));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        llp.leftMargin = (int) (10 * getResources().getDisplayMetrics().density);
        row.addView(label, llp);

        if (isRoomHost) {
            TextView crown = new TextView(this);
            crown.setText("👑");
            crown.setTextSize(14);
            row.addView(crown);
        }
        playersContainer.addView(row);
    }

    /** 玩家列表心跳回调（工作线程）。 */
    private void onRoomPlayers(List<RoomCenter.Player> list, long rttMs) {
        runOnUiThread(() -> {
            if (!roomView.isShown() && joinDialog == null) {
                return;
            }
            if (rttMs > 0) {
                roomLatency.setText(getString(R.string.online_room_latency_fmt, rttMs));
            }
            updateConnModeBadge();
            String selfId = PlayerIdentity.getClientId(this);
            String nick = PlayerIdentity.getNickname(this);
            playersContainer.removeAllViews();
            int memberCount = 1;
            if (isHost) {
                // 房主视角：房主行已展示自己，玩家列表只列成员
                for (RoomCenter.Player p : list) {
                    if (!p.isRoomHost) {
                        addPlayerRow(p.name, false, false);
                        memberCount++;
                    }
                }
                if (memberCount == 1) {
                    TextView empty = new TextView(this);
                    empty.setText(R.string.online_players_empty);
                    empty.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
                    empty.setTextSize(12);
                    playersContainer.addView(empty);
                }
            } else {
                // 成员视角：房主在专属房主行展示（👑），玩家列表只放其他成员 + 自己（高亮）
                // （v514 修复：房主不再重复出现在列表里）
                for (RoomCenter.Player p : list) {
                    if (p.isRoomHost) {
                        hostAvatar.setText(firstChar(p.name));
                        hostName.setText(p.name);
                        memberCount++;
                    }
                }
                for (RoomCenter.Player p : list) {
                    if (!p.isRoomHost && !p.clientId.equals(selfId)) {
                        addPlayerRow(p.name, false, false);
                        memberCount++;
                    }
                }
                addPlayerRow(nick, true, false);
                if (memberCount == 1) {
                    TextView empty = new TextView(this);
                    empty.setText(R.string.online_players_empty);
                    empty.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
                    empty.setTextSize(12);
                    playersContainer.addView(empty);
                }
            }
            TextView label = findViewById(R.id.online_players_label);
            if (label != null) {
                label.setText(getString(R.string.online_players_count_fmt,
                        memberCount, RoomCenter.MAX_PLAYERS));
            }
            // v520：成员端提示房主世界开启状态（心跳响应带回）
            if (!isHost && joinGameHint != null) {
                if (RoomCenter.hostGameOpen) {
                    joinGameHint.setText(R.string.online_game_open);
                } else {
                    joinGameHint.setText(R.string.online_game_wait);
                }
            }
        });
    }

    // ---------- 创建房间 ----------

    private void onCreateRoomClicked() {
        EasyTierManager.get().stop(this);
        InviteCode.Generated g;
        try {
            g = InviteCode.generate();
        } catch (RuntimeException e) {
            setHomeState(EasyTierManager.State.FAILED, e.getMessage());
            return;
        }
        currentCode = g.code;
        isHost = true;
        roomCodeText.setText("P/" + g.code);
        createStatus.setText(getString(R.string.online_connecting_kernel));
        showCreate();
        LanDiscovery.startHost(g.parsed.networkName);
        List<String> relayPeers = RelayStore.load(this);
        EasyTierManager.get().host(this, g.parsed.networkName, g.parsed.networkSecret, this,
                HOST_IPV4, relayPeers);
    }

    // ---------- 加入房间弹窗 ----------

    private void showJoinDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_join, null);
        EditText input = v.findViewById(R.id.join_code_input);
        TextView feedback = v.findViewById(R.id.join_feedback);
        LinearLayout steps = v.findViewById(R.id.join_steps_container);
        TextView status = v.findViewById(R.id.join_status);

        v.findViewById(R.id.join_paste_button).setOnClickListener(x -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
                    CharSequence clip = cm.getPrimaryClip().getItemAt(0).getText();
                    if (clip != null) {
                        input.setText(InviteCode.formatInput(clip.toString()));
                        input.setSelection(input.length());
                        updateJoinFeedback(input, feedback);
                    }
                }
            } catch (Exception ignored) {
            }
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (formatting || isComposing(s)) {
                    return;
                }
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
                updateJoinFeedback(input, feedback);
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(v)
                .setCancelable(false)
                .create();
        joinDialog = dialog;

        v.findViewById(R.id.join_cancel_button).setOnClickListener(x -> dialog.dismiss());
        v.findViewById(R.id.join_confirm_button).setOnClickListener(x -> {
            String raw = input.getText() == null ? "" : input.getText().toString();
            InviteCode.Result r = InviteCode.parse(InviteCode.formatInput(raw));
            if (!r.ok()) {
                feedback.setVisibility(View.VISIBLE);
                feedback.setTextColor(getResources().getColor(R.color.error, getTheme()));
                feedback.setText(errorText(r.error));
                return;
            }
            // 校验通过：进入连接步骤动画
            feedback.setVisibility(View.GONE);
            input.setEnabled(false);
            v.findViewById(R.id.join_paste_button).setEnabled(false);
            v.findViewById(R.id.join_confirm_button).setVisibility(View.GONE);
            v.findViewById(R.id.join_cancel_button).setVisibility(View.GONE);
            buildSteps(steps);
            setStepState(0, true);
            pendingParsed.clear();
            pendingParsed.add(r.parsed);
            Intent vpnIntent = VpnService.prepare(this);
            if (vpnIntent != null) {
                status.setVisibility(View.VISIBLE);
                status.setText(getString(R.string.online_vpn_needed));
                startActivityForResult(vpnIntent, REQ_VPN);
            } else {
                doJoinFromDialog(r.parsed);
            }
        });
        dialog.setOnDismissListener(d -> {
            joinDialog = null;
            if (!pendingParsed.isEmpty()) {
                // 连接中关闭弹窗 = 取消（停止组网）
                pendingParsed.clear();
                EasyTierManager.get().stop(this);
                showHome();
                setHomeState(EasyTierManager.State.IDLE, null);
            }
        });
        dialog.show();
    }

    private void doJoinFromDialog(InviteCode.Parsed parsed) {
        currentCode = rawToCode(parsed);
        isHost = false;
        setStepState(1, true);
        new Thread(() -> {
            List<String> peers = new ArrayList<>(LanDiscovery.discover(parsed.networkName, 3000));
            // 合并固定中转：异地/流量联机时局域网发现不到房主，必须靠中转牵线（v511 修复）
            for (String p : RelayStore.load(this)) {
                if (!peers.contains(p)) {
                    peers.add(p);
                }
            }
            runOnUiThread(() -> {
                if (joinDialog == null) {
                    return;
                }
                setStepState(2, true);
                EasyTierManager.get().join(this, parsed.networkName, parsed.networkSecret,
                        this, peers);
            });
        }, "lan-discover").start();
    }

    private String rawToCode(InviteCode.Parsed p) {
        String name = p.networkName; // paper-connect-NNNN-NNNN
        String code = name.substring("paper-connect-".length()).replace("-", "");
        return code.substring(0, 4) + "-" + code.substring(4, 8) + "-"
                + p.networkSecret.substring(0, 4) + "-" + p.networkSecret.substring(5);
    }

    private String errorText(InviteCode.Error e) {
        switch (e) {
            case CHARSET:
                return getString(R.string.online_err_charset);
            case CHECKSUM:
                return getString(R.string.online_err_checksum);
            default:
                return getString(R.string.online_err_format);
        }
    }

    private void updateJoinFeedback(EditText input, TextView feedback) {
        String text = input.getText() == null ? "" : input.getText().toString();
        int rawLen = 0;
        for (char ch : text.toUpperCase().toCharArray()) {
            if (InviteCode.CHARSET.indexOf(ch) >= 0) {
                rawLen++;
            }
        }
        if (rawLen == 0) {
            feedback.setVisibility(View.GONE);
            return;
        }
        feedback.setVisibility(View.VISIBLE);
        if (rawLen < 16) {
            feedback.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
            feedback.setText(getString(R.string.online_code_progress_fmt, rawLen));
            return;
        }
        InviteCode.Result r = InviteCode.parse(InviteCode.formatInput(text));
        if (r.ok()) {
            feedback.setTextColor(getResources().getColor(R.color.primary, getTheme()));
            feedback.setText(getString(R.string.online_code_valid));
        } else {
            feedback.setTextColor(getResources().getColor(R.color.error, getTheme()));
            feedback.setText(errorText(r.error));
        }
    }

    // ---------- 连接步骤条 ----------

    private TextView[] stepViews;

    private void buildSteps(LinearLayout container) {
        container.removeAllViews();
        container.setVisibility(View.VISIBLE);
        int[] labels = {
                R.string.online_step_parse,
                R.string.online_step_connect,
                R.string.online_step_discover,
                R.string.online_step_handshake
        };
        stepViews = new TextView[labels.length];
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < labels.length; i++) {
            LinearLayout step = new LinearLayout(this);
            step.setOrientation(LinearLayout.HORIZONTAL);
            step.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            step.setLayoutParams(slp);
            View dot = new View(this);
            dot.setBackgroundResource(R.drawable.bg_state_dot_idle);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    (int) (9 * d), (int) (9 * d));
            step.addView(dot, dlp);
            dot.setTag("dot");
            TextView label = new TextView(this);
            label.setText(labels[i]);
            label.setTextSize(11);
            label.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.leftMargin = (int) (5 * d);
            step.addView(label, llp);
            label.setTag("label");
            container.addView(step);
            stepViews[i] = label;
        }
        setStepState(0, false);
    }

    /** 步骤状态：done=true 的前 N 步完成（绿点），active 为当前步（高亮）。 */
    private void setStepState(int active, boolean done) {
        if (stepViews == null) {
            return;
        }
        for (int i = 0; i < stepViews.length; i++) {
            View step = (View) stepViews[i].getParent();
            View dot = step.findViewWithTag("dot");
            TextView label = (TextView) step.findViewWithTag("label");
            boolean isDone = i < active || done && i <= active && i != active;
            boolean isActive = i == active;
            if (isDone) {
                dot.setBackgroundResource(R.drawable.bg_state_dot_ok);
                label.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
            } else if (isActive) {
                dot.setBackgroundResource(R.drawable.bg_state_dot_ok);
                label.setTextColor(getResources().getColor(R.color.primary, getTheme()));
            } else {
                dot.setBackgroundResource(R.drawable.bg_state_dot_idle);
                label.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
            }
        }
    }

    // ---------- 最近房间 ----------

    private void refreshRecent() {
        LinearLayout container = findViewById(R.id.online_recent_container);
        View title = findViewById(R.id.online_recent_title);
        container.removeAllViews();
        List<String> recents = loadRecent();
        if (recents.isEmpty()) {
            container.setVisibility(View.GONE);
            title.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);
        title.setVisibility(View.VISIBLE);
        for (String code : recents) {
            TextView row = new TextView(this);
            row.setText("P/" + code);
            row.setTextColor(getResources().getColor(R.color.primary, getTheme()));
            row.setTextSize(14);
            row.setPadding(24, 14, 24, 14);
            row.setBackgroundResource(R.drawable.bg_rounded_card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (6 * getResources().getDisplayMetrics().density);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> {
                // 点击最近房间：预填弹窗输入框
                showJoinDialogWithCode(code);
            });
            // 长按删除记录
            row.setOnLongClickListener(v -> {
                new android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.online_recent_delete)
                        .setMessage("P/" + code)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            List<String> recentList = loadRecent();
                            recentList.remove(code);
                            getSharedPreferences(PREFS_RECENT, MODE_PRIVATE).edit()
                                    .putString(KEY_RECENT, String.join(",", recentList)).apply();
                            refreshRecent();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            });
            container.addView(row);
        }
    }

    private void showJoinDialogWithCode(String code) {
        showJoinDialog();
        if (joinDialog != null) {
            EditText input = joinDialog.findViewById(R.id.join_code_input);
            input.setText(InviteCode.formatInput(code));
            input.setSelection(input.length());
        }
    }

    private List<String> loadRecent() {
        List<String> out = new ArrayList<>();
        try {
            String raw = getSharedPreferences(PREFS_RECENT, MODE_PRIVATE)
                    .getString(KEY_RECENT, "");
            if (raw != null && !raw.isEmpty()) {
                for (String c : raw.split(",")) {
                    if (!c.isEmpty() && !out.contains(c)) {
                        out.add(c);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void saveRecent(String code) {
        List<String> recents = loadRecent();
        recents.remove(code);
        recents.add(0, code);
        while (recents.size() > 5) {
            recents.remove(recents.size() - 1);
        }
        getSharedPreferences(PREFS_RECENT, MODE_PRIVATE).edit()
                .putString(KEY_RECENT, String.join(",", recents)).apply();
    }

    // ---------- 通用 ----------

    private void copyCurrentCode() {
        if (currentCode == null) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("invite", "P/" + currentCode));
        Toast.makeText(this, R.string.online_copied, Toast.LENGTH_SHORT).show();
    }

    private void shareCurrentCode() {
        if (currentCode == null) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, getString(R.string.online_share_text, "P/" + currentCode));
        startActivity(Intent.createChooser(send, getString(R.string.online_share_code)));
    }

    private void updateRelayView() {
        RelayStore.cleanupLegacy(this);
        relayValue.setText(getString(R.string.online_relay_support_fmt, RelayStore.SUPPORTED_BY));
        // 异步测试固定中转连通性（角标：绿=在线 / 灰=离线）
        new Thread(() -> {
            boolean ok = false;
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("192.168.1.167", 11010), 2000);
                ok = true;
            } catch (Exception ignored) {
            }
            boolean reachable = ok;
            runOnUiThread(() -> {
                View dot = findViewById(R.id.online_relay_dot);
                if (dot != null) {
                    dot.setBackgroundResource(reachable
                            ? R.drawable.bg_state_dot_ok : R.drawable.bg_state_dot_idle);
                }
                relayValue.setText(getString(reachable
                        ? R.string.online_relay_support_fmt : R.string.online_relay_offline,
                        RelayStore.SUPPORTED_BY));
            });
        }, "relay-check").start();
    }

    private void onLeaveClicked() {
        EasyTierManager.get().stop(this);
        LanDiscovery.stopHost();
        RoomCenter.stopHost();
        RoomCenter.stopClient();
        LanBridge.stopHost();
        RoomCenter.roomCode = null;
        RoomCenter.hostGameOpen = false;
        currentCode = null;
        isHost = false;
        hostAddress = null;
        showHome();
        setHomeState(EasyTierManager.State.IDLE, null);
    }

    private void setHomeState(EasyTierManager.State state, String detail) {
        switch (state) {
            case STARTING:
            case WAIT_IP:
                stateDot.setBackgroundResource(R.drawable.bg_state_dot_idle);
                stateText.setTextColor(getResources().getColor(R.color.primary, getTheme()));
                stateText.setText(state == EasyTierManager.State.STARTING
                        ? getString(R.string.online_connecting_kernel)
                        : getString(R.string.online_wait_ip));
                stateProgress.setVisibility(View.VISIBLE);
                disconnectButton.setVisibility(View.GONE);
                break;
            case CONNECTED:
                stateDot.setBackgroundResource(R.drawable.bg_state_dot_ok);
                stateText.setTextColor(getResources().getColor(R.color.primary, getTheme()));
                stateText.setText(getString(R.string.online_state_connected_fmt,
                        detail == null ? "?" : detail));
                stateProgress.setVisibility(View.GONE);
                disconnectButton.setVisibility(View.VISIBLE);
                break;
            case FAILED:
                stateDot.setBackgroundResource(R.drawable.bg_state_dot_err);
                stateText.setTextColor(getResources().getColor(R.color.error, getTheme()));
                stateText.setText(getString(R.string.online_err_connect_fmt,
                        detail == null ? "?" : detail));
                stateProgress.setVisibility(View.GONE);
                disconnectButton.setVisibility(View.GONE);
                break;
            default:
                stateDot.setBackgroundResource(R.drawable.bg_state_dot_idle);
                stateText.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
                stateText.setText(getString(R.string.online_state_idle));
                stateProgress.setVisibility(View.GONE);
                disconnectButton.setVisibility(View.GONE);
                break;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VPN) {
            return;
        }
        if (resultCode == RESULT_OK && !pendingParsed.isEmpty()) {
            doJoinFromDialog(pendingParsed.remove(0));
        } else {
            pendingParsed.clear();
            if (joinDialog != null) {
                joinDialog.dismiss();
            }
            Toast.makeText(this, R.string.online_vpn_cancelled, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateRelayView();
        refreshRecent();
        EasyTierManager.get().setListener(this);
        EasyTierManager.State s = EasyTierManager.get().getState();
        if (s == EasyTierManager.State.CONNECTED) {
            onState(s, EasyTierManager.get().getVirtualIp());
        }
    }

    @Override
    public void onState(EasyTierManager.State state, String detail) {
        switch (state) {
            case STARTING:
                if (joinDialog != null) {
                    setStepState(1, true);
                } else {
                    setHomeState(state, detail);
                }
                break;
            case WAIT_IP:
                if (joinDialog != null) {
                    setStepState(3, true);
                } else {
                    setHomeState(state, detail);
                }
                break;
            case CONNECTED:
                if (joinDialog != null) {
                    setStepState(3, true);
                    // 延迟关闭弹窗展示完成态
                    joinDialog.setOnDismissListener(null);
                    joinDialog.dismiss();
                    joinDialog = null;
                    pendingParsed.clear();
                }
                if (currentCode != null) {
                    saveRecent(currentCode);
                }
                // v521：会话状态写入静态区，供游戏内悬浮窗读取
                RoomCenter.roomCode = currentCode;
                RoomCenter.isHost = isHost;
                // v524：游戏正在前台时立即挂悬浮窗（否则要等游戏下次 onResume 才出现）
                try {
                    if (org.levimc.launcher.core.minecraft.MinecraftActivityState.isRunning()) {
                        android.app.Activity game = org.levimc.launcher.core.minecraft.MinecraftActivityState.getActivity();
                        if (game != null) {
                            OnlineOverlay.get(game).show();
                        }
                    }
                } catch (Throwable ignored) {
                }
                // v519：初始显示"检测中"，等轮询数据到达再亮 P2P/中继徽章
                roomState.setText(getString(R.string.online_step_unknown));
                roomState.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
                showRoom();
                // 房间中心：房主开 TCP 服务，成员连房主心跳（玩家列表+延迟）
                String nick = PlayerIdentity.getNickname(this);
                String cid = PlayerIdentity.getClientId(this);
                if (isHost) {
                    // 切换身份时清理对端角色（房主→成员或成员→房主，v518）
                    RoomCenter.stopClient();
                    RoomCenter.startHost(nick, cid, this::onRoomPlayers);
                    // v517 局域网公告桥：合成 MC 公告单播给成员，异地好友页可见房主世界
                    LanBridge.startHost(nick);
                } else {
                    // v518 修复：手动加入时停掉旧的房主中心，否则心跳发往
                    // 10.144.144.144:8090 会被自己残留的房主 socket 接住，
                    // 玩家列表出现自己的房主 ID
                    RoomCenter.stopHost();
                    LanBridge.stopHost();
                    RoomCenter.startClient(HOST_IPV4, nick, cid, this::onRoomPlayers);
                }
                break;
            case FAILED:
                if (joinDialog != null) {
                    joinDialog.dismiss();
                }
                setHomeState(state, detail);
                break;
            default:
                setHomeState(state, detail);
                break;
        }
    }
}
