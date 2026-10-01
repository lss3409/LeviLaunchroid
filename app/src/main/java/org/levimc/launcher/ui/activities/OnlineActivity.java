package org.levimc.launcher.ui.activities;

import android.app.AlertDialog;
import android.content.ClipData;
import android.graphics.Color;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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
import org.levimc.launcher.core.online.OnlineOverlay;
import org.levimc.launcher.core.online.PlayerDetailCard;
import org.levimc.launcher.core.online.PlayerIdentity;
import org.levimc.launcher.core.online.QrUtils;
import org.levimc.launcher.core.online.RelayStore;
import org.levimc.launcher.core.online.RoomCenter;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 联机页（v500 按提示词重设计）：
 * 首页（状态条+创建/加入并排卡片+最近房间+中转入口）/ 创建视图 / 房间视图；
 * 加入房间为弹窗（输入+实时校验+连接步骤条动画 解析→组网→发现房主→握手）。
 */
public final class OnlineActivity extends BaseActivity
        implements EasyTierManager.Listener, RoomCenter.Listener {

    private static final int REQ_VPN = 1001;
    /** v533：扫码请求码（journeyapps 默认 49374；相册选图自定义）。 */
    private static final int REQ_SCAN_GALLERY = 1003;
    private static final String HOST_IPV4 = "10.144.144.144";
    private static final String PREFS_RECENT = "levimc_recent";
    private static final String KEY_RECENT = "rooms";

    private View homeView;
    private View createView;
    private View roomView;
    private TextView stateText;
    private View stateDot;
    private View stateProgress;
    private View disconnectButton;
    private TextView roomCodeText;
    private TextView roomCodeText2;
    private TextView roomState;
    private TextView roomLatency;
    private TextView hostAvatar;
    private TextView hostName;
    private TextView gameStatus;
    private LinearLayout playersContainer;
    private TextView createStatus;

    // v529：房主头像（成员端从玩家列表同步）+ 加入/离开横幅
    private String hostAvatarUrl;
    /** v547：成员端保存房主快照（房主行点击详情卡用）。 */
    private RoomCenter.Player hostPlayer;
    private TextView banner;
    private final android.os.Handler bannerHandler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private final Runnable hideBannerRunnable = this::hideBannerNow;
    private final List<String> pendingJoins = new ArrayList<>();
    private final List<String> pendingLeaves = new ArrayList<>();
    private final java.util.Set<String> lastPlayerIds = new java.util.HashSet<>();
    private final java.util.Map<String, String> lastPlayerNames = new java.util.HashMap<>();
    private Runnable flushBannerRunnable;

    private boolean formatting;
    private InviteCode.Parsed pendingJoin;
    private String currentCode; // 当前房间码（房主生成/成员加入）
    private android.app.Dialog joinDialog;
    private boolean isHost;
    private final List<InviteCode.Parsed> pendingParsed = new ArrayList<>();
    /** v568：建房流程 VPN 授权未完成时暂存的邀请码（授权回来继续建房）。 */
    private InviteCode.Parsed pendingHostParsed;
    /** v561：成员加入后等待房主握手的超时计时（20s 未见房主 = 房间已解散）。 */
    private final android.os.Handler handshakeHandler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private boolean roomHandshakeDone = true;
    /** v571：VPN 授权弹窗是否正在显示（防重复弹）。 */
    private boolean vpnAuthDialogShowing;
    private final Runnable handshakeTimeout = () -> failHandshake(
            "握手超时触发——20s 未见房主名单，断开");

    /** v571：8s 快速判失败——EasyTier 路由表里一直没有房主 peer
     * （getHostVirtualIp 为 null）说明房主不在网络（最近房间/房主已
     * 解散），不必等满 20s 握手超时；房主在线时 peer 通常 3-5s 内出现。 */
    private final Runnable fastFailCheck = () -> {
        if (isHost || roomHandshakeDone || isFinishing()) {
            return;
        }
        if (EasyTierManager.get().getHostVirtualIp() != null) {
            // 已看到房主 peer，等正常握手
            return;
        }
        failHandshake("8s 未见房主 peer（路由表无 paper-connect-server）——快速判定房间已解散");
    };

    /** 握手失败统一出口（避免两个 Runnable 互相前向引用）。 */
    private void failHandshake(String reason) {
        if (isHost || roomHandshakeDone || isFinishing()) {
            return;
        }
        roomHandshakeDone = true;
        if (joinDialog != null) {
            joinDialog.dismiss();
        }
        handshakeHandler.removeCallbacks(handshakeTimeout);
        handshakeHandler.removeCallbacks(fastFailCheck);
        org.levimc.launcher.util.OnlineDebugLog.log(reason);
        EasyTierManager.get().stop(this);
        RoomCenter.stopClient();
        LanDiscovery.stopHost();
        Toast.makeText(this, R.string.online_host_not_found, Toast.LENGTH_LONG).show();
        showHome();
        setHomeState(EasyTierManager.State.IDLE, null);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_online);
        setActiveNavTab(R.id.nav_tab_online);

        homeView = findViewById(R.id.online_home_view);
        createView = findViewById(R.id.online_create_view);
        roomView = findViewById(R.id.online_room_view);
        stateText = findViewById(R.id.online_state_text);
        stateDot = findViewById(R.id.online_state_dot);
        stateProgress = findViewById(R.id.online_state_progress);
        disconnectButton = findViewById(R.id.online_disconnect_button);
        roomCodeText = findViewById(R.id.online_room_code);
        roomCodeText2 = findViewById(R.id.online_room_code2);
        roomState = findViewById(R.id.online_room_state);
        roomLatency = findViewById(R.id.online_room_latency);
        hostAvatar = findViewById(R.id.online_host_avatar);
        hostName = findViewById(R.id.online_host_name);
        gameStatus = findViewById(R.id.online_room_game_status);
        banner = findViewById(R.id.online_banner);
        flushBannerRunnable = () -> {
            List<String> joins = new ArrayList<>(pendingJoins);
            List<String> leaves = new ArrayList<>(pendingLeaves);
            pendingJoins.clear();
            pendingLeaves.clear();
            if (!joins.isEmpty()) {
                showBanner(getString(R.string.online_join_banner_fmt, String.join("」「", joins)),
                        0xCC3A6B4E, 2500); // MC 原版加入提示绿
            } else if (!leaves.isEmpty()) {
                showBanner(getString(R.string.online_leave_banner_fmt, String.join("」「", leaves)),
                        0xAA3A3A44, 1500); // 离开用淡灰，不抢注意力
            }
        };
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

        // v535：联机页主按钮手动应用个性化强调色（复制/分享/二维码等）
        applyAccentToPrimaryButtons();

        // v533：预热身份（昵称+头像静态缓存）——debug 后门加入时不经过 UI
        // 展示路径，不预热会导致心跳 avatarUrl 为空（vivo 头像"不显示"根因）
        PlayerIdentity.getNickname(this);
        PlayerIdentity.getAvatarUrl(this);

        refreshRecent();
        setHomeState(EasyTierManager.State.IDLE, null);

        // v571：VPN 授权失效（重装 APK 清授权）时自动弹授权窗——
        // establish 持续失败时 VpnService 回调此处，30s 节流
        EasyTierManager.setVpnAuthRequiredCallback(() -> {
            if (vpnAuthDialogShowing || isFinishing()) {
                return;
            }
            Intent vpnIntent = VpnService.prepare(this);
            if (vpnIntent != null) {
                vpnAuthDialogShowing = true;
                Toast.makeText(this, R.string.online_vpn_needed, Toast.LENGTH_SHORT).show();
                startActivityForResult(vpnIntent, REQ_VPN);
            }
        });

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
            // v568：建房同样需要 VPN 授权（此前只有加入流程有 prepare，
            // 房主重装后无授权时 TUN 建立失败且永远不弹授权窗）
            Intent vpnIntent = VpnService.prepare(this);
            if (vpnIntent != null) {
                pendingHostParsed = hr.parsed;
                Toast.makeText(this, "请允许 VPN 连接以完成联机", Toast.LENGTH_SHORT).show();
                startActivityForResult(vpnIntent, REQ_VPN);
                return;
            }
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
            runOnUiThread(() -> {
                // v565：成员固定虚拟 IP（dhcp=false），不再让 DHCP 从房主网段分地址
                String memberIp = EasyTierManager.memberIpv4For(PlayerIdentity.getClientId(this));
                org.levimc.launcher.util.OnlineDebugLog.log("成员固定虚拟IP: " + memberIp);
                EasyTierManager.get().join(this,
                        parsed.networkName, parsed.networkSecret, this, peers, memberIp);
            });
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
        // v574：删掉建房后的淡入过渡（一闪而过无意义，直接显示）
        populateRoom();
    }

    /** 房间视图数据填充。 */
    private void populateRoom() {
        roomCodeText2.setText(currentCode == null ? "" : "P/" + currentCode);
        hostAvatar.setBackground(accentAvatarBg()); // v541：房主头像底跟随个性化强调色
        String nick = PlayerIdentity.getNickname(this);
        if (isHost) {
            hostAvatar.setText(firstChar(nick));
            hostName.setText(getString(R.string.online_host_you, nick));
            // v547：房主视角点自己的房主行 → 自己的详情卡
            hostPlayer = new RoomCenter.Player(nick, PlayerIdentity.getClientId(this),
                    true, null, org.levimc.launcher.core.online.voice.VoiceEngine.getLastMode(),
                    PlayerIdentity.getAvatarUrl(this),
                    PlayerIdentity.getCurrentXuid(), PlayerIdentity.getCurrentMsUser(),
                    PlayerIdentity.getPlayMinutes(this), 2,
                    PlayerIdentity.getLastActiveStatic());
        } else {
            // 成员视角：房主昵称由玩家列表心跳获取（isRoomHost），未获取前占位
            hostAvatar.setText("房");
            hostName.setText(getString(R.string.online_host_unknown));
            hostPlayer = null;
        }
        // v547：房主行点击弹详情卡（成员端点击=房主快照，房主端=自己快照）
        android.view.View.OnClickListener hostClick = v -> {
            RoomCenter.Player hp = hostPlayer;
            if (hp == null) {
                return;
            }
            if (PlayerDetailCard.canView(this, hp, RoomCenter.isHost)) {
                PlayerDetailCard.show(this, hp, false);
            } else {
                Toast.makeText(this, R.string.online_card_no_permission,
                        Toast.LENGTH_SHORT).show();
            }
        };
        hostAvatar.setClickable(true);
        hostAvatar.setOnClickListener(hostClick);
        hostName.setClickable(true);
        hostName.setOnClickListener(hostClick);
        // v529：房主行头像——房主端用本机账号头像，成员端用玩家列表同步的房主头像
        hostAvatarUrl = isHost ? PlayerIdentity.getAvatarUrl(this) : null;
        loadHostAvatar();
        // v526：房主世界开启状态（成员端显示，房主端隐藏；心跳回调后实时刷新）
        if (gameStatus != null) {
            if (isHost) {
                gameStatus.setVisibility(View.GONE);
            } else {
                gameStatus.setVisibility(View.VISIBLE);
                gameStatus.setText(R.string.online_game_wait);
                gameStatus.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
            }
        }
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
        org.levimc.launcher.ui.dialogs.CustomAlertDialog d =
                new org.levimc.launcher.ui.dialogs.CustomAlertDialog(this);
        d.setTitleText(getString(R.string.online_qr));
        d.setCustomView(wrap);
        d.setPositiveButton(getString(R.string.confirm), null);
        d.show();
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

    /** 玩家行（v502/v529/v544）：首字头像（有 URL 时 Glide 覆盖）+ 昵称 + 房主皇冠 + 自己高亮。
     *  v529：avatarUrl 按玩家传入（跨设备同步），不再用本机账号头像。
     *  v544：点击行弹出玩家详情卡（Xbox 详情/游玩时长/皮肤/权限）。 */
    private void addPlayerRow(String name, boolean isSelf, boolean isRoomHost, String avatarUrl,
                              RoomCenter.Player player) {
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
        avatar.setBackground(accentAvatarBg()); // v541：头像底跟随个性化强调色
        avatarFrame.addView(avatar, new android.widget.FrameLayout.LayoutParams(
                avatarSize, avatarSize));
        // Xbox 头像（该玩家的 URL；自己=本机账号头像）
        if (isSelf) {
            avatarUrl = PlayerIdentity.getAvatarUrl(this);
        }
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            com.bumptech.glide.Glide.with(this).load(avatarUrl).circleCrop().into(iv);
            avatarFrame.addView(iv, new android.widget.FrameLayout.LayoutParams(
                    avatarSize, avatarSize));
        }
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        row.addView(avatarFrame, alp);

        TextView label = new TextView(this);
        // v529（清单 #33）：自己那行加"（我）"标识
        label.setText(isSelf ? name + getString(R.string.online_self_suffix) : name);
        label.setTextSize(14);
        // v541：自己名字/高亮跟随个性化强调色
        label.setTextColor(isSelf ? accentColor()
                : getResources().getColor(R.color.on_surface, getTheme()));
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
        // v547：点击玩家行弹详情卡（公共组件，悬浮窗同款）
        if (player != null) {
            row.setClickable(true);
            row.setOnClickListener(v -> {
                if (PlayerDetailCard.canView(this, player, RoomCenter.isHost)) {
                    PlayerDetailCard.show(this, player,
                            RoomCenter.isHost && !isSelf && !player.isRoomHost);
                } else {
                    Toast.makeText(this, R.string.online_card_no_permission,
                            Toast.LENGTH_SHORT).show();
                }
            });
        }
        playersContainer.addView(row);
    }

    /** v560：RoomCenter.Listener 接口实现（转调 onRoomPlayers）。 */
    @Override
    public void onPlayers(List<RoomCenter.Player> players, long rttMs) {
        onRoomPlayers(players, rttMs);
    }

    /** v560：成员端收到房主世界邀请 → 深链启动游戏自动连接（免选局域网入口）。 */
    @Override
    public void onInvite(String hostIp, int port) {
        if (RoomCenter.isHost) {
            return;
        }
        runOnUiThread(() -> {
            try {
                Intent intent = new Intent(this,
                        org.levimc.launcher.ui.activities.IntentHandler.class);
                intent.setAction(Intent.ACTION_VIEW);
                intent.setData(Uri.parse("minecraft://connect?serverUrl="
                        + hostIp + "&serverPort=" + port));
                startActivity(intent);
                Toast.makeText(this, "房主邀请进入世界，正在连接…",
                        Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "邀请连接失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 玩家列表心跳回调（工作线程）。 */
    private void onRoomPlayers(List<RoomCenter.Player> list, long rttMs) {
        runOnUiThread(() -> {
            // v561：加入握手——名单里出现房主条目即握手成功，取消超时判定
            if (!isHost && !roomHandshakeDone && list != null) {
                for (RoomCenter.Player p : list) {
                    if (p.isRoomHost) {
                        roomHandshakeDone = true;
                        handshakeHandler.removeCallbacks(handshakeTimeout);
                    handshakeHandler.removeCallbacks(fastFailCheck);
                        org.levimc.launcher.util.OnlineDebugLog.log(
                                "握手成功——收到房主名单（" + list.size() + " 人）");
                        break;
                    }
                }
            }
            if (!roomView.isShown() && joinDialog == null) {
                return;
            }
            if (rttMs > 0) {
                roomLatency.setText(getString(R.string.online_room_latency_fmt, rttMs));
                // v529（清单 #25）：延迟颜色分级 <50 绿 / 50-100 黄 / >100 红
                roomLatency.setTextColor(rttMs < 50 ? 0xFF4CAF50
                        : rttMs < 100 ? getResources().getColor(R.color.warning, getTheme())
                        : getResources().getColor(R.color.error, getTheme()));
            }
            updateConnModeBadge();
            String selfId = PlayerIdentity.getClientId(this);
            String nick = PlayerIdentity.getNickname(this);
            // v529（清单 #13/15/16）：加入/离开横幅（对比上次名单）
            detectPlayerChanges(list, selfId);
            playersContainer.removeAllViews();
            int memberCount = 1;
            if (isHost) {
                // 房主视角：房主行已展示自己，玩家列表只列成员
                for (RoomCenter.Player p : list) {
                    if (!p.isRoomHost) {
                        addPlayerRow(p.name, false, false, p.avatarUrl, p);
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
                        // v547：保存房主快照（房主行点击详情卡用）
                        hostPlayer = p;
                        // v529：房主头像用同步来的 URL（变化时重新加载）
                        if (p.avatarUrl != null && !p.avatarUrl.equals(hostAvatarUrl)) {
                            hostAvatarUrl = p.avatarUrl;
                            loadHostAvatar();
                        }
                        memberCount++;
                    }
                }
                for (RoomCenter.Player p : list) {
                    if (!p.isRoomHost && !p.clientId.equals(selfId)) {
                        addPlayerRow(p.name, false, false, p.avatarUrl, p);
                        memberCount++;
                    }
                }
                // v547：自己行也传快照（点击弹自己的详情卡）
                addPlayerRow(nick, true, false, null,
                        new RoomCenter.Player(nick, selfId, false, null,
                                org.levimc.launcher.core.online.voice.VoiceEngine.getLastMode(),
                                PlayerIdentity.getAvatarUrl(this),
                                PlayerIdentity.getCurrentXuid(), PlayerIdentity.getCurrentMsUser(),
                                PlayerIdentity.getPlayMinutes(this), 2,
                                PlayerIdentity.getLastActiveStatic()));
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
            // v526：成员端房主世界开启状态（紧凑状态行，替代旧引导卡）
            if (gameStatus != null) {
                if (isHost) {
                    gameStatus.setVisibility(View.GONE);
                } else if (RoomCenter.hostGameOpen) {
                    gameStatus.setVisibility(View.VISIBLE);
                    gameStatus.setText(R.string.online_game_open);
                    gameStatus.setTextColor(getResources().getColor(R.color.primary, getTheme()));
                } else {
                    gameStatus.setVisibility(View.VISIBLE);
                    gameStatus.setText(R.string.online_game_wait);
                    gameStatus.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
                }
            }
        });
    }

    /** v529：房主行头像加载（hostAvatarUrl 变化时调用）。 */
    private void loadHostAvatar() {
        android.widget.ImageView hostImg = findViewById(R.id.online_host_avatar_img);
        if (hostImg == null) {
            return;
        }
        hostImg.setImageDrawable(null);
        if (hostAvatarUrl != null && !hostAvatarUrl.isEmpty()) {
            com.bumptech.glide.Glide.with(this).load(hostAvatarUrl).circleCrop().into(hostImg);
        }
    }

    // ---- v529 加入/离开横幅 ----

    private void detectPlayerChanges(List<RoomCenter.Player> list, String selfId) {
        if (list == null) {
            return;
        }
        // v531 防呆：成员端名单缺房主条目 = 数据不完整（如误收的响应），
        // 跳过 diff，避免误判"房主离开了房间"
        if (!isHost) {
            boolean hasHost = false;
            for (RoomCenter.Player p : list) {
                if (p.isRoomHost) {
                    hasHost = true;
                    break;
                }
            }
            if (!hasHost) {
                return;
            }
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (RoomCenter.Player p : list) {
            if (!p.clientId.equals(selfId)) {
                ids.add(p.clientId);
                names.put(p.clientId, p.name);
            }
        }
        if (!lastPlayerIds.isEmpty()) {
            for (String id : ids) {
                if (!lastPlayerIds.contains(id)) {
                    queueJoin(names.get(id));
                }
            }
            for (String id : lastPlayerIds) {
                if (!ids.contains(id)) {
                    queueLeave(lastPlayerNames.get(id));
                }
            }
        }
        lastPlayerIds.clear();
        lastPlayerIds.addAll(ids);
        lastPlayerNames.clear();
        lastPlayerNames.putAll(names);
    }

    private void queueJoin(String name) {
        if (name == null) {
            return;
        }
        pendingJoins.add(name);
        bannerHandler.removeCallbacks(flushBannerRunnable);
        bannerHandler.postDelayed(flushBannerRunnable, 800);
    }

    private void queueLeave(String name) {
        if (name == null) {
            return;
        }
        pendingLeaves.add(name);
        bannerHandler.removeCallbacks(flushBannerRunnable);
        bannerHandler.postDelayed(flushBannerRunnable, 800);
    }

    private void showBanner(String text, int bgColor, long durationMs) {
        banner.setText(text);
        // v531：不能对共享 drawable（bg_rounded_card）setTint——会把页面上
        // 所有引用它的卡片背景全染色（"切后台回来背景变灰"根因）。
        // 每次新建独立 GradientDrawable。
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(14 * getResources().getDisplayMetrics().density);
        banner.setBackground(bg);
        banner.setAlpha(0f);
        banner.setVisibility(View.VISIBLE);
        banner.animate().alpha(1f).setDuration(200).start();
        bannerHandler.removeCallbacks(hideBannerRunnable);
        bannerHandler.postDelayed(hideBannerRunnable, durationMs);
    }

    private void hideBannerNow() {
        banner.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> banner.setVisibility(View.GONE)).start();
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
        // v568：建房同样需要 VPN 授权（此前只有加入流程有 prepare，
        // 房主重装后无授权时 TUN 建立失败且永远不弹授权窗——用户实测
        // "平板做房主 VPN 没跑起来"的根因）
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            pendingHostParsed = g.parsed;
            Toast.makeText(this, "请允许 VPN 连接以完成联机", Toast.LENGTH_SHORT).show();
            startActivityForResult(vpnIntent, REQ_VPN);
            return;
        }
        doHostRoom(g.parsed);
    }

    /** v568：建房通用流程（授权完成后调用）。 */
    private void doHostRoom(InviteCode.Parsed parsed) {
        createStatus.setText(getString(R.string.online_connecting_kernel));
        showCreate();
        LanDiscovery.startHost(parsed.networkName);
        List<String> relayPeers = RelayStore.load(this);
        EasyTierManager.get().host(this, parsed.networkName, parsed.networkSecret, this,
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

        // v533：扫码加入（相机面对面扫 / 相册选图）；v535：Levi 风格弹窗
        v.findViewById(R.id.join_scan_button).setOnClickListener(x -> {
            org.levimc.launcher.ui.dialogs.CustomAlertDialog scanDialog =
                    new org.levimc.launcher.ui.dialogs.CustomAlertDialog(this);
            scanDialog.setTitleText(getString(R.string.online_scan_title));
            scanDialog.setItems(new String[]{getString(R.string.online_scan_camera),
                    getString(R.string.online_scan_gallery)}, (d, which) -> {
                        if (which == 0) {
                            try {
                                new com.google.zxing.integration.android.IntentIntegrator(this)
                                        .setDesiredBarcodeFormats(
                                                com.google.zxing.integration.android.IntentIntegrator.QR_CODE)
                                        .setPrompt(getString(R.string.online_scan_camera))
                                        .setOrientationLocked(false)
                                        .initiateScan();
                            } catch (Throwable t) {
                                Toast.makeText(this, getString(R.string.online_scan_unavailable),
                                        Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                            pick.setType("image/*");
                            pick.addCategory(Intent.CATEGORY_OPENABLE);
                            startActivityForResult(pick, REQ_SCAN_GALLERY);
                        }
                    });
            scanDialog.setNegativeButton(getString(android.R.string.cancel), null);
            scanDialog.show();
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

        // v535：改用 Levi 风格弹窗（CustomAlertDialog），统一背景与启动器一致
        org.levimc.launcher.ui.dialogs.CustomAlertDialog dialog =
                new org.levimc.launcher.ui.dialogs.CustomAlertDialog(this);
        // v548：加入弹窗背景收窄；v557：380dp——横版比例（用户反馈太窄内容竖堆）
        dialog.setMaxWidthDp(380);
        dialog.setCustomView(v);
        dialog.setCancelable(false);
        dialog.show();
        joinDialog = dialog;
        // v537/v538：弹窗内按钮染个性化强调色（加入=主按钮，取消=文字染 accent）
        org.levimc.launcher.util.AccentStyler.stylePrimary(this,
                v.findViewById(R.id.join_confirm_button));
        org.levimc.launcher.util.AccentStyler.styleSecondary(this,
                v.findViewById(R.id.join_cancel_button));

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
                // v574：VPN 提示小字跟随个性化 accent（XML 里 primary 深绿）
                status.setTextColor(accentColor());
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
    }

    private void doJoinFromDialog(InviteCode.Parsed parsed) {
        currentCode = rawToCode(parsed);
        isHost = false;
        setStepState(1, true);
        new Thread(() -> {
            List<String> peers = new ArrayList<>(LanDiscovery.discover(parsed.networkName, 1500)); // v537：局域网发现 3s→1.5s
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
                // v565：成员固定虚拟 IP（dhcp=false），不再让 DHCP 从房主网段分地址
                String memberIp = EasyTierManager.memberIpv4For(PlayerIdentity.getClientId(this));
                org.levimc.launcher.util.OnlineDebugLog.log("成员固定虚拟IP: " + memberIp);
                EasyTierManager.get().join(this, parsed.networkName, parsed.networkSecret,
                        this, peers, memberIp);
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
            // v574：「邀请码有效」等提示小字跟随个性化 accent（原 primary 深绿）
            feedback.setTextColor(accentColor());
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
        // v571：步骤点用个性化 accent 色（bg_state_dot_ok 是 primary 深绿，
        // 不跟随个性化）
        int accent = new org.levimc.launcher.util.PersonalizationManager(this).getAccentColor();
        if (accent == 0) {
            accent = getResources().getColor(R.color.primary, getTheme());
        }
        for (int i = 0; i < stepViews.length; i++) {
            View step = (View) stepViews[i].getParent();
            View dot = step.findViewWithTag("dot");
            TextView label = (TextView) step.findViewWithTag("label");
            boolean isDone = i < active || done && i <= active && i != active;
            boolean isActive = i == active;
            if (isDone) {
                applyDotColor(dot, accent);
                label.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
            } else if (isActive) {
                applyDotColor(dot, accent);
                label.setTextColor(accent);
            } else {
                dot.setBackgroundResource(R.drawable.bg_state_dot_idle);
                label.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
            }
        }
    }

    /** 步骤点染 accent（椭圆实心）。 */
    private static void applyDotColor(View dot, int color) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        dot.setBackground(g);
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
            row.setTextColor(accentColor()); // v541：最近房间码跟随个性化强调色
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

    /**
     * v526：Levi 品牌邀请卡弹窗（大码 + 二维码 + 复制/系统分享）。
     * 替代原先直接拉起系统分享选择器的粗糙交互。
     */
    private void shareCurrentCode() {
        if (currentCode == null) {
            return;
        }
        View v = getLayoutInflater().inflate(R.layout.dialog_share_code, null);
        ((TextView) v.findViewById(R.id.share_code_text)).setText("P/" + currentCode);
        // v542：大邀请码与「联机邀请」标签跟随个性化强调色
        ((TextView) v.findViewById(R.id.share_code_text)).setTextColor(accentColor());
        ((TextView) v.findViewById(R.id.share_tag)).setTextColor(accentColor());
        android.graphics.Bitmap qr = QrUtils.generate("P/" + currentCode, 480);
        android.widget.ImageView qrView = v.findViewById(R.id.share_qr);
        if (qr != null) {
            qrView.setImageBitmap(qr);
        } else {
            qrView.setVisibility(View.GONE);
        }
        // v535：分享卡弹窗同样换 Levi 风格；v536：点外部空白即可关闭
        org.levimc.launcher.ui.dialogs.CustomAlertDialog dialog =
                new org.levimc.launcher.ui.dialogs.CustomAlertDialog(this);
        dialog.setCustomView(v);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        // v537：分享卡按钮染个性化强调色
        org.levimc.launcher.util.AccentStyler.stylePrimary(this,
                v.findViewById(R.id.share_copy_button),
                v.findViewById(R.id.share_send_button));
        v.findViewById(R.id.share_copy_button).setOnClickListener(x -> {
            android.util.Log.i("OnlineActivity", "分享卡: 复制点击");
            copyCurrentCode();
            dialog.dismiss();
        });
        v.findViewById(R.id.share_send_button).setOnClickListener(x -> {
            android.util.Log.i("OnlineActivity", "分享卡: 系统分享点击");
            dialog.dismiss();
            systemShareCode();
        });
        dialog.show();
    }

    /** 系统分享（微信/QQ 等），文本为链接卡片式。 */
    private void systemShareCode() {
        if (currentCode == null) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, getString(R.string.online_share_text, "P/" + currentCode));
        startActivity(Intent.createChooser(send, getString(R.string.online_share_code)));
    }

    /** v541：当前个性化强调色。 */
    private int accentColor() {
        try {
            return new org.levimc.launcher.util.PersonalizationManager(this).getAccentColor();
        } catch (Throwable ignored) {
            return getResources().getColor(R.color.primary, getTheme());
        }
    }

    /** v541：圆形强调色头像底（替代 bg_avatar 静态色）。 */
    private android.graphics.drawable.GradientDrawable accentAvatarBg() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(accentColor());
        return d;
    }

    /** v537：主按钮统一走 AccentStyler（无条件染强调色 + 按压态加深）。 */
    private void applyAccentToPrimaryButtons() {
        try {
            org.levimc.launcher.util.AccentStyler.stylePrimary(this,
                    findViewById(R.id.online_copy_button),
                    findViewById(R.id.online_share_button),
                    findViewById(R.id.online_qr_button),
                    findViewById(R.id.online_room_copy_button),
                    findViewById(R.id.online_room_share_button));
            // v572：返回首页按钮用启动器主按钮背景（accent 实底白字，
            // 与个性化联动）——v571 的半透明灰底方案用户不满意
            org.levimc.launcher.util.AccentStyler.stylePrimary(this,
                    findViewById(R.id.online_back_home_button));
        } catch (Throwable ignored) {
        }
    }

    private void onLeaveClicked() {
        // v561：清理加入握手超时计时
        roomHandshakeDone = true;
        handshakeHandler.removeCallbacks(handshakeTimeout);
                    handshakeHandler.removeCallbacks(fastFailCheck);
        EasyTierManager.get().stop(this);
        LanDiscovery.stopHost();
        RoomCenter.stopHost();
        RoomCenter.stopClient();
        LanBridge.stopHost();
        org.levimc.launcher.core.online.voice.VoiceEngine.get(this).stop();
        RoomCenter.roomCode = null;
        RoomCenter.hostGameOpen = false;
        currentCode = null;
        isHost = false;
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
        // v533：扫码结果（相机/相册）→ 提取邀请码回填输入框
        if (requestCode == REQ_SCAN_GALLERY) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                String text = decodeQrFromImage(data.getData());
                if (text != null) {
                    fillJoinInput(text);
                } else {
                    Toast.makeText(this, R.string.online_scan_failed, Toast.LENGTH_SHORT).show();
                }
            }
            return;
        }
        if (requestCode == com.google.zxing.integration.android.IntentIntegrator.REQUEST_CODE) {
            com.google.zxing.integration.android.IntentResult scan =
                    com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(
                            requestCode, resultCode, data);
            if (scan != null && scan.getContents() != null) {
                fillJoinInput(scan.getContents());
            }
            return;
        }
        if (requestCode != REQ_VPN) {
            return;
        }
        vpnAuthDialogShowing = false;
        if (resultCode == RESULT_OK && pendingHostParsed != null) {
            // v568：建房授权完成，继续建房
            InviteCode.Parsed hp = pendingHostParsed;
            pendingHostParsed = null;
            doHostRoom(hp);
        } else if (resultCode == RESULT_OK && !pendingParsed.isEmpty()) {
            doJoinFromDialog(pendingParsed.remove(0));
        } else {
            pendingParsed.clear();
            pendingHostParsed = null;
            if (joinDialog != null) {
                joinDialog.dismiss();
            }
            Toast.makeText(this, R.string.online_vpn_cancelled, Toast.LENGTH_SHORT).show();
        }
    }

    /** v533：扫码文本 → 智能提取邀请码 → 回填加入弹窗输入框。 */
    private void fillJoinInput(String text) {
        if (text == null) {
            return;
        }
        String code = InviteCode.formatInput(text);
        if (joinDialog != null && joinDialog.isShowing()) {
            EditText input = joinDialog.findViewById(R.id.join_code_input);
            input.setText(code);
            input.setSelection(input.length());
            TextView feedback = joinDialog.findViewById(R.id.join_feedback);
            updateJoinFeedback(input, feedback);
        } else {
            showJoinDialogWithCode(code);
        }
    }

    /** v533：相册图片解码二维码（zxing core）。 */
    private String decodeQrFromImage(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is);
            if (bmp == null) {
                return null;
            }
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            com.google.zxing.LuminanceSource src =
                    new com.google.zxing.RGBLuminanceSource(w, h, px);
            com.google.zxing.BinaryBitmap bb = new com.google.zxing.BinaryBitmap(
                    new com.google.zxing.common.HybridBinarizer(src));
            java.util.Map<com.google.zxing.DecodeHintType, Object> hints = new java.util.HashMap<>();
            hints.put(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS,
                    java.util.Collections.singletonList(com.google.zxing.BarcodeFormat.QR_CODE));
            hints.put(com.google.zxing.DecodeHintType.CHARACTER_SET, "UTF-8");
            com.google.zxing.Result r = new com.google.zxing.qrcode.QRCodeReader().decode(bb, hints);
            return r == null ? null : r.getText();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRecent();
        EasyTierManager.get().setListener(this);
        EasyTierManager.State s = EasyTierManager.get().getState();
        if (s == EasyTierManager.State.CONNECTED) {
            // v545：游戏内切回启动器/新建联机页实例时，恢复当前房间视图
            // （此前新实例 onCreate 只显示首页，用户看到"重新联机"）
            restoreRoomIfConnected();
            onState(s, EasyTierManager.get().getVirtualIp());
        }
    }

    /** v545：组网仍连接且有房间时，直接恢复房间视图。 */
    private void restoreRoomIfConnected() {
        if (RoomCenter.roomCode == null) {
            return;
        }
        currentCode = RoomCenter.roomCode;
        isHost = RoomCenter.isHost;
        roomCodeText.setText("P/" + currentCode);
        showRoom();
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
                    // v561：房主虚拟 IP 从 EasyTier 路由表解析（异地中继下
                    // DHCP 分配的真实地址），解析不到才回退固定 IP——
                    // 写死 10.144.144.144 是异地联机"只显示 1 人"的根因
                    String hostIp = EasyTierManager.get().getHostVirtualIp();
                    if (hostIp == null || hostIp.isEmpty()) {
                        hostIp = HOST_IPV4;
                    }
                    android.util.Log.i("OnlineActivity", "连接房主: " + hostIp);
                    org.levimc.launcher.util.OnlineDebugLog.log("成员连接房主: " + hostIp
                            + "（路由表解析=" + EasyTierManager.get().getHostVirtualIp()
                            + "）本机虚拟IP=" + EasyTierManager.get().getVirtualIp());
                    RoomCenter.startClient(hostIp, nick, cid, this::onRoomPlayers);
                    // v561：加入握手确认——20 秒内收不到房主玩家列表
                    // 即判定房间已解散（此前"房主不在也能加入成功"）；
                    // v571：8 秒无房主 peer 快速失败（最近房间/房主已
                    // 解散时不必干等 20 秒）
                    roomHandshakeDone = false;
                    handshakeHandler.removeCallbacks(handshakeTimeout);
                    handshakeHandler.removeCallbacks(fastFailCheck);
                    handshakeHandler.removeCallbacks(fastFailCheck);
                    handshakeHandler.postDelayed(handshakeTimeout, 20_000L);
                    handshakeHandler.postDelayed(fastFailCheck, 8_000L);
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
