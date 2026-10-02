package org.levimc.launcher.ui.activities;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.lifecycle.ViewModelProvider;

import org.levimc.launcher.R;
import org.levimc.launcher.core.minecraft.MinecraftActivityState;
import org.levimc.launcher.core.minecraft.MinecraftProcessRestarter;
import org.levimc.launcher.core.minecraft.MinecraftImportIntents;
import org.levimc.launcher.core.minecraft.LaunchTrace;
import org.levimc.launcher.core.minecraft.MinecraftLauncher;
import org.levimc.launcher.core.mods.FileHandler;
import org.levimc.launcher.core.mods.Mod;
import org.levimc.launcher.core.mods.inbuilt.manager.InbuiltModManager;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.databinding.ActivityMainBinding;
import org.levimc.launcher.settings.FeatureSettings;

import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.ui.views.MainViewModel;
import org.levimc.launcher.ui.views.MainViewModelFactory;
import org.levimc.launcher.util.ApkImportManager;
import org.levimc.launcher.util.HardcoreBackupManager;
import org.levimc.launcher.util.LanguageManager;
import org.levimc.launcher.util.LauncherStorage;
import org.levimc.launcher.util.PermissionsHandler;
import org.levimc.launcher.util.InstanceShortcutManager;
import org.levimc.launcher.util.PersonalizationManager;
import org.levimc.launcher.util.ResourcepackHandler;
import org.levimc.launcher.util.UIHelper;
import org.levimc.launcher.core.content.ContentManager;
import java.util.ArrayList;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


 import android.widget.Button;
 import android.widget.ProgressBar;
 import android.graphics.Bitmap;
 import android.view.Gravity;
 import android.widget.PopupWindow;
 import android.view.LayoutInflater;
 import android.graphics.drawable.ColorDrawable;
 import android.util.TypedValue;
 import android.view.ViewGroup;
 import android.view.ViewTreeObserver;
 import androidx.core.content.ContextCompat;

import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import okhttp3.OkHttpClient;
 import okhttp3.Request;
 import okhttp3.Response;

 import org.levimc.launcher.core.auth.MsftAccountStore;
 import org.levimc.launcher.core.auth.MsftAuthManager;
 import org.levimc.launcher.ui.dialogs.LoadingDialog;
 import org.levimc.launcher.util.AccountTextUtils;
 import org.levimc.launcher.util.DialogUtils;

 import static org.levimc.launcher.core.minecraft.MinecraftProcessRestarterKt.ACTION_MAIN_ACTIVITY_FIRST_DRAWN;
 import static org.levimc.launcher.core.minecraft.MinecraftProcessRestarterKt.EXTRA_CLOSE_RESTART_ACTIVITY_ON_FIRST_DRAW;

 public class MainActivity extends BaseActivity {
    private ActivityMainBinding binding;
    private MinecraftLauncher minecraftLauncher;
    private LanguageManager languageManager;
    private PermissionsHandler permissionsHandler;
    private FileHandler fileHandler;
    private ApkImportManager apkImportManager;
    private MainViewModel viewModel;
    private VersionManager versionManager;
    private ActivityResultLauncher<Intent> permissionResultLauncher;
    private ActivityResultLauncher<Intent> apkImportResultLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    private LinearLayout modsListContainer;
    private ContentManager contentManager;
    private TextView worldsCountText;
    private TextView resourcePacksCountText;
    private TextView behaviorPacksCountText;

    private com.microsoft.xbox.idp.toolkit.CircleImageView accountAvatar;
    private View accountAvatarContainer;
    private ProgressBar avatarProgress;
    private Button signInButton;
    private String lastAvatarXuid;
    private final OkHttpClient avatarClient = new OkHttpClient();
    private ExecutorService accountExecutor = Executors.newSingleThreadExecutor();
    private LoadingDialog accountLoadingDialog;
    private ActivityResultLauncher<Intent> accountLoginLauncher;
    private OnBackPressedCallback onBackPressedCallback;

    /** 主页是否在前台（游戏后台被退出时据此决定是否静默重启，避免弹出多余过渡 UI）。 */
    public static volatile boolean sForeground = false;

    /** v518 久置自愈：后台久置后回前台，同进程渲染可能整体停摆（游戏黑屏+主页只剩背景色）。 */
    private long lastPauseElapsed = 0L;
    private static final long LONG_PAUSE_HEAL_MS = 5 * 60_000L;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        closeLauncherRestartAfterFirstDraw();
        setupNavBar();
        setupManagersAndHandlers();
        setupOnBackPressedCallback();
        // v705：卡片区布局照官方原版（XML weight 弹性），运行时调整已删

        accountLoginLauncher = registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null
                    && result.getData().getBooleanExtra(MsftLoginActivity.EXTRA_LOGIN_COMPLETED, false)) {
                String name = result.getData().getStringExtra(MsftLoginActivity.EXTRA_LOGIN_NAME);
                String statusName = name != null ? name : getString(R.string.not_signed_in);
                Toast.makeText(this, getString(R.string.ms_login_success, statusName), Toast.LENGTH_SHORT).show();
            }
            refreshAccountHeaderUI();
            refreshNavAccountUI();
        });

        initAccountHeader();
        initializeAfterMigrationGate();
        // v555：更新日志弹窗已删除（用户要求）
        // v601：深链待启动记忆——游戏退出流程重启后自动补发邀请深链
        // （成员端游戏运行中收到邀请时先结束会话的场景）
        // v618：双后台删除后游戏退出回到启动器走 onResume（MainActivity
        // 不重建），消费点移到 onResume——onCreate 保留兜底（进程被杀后
        // 冷启动场景）。补发前先等游戏完全退出（运行中发深链 1.26 不
        // 处理——v601 实测根因）。
        firePendingDeepLink();

        // v711：自动检查更新（每天一次限频；有新版本弹更新弹窗）
        org.levimc.launcher.core.updates.UpdateChecker.checkAsync(this, false, u -> {
            if (u != null && !isFinishing()) {
                org.levimc.launcher.core.updates.UpdateChecker.showUpdateDialog(this, u);
            }
        });
    }

    private volatile boolean deepLinkFiring = false;

    /** v618：消费待启动深链并在游戏完全退出后补发（onCreate/onResume 共用）。 */
    private void firePendingDeepLink() {
        if (deepLinkFiring) {
            return;
        }
        final String pending = org.levimc.launcher.ui.activities.OnlineActivity
                .consumePendingDeepLink(this);
        if (pending == null) {
            return;
        }
        deepLinkFiring = true;
        new Thread(() -> {
            try {
                int waited = 0;
                while (org.levimc.launcher.core.minecraft.MinecraftActivityState.isRunning()
                        && waited < 10_000) {
                    Thread.sleep(300);
                    waited += 300;
                }
            } catch (InterruptedException ignored) {
            }
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(this,
                            org.levimc.launcher.ui.activities.IntentHandler.class);
                    i.setAction(Intent.ACTION_VIEW);
                    i.setData(Uri.parse(pending));
                    startActivity(i);
                } catch (Exception ignored) {
                }
                deepLinkFiring = false;
            });
        }, "pending-deeplink").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleVersionDependentIntent();
    }

    private void closeLauncherRestartAfterFirstDraw() {
        Intent intent = getIntent();
        if (intent == null || !intent.getBooleanExtra(EXTRA_CLOSE_RESTART_ACTIVITY_ON_FIRST_DRAW, false)) {
            return;
        }
        intent.removeExtra(EXTRA_CLOSE_RESTART_ACTIVITY_ON_FIRST_DRAW);
        setIntent(intent);

        final View root = binding.getRoot();
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (root.getViewTreeObserver().isAlive()) {
                    root.getViewTreeObserver().removeOnPreDrawListener(this);
                }
                root.post(() -> {
                    hideSystemUI();
                    sendBroadcast(new Intent(ACTION_MAIN_ACTIVITY_FIRST_DRAWN).setPackage(getPackageName()));
                });
                return true;
            }
        });
    }


    private void initAccountHeader() {
        signInButton = findViewById(R.id.nav_sign_in_button);
        accountAvatar = findViewById(R.id.nav_account_avatar);
        accountAvatarContainer = findViewById(R.id.nav_account_avatar_container);
        avatarProgress = findViewById(R.id.nav_avatar_progress);

        if (signInButton != null) {
            signInButton.setOnClickListener(v -> {
                Intent intent = new Intent(this, MsftLoginActivity.class);
                accountLoginLauncher.launch(intent);
            });
            DynamicAnim.applyPressScale(signInButton);
        }
        if (accountAvatarContainer != null) {
            accountAvatarContainer.setOnClickListener(this::showAccountSwitchPopup);
            DynamicAnim.applyPressScale(accountAvatarContainer);
        }

        // 登录入口一直显示（不再受设置开关控制）
        refreshAccountHeaderUI();
    }

    private MsftAccountStore.MsftAccount getActiveAccount() {
        java.util.List<MsftAccountStore.MsftAccount> list = MsftAccountStore.list(this);
        for (MsftAccountStore.MsftAccount a : list) if (a.active) return a;
        return null;
    }

     private void setupOnBackPressedCallback() {
         onBackPressedCallback = new OnBackPressedCallback(true) {
             @Override
             public void handleOnBackPressed() {
                 org.levimc.launcher.ui.dialogs.CustomAlertDialog exitDialog = new org.levimc.launcher.ui.dialogs.CustomAlertDialog(MainActivity.this);
                 exitDialog.setTitleText(getString(org.levimc.launcher.R.string.dialog_title_exit_app))
                         .setMessage(getString(org.levimc.launcher.R.string.dialog_message_exit_app))
                         .setPositiveButton(getString(org.levimc.launcher.R.string.dialog_positive_exit), v -> {
                             exitDialog.dismissImmediately();
                             finishAffinity();
                         })
                         .setNegativeButton(getString(org.levimc.launcher.R.string.dialog_negative_cancel), null)
                         .show();
             }
         };

         getOnBackPressedDispatcher().addCallback(this, onBackPressedCallback);
     }

    /** v705：卡片区布局照官方原版——cards_row 在 XML 里 0dp+weight=1
     *  天然填满剩余空间（手机/平板比例由 weight 适配），不再需要
     *  v456 的运行时响应式调整。 */

    private void refreshAccountHeaderUI() {
        // 登录入口一直显示（不再受设置开关控制）
        MsftAccountStore.MsftAccount active = getActiveAccount();
        if (active == null) {
            if (signInButton != null) signInButton.setVisibility(View.VISIBLE);
            if (accountAvatarContainer != null) accountAvatarContainer.setVisibility(View.GONE);
            if (accountAvatar != null) accountAvatar.setImageDrawable(null);
            lastAvatarXuid = null;
            if (avatarProgress != null) avatarProgress.setVisibility(View.GONE);
        } else {
            if (signInButton != null) signInButton.setVisibility(View.GONE);
            if (accountAvatarContainer != null) accountAvatarContainer.setVisibility(View.VISIBLE);
            loadXboxAvatar(active);
        }
    }

    private void loadXboxAvatar(MsftAccountStore.MsftAccount active) {
        if (accountAvatar == null) return;
        String url = AccountTextUtils.sanitizeUrl(active != null ? active.xboxAvatarUrl : null);
        if (url == null) {
            if (avatarProgress != null) avatarProgress.setVisibility(View.GONE);
            accountAvatar.setImageDrawable(null);
            lastAvatarXuid = null;
            return;
        }

        Object currentUrl = accountAvatar.getTag(R.id.nav_account_avatar);
        if (url.equals(currentUrl) && accountAvatar.getDrawable() != null) {
            if (avatarProgress != null) avatarProgress.setVisibility(View.GONE);
            return;
        }

        Bitmap cached = AccountTextUtils.getCachedAvatar(url);
        if (cached != null) {
            accountAvatar.setTag(R.id.nav_account_avatar, url);
            accountAvatar.setImageBitmap(cached);
            if (avatarProgress != null) avatarProgress.setVisibility(View.GONE);
            return;
        }

        accountAvatar.setTag(R.id.nav_account_avatar, url);
        accountAvatar.setImageDrawable(null);
        if (avatarProgress != null) avatarProgress.setVisibility(View.VISIBLE);
        accountExecutor.execute(() -> {
            try {
                try (Response imgResp = avatarClient.newCall(new Request.Builder().url(url).build()).execute()) {
                    Bitmap bmp = (imgResp.isSuccessful() && imgResp.body() != null) ? android.graphics.BitmapFactory.decodeStream(imgResp.body().byteStream()) : null;
                    runOnUiThread(() -> {
                        if (!url.equals(accountAvatar.getTag(R.id.nav_account_avatar))) return;
                        if (bmp != null) {
                            AccountTextUtils.cacheAvatar(url, bmp);
                            accountAvatar.setImageBitmap(bmp);
                        }
                        if (avatarProgress != null) avatarProgress.setVisibility(View.GONE);
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (avatarProgress != null) avatarProgress.setVisibility(View.GONE);
                });
            }
        });
    }

    private void showAccountSwitchPopup(View anchor) {
        java.util.List<MsftAccountStore.MsftAccount> list = MsftAccountStore.list(this);

        View content = LayoutInflater.from(this).inflate(R.layout.popup_account_switch, null);
        androidx.recyclerview.widget.RecyclerView recyclerAccounts = content.findViewById(R.id.recycler_accounts);
        TextView manageAction = content.findViewById(R.id.manage_action);
        com.microsoft.xbox.idp.toolkit.CircleImageView headerAvatar = content.findViewById(R.id.header_avatar);
        View headerContainer = content.findViewById(R.id.header_container);
        TextView headerName = content.findViewById(R.id.header_name);

        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        int selectableRes = outValue.resourceId;

        int paddingH = (int) (16 * getResources().getDisplayMetrics().density);
        int paddingV = (int) (12 * getResources().getDisplayMetrics().density);
        int paddingR = (int) (12 * getResources().getDisplayMetrics().density);

        MsftAccountStore.MsftAccount active = getActiveAccount();
        headerName.setText(AccountTextUtils.displayNameOrNotSigned(this, active));
        if (accountAvatar != null && accountAvatar.getDrawable() != null) {
            headerAvatar.setImageDrawable(accountAvatar.getDrawable());
        } else if (active != null) {
            final String url = AccountTextUtils.sanitizeUrl(active.xboxAvatarUrl);
            if (url != null) {
                accountExecutor.execute(() -> {
                    try {
                        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                        okhttp3.Response imgResp = client.newCall(new okhttp3.Request.Builder().url(url).build()).execute();
                        final android.graphics.Bitmap bmp = (imgResp.isSuccessful() && imgResp.body() != null) ? android.graphics.BitmapFactory.decodeStream(imgResp.body().byteStream()) : null;
                        runOnUiThread(() -> { if (bmp != null) headerAvatar.setImageBitmap(bmp); });
                    } catch (Exception ignored) {}
                });
            }
        }

        final PopupWindow popup = new PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        content.setAlpha(0f);
        content.setTranslationY(24f);
        float dens = getResources().getDisplayMetrics().density;
        if (headerContainer != null) {
            headerContainer.setAlpha(0f);
            headerContainer.setTranslationY(8f * dens);
        }
        if (headerAvatar != null) {
            headerAvatar.setAlpha(0f);
            headerAvatar.setScaleX(0.94f);
            headerAvatar.setScaleY(0.94f);
        }
        if (headerName != null) {
            headerName.setAlpha(0f);
            headerName.setTranslationY(6f * dens);
        }
        if (manageAction != null) {
            manageAction.setAlpha(0f);
            manageAction.setTranslationX(6f * dens);
        }
        popup.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) popup.setElevation(8f);

        final ViewGroup root = findViewById(android.R.id.content);
        final View scrim = new View(this);
        scrim.setBackgroundColor(ContextCompat.getColor(this, R.color.scrim));
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> popup.dismiss());
        root.addView(scrim, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(120).start();

         final java.util.List<MsftAccountStore.MsftAccount> displayList = new java.util.ArrayList<>();
        for (MsftAccountStore.MsftAccount a : list) {
            if (active == null || !android.text.TextUtils.equals(a.id, active.id)) displayList.add(a);
        }

         class AccountRowViewHolder extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
             TextView tv;
             AccountRowViewHolder(TextView t) { super(t); this.tv = t; }
         }

         recyclerAccounts.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
         recyclerAccounts.setAdapter(new androidx.recyclerview.widget.RecyclerView.Adapter<AccountRowViewHolder>() {
             @Override
             public AccountRowViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                 TextView row = new TextView(parent.getContext());
                 row.setLayoutParams(new androidx.recyclerview.widget.RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                 row.setTextColor(ContextCompat.getColor(parent.getContext(), R.color.on_surface));
                 row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                 row.setPadding(paddingH, paddingV, paddingR, paddingV);
                 row.setBackgroundResource(selectableRes);
                 return new AccountRowViewHolder(row);
             }

             @Override
             public void onBindViewHolder(AccountRowViewHolder holder, int position) {
                 MsftAccountStore.MsftAccount account = displayList.get(position);
                 holder.tv.setText(AccountTextUtils.titleOrUnknown(account));
                 holder.tv.setOnClickListener(v -> {
                     popup.dismiss();

                     MsftAccountStore.setActive(MainActivity.this, account.id);
                     boolean withinSevenDays = AccountTextUtils.isRecentlyUpdated(account, 7);

                     if (withinSevenDays) {
                         runOnUiThread(() -> {
                             DialogUtils.dismissQuietly(accountLoadingDialog);
                             String statusName = AccountTextUtils.displayNameOrNotSigned(MainActivity.this, account);
                             Toast.makeText(MainActivity.this, getString(R.string.ms_login_success, statusName), Toast.LENGTH_SHORT).show();
                             refreshAccountHeaderUI();
                         });
                         return;
                     }

                     accountLoadingDialog = DialogUtils.ensure(MainActivity.this, accountLoadingDialog);
                     DialogUtils.showWithMessage(accountLoadingDialog, getString(R.string.ms_login_auth_xbox_device));

                     accountExecutor.execute(() -> {
                         try {
                             BedrockAuthManager authManager = MsftAuthManager.refreshAndAuth(account);
                             MsftAuthManager.saveAccountOrThrow(MainActivity.this, authManager);
                             MsftAccountStore.setActive(MainActivity.this, account.id);
                             String minecraftUsername = authManager.getMinecraftCertificateChain().getUpToDate().getIdentityDisplayName();

                             runOnUiThread(() -> {
                                 DialogUtils.dismissQuietly(accountLoadingDialog);
                                 String statusName = minecraftUsername != null ? minecraftUsername : getString(R.string.not_signed_in);
                                 Toast.makeText(MainActivity.this, getString(R.string.ms_login_success, statusName), Toast.LENGTH_SHORT).show();
                                 refreshAccountHeaderUI();
                             });
                         } catch (Exception e) {
                             runOnUiThread(() -> {
                                 DialogUtils.dismissQuietly(accountLoadingDialog);
                                 Toast.makeText(MainActivity.this, getString(R.string.ms_login_failed_detail, MsftAuthManager.describeError(e)), Toast.LENGTH_LONG).show();
                                 refreshAccountHeaderUI();
                             });
                         }
                     });
                 });
             }

             @Override
             public int getItemCount() { return displayList.size(); }
         });

         float density = getResources().getDisplayMetrics().density;
         if (displayList.size() > 2) {
             int limitHeight = (int) ((48 * 2 + 16) * density);
             recyclerAccounts.getLayoutParams().height = limitHeight;
         } else {
             recyclerAccounts.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
         }

        manageAction.setOnClickListener(v -> {
            popup.dismiss();
            startActivity(new Intent(this, AccountsActivity.class));
        });
        DynamicAnim.applyPressScale(manageAction);

        popup.setOnDismissListener(() -> {
            if (root != null && scrim != null) {
                scrim.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                    try { root.removeView(scrim); } catch (Exception ignored) {}
                }).start();
            }
        });

        int edgeMargin = (int) (4 * getResources().getDisplayMetrics().density);
        popup.showAsDropDown(anchor, -edgeMargin, edgeMargin / 4, Gravity.END);

        DynamicAnim.springAlphaTo(content, 1f).start();
        DynamicAnim.springTranslationYTo(content, 0f).start();
        recyclerAccounts.post(() -> DynamicAnim.staggerRecyclerChildren(recyclerAccounts));
        if (headerContainer != null) {
            DynamicAnim.springAlphaTo(headerContainer, 1f).start();
            DynamicAnim.springTranslationYTo(headerContainer, 0f).start();
        }
        if (headerAvatar != null) {
            DynamicAnim.springAlphaTo(headerAvatar, 1f).start();
            DynamicAnim.springScaleXTo(headerAvatar, 1f).start();
            DynamicAnim.springScaleYTo(headerAvatar, 1f).start();
        }
        if (headerName != null) {
            DynamicAnim.springAlphaTo(headerName, 1f).start();
            DynamicAnim.springTranslationYTo(headerName, 0f).start();
        }
        if (manageAction != null) {
            DynamicAnim.springAlphaTo(manageAction, 1f).start();
            DynamicAnim.springTranslationXTo(manageAction, 0f).start();
        }
    }

    private void setupManagersAndHandlers() {
        languageManager = new LanguageManager(this);
        languageManager.applySavedLanguage();
        permissionResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (permissionsHandler != null)
                        permissionsHandler.onActivityResult(result.getResultCode(), result.getData());
                }
        );
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                }
        );
        apkImportResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (apkImportManager != null)
                        apkImportManager.handleActivityResult(result.getResultCode(), result.getData());
                }
        );
        permissionsHandler = PermissionsHandler.getInstance();
        permissionsHandler.setActivity(this, permissionResultLauncher);
        initListeners();
    }

    private void initializeAfterMigrationGate() {
        if (isFinishing() || isDestroyed()) return;

        minecraftLauncher = new MinecraftLauncher(this);
        viewModel = new ViewModelProvider(this, new MainViewModelFactory(getApplication())).get(MainViewModel.class);
        apkImportManager = new ApkImportManager(this, viewModel);

        initModsSection();
        initContentManagementSection();
        initMiscellaneousSection();
        initializeVersionManager();
        requestStoragePermissionIfNeeded();
    }

    private void requestStoragePermissionIfNeeded() {
        if (permissionsHandler == null) return;
        if (permissionsHandler.hasPermission(PermissionsHandler.PermissionType.STORAGE)) return;
        permissionsHandler.requestPermission(PermissionsHandler.PermissionType.STORAGE, new PermissionsHandler.PermissionResultCallback() {
            @Override
            public void onPermissionGranted(PermissionsHandler.PermissionType type) {
                // 授权成功后重新扫描版本（首次打开可能因无权限扫描不到版本）
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (versionManager != null) {
                        versionManager.loadAllVersions();
                        setTextMinecraftVersion();
                        updateViewModelVersion();
                        refreshContentCounts();
                    }
                });
            }

            @Override
            public void onPermissionDenied(PermissionsHandler.PermissionType type, boolean permanentlyDenied) {
                Toast.makeText(MainActivity.this, R.string.storage_permission_not_granted, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void initializeVersionManager() {
        binding.launchButton.setEnabled(false);
        versionManager = VersionManager.getIfInitialized();
        if (versionManager != null) {
            onVersionManagerReady();
            return;
        }
        VersionManager.initializeAsync(this, manager -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            versionManager = manager;
            onVersionManagerReady();
        }));
    }

    private void onVersionManagerReady() {
        if (versionManager == null || binding == null) return;
        fileHandler = new FileHandler(this, viewModel, versionManager);
        setTextMinecraftVersion();
        updateViewModelVersion();
        repairNeededVersions();
        binding.launchButton.setEnabled(true);
        handleVersionDependentIntent();
        refreshContentCounts();
        binding.getRoot().postDelayed(this::checkCrashReport, 900);
    }

    private void handleVersionDependentIntent() {
        if (versionManager == null || fileHandler == null) return;
        if (handleInstanceShortcutLaunch()) return;
        handleIncomingMinecraftResource();
        checkResourcepack();
        handleIncomingFiles();
        handleMinecraftUriLaunch();
    }

    /** 官方快捷方式点击进入：直接选中对应版本并启动（InstanceShortcutManager）。 */
    private boolean handleInstanceShortcutLaunch() {
        Intent intent = getIntent();
        if (intent == null || !InstanceShortcutManager.ACTION_LAUNCH_INSTANCE.equals(intent.getAction())) {
            return false;
        }

        String type = intent.getStringExtra(InstanceShortcutManager.EXTRA_INSTANCE_TYPE);
        String key = intent.getStringExtra(InstanceShortcutManager.EXTRA_INSTANCE_KEY);
        intent.setAction(null);
        intent.removeExtra(InstanceShortcutManager.EXTRA_INSTANCE_TYPE);
        intent.removeExtra(InstanceShortcutManager.EXTRA_INSTANCE_KEY);
        setIntent(intent);

        GameVersion target = findShortcutVersion(type, key);
        if (target == null) {
            Toast.makeText(this, R.string.instance_shortcut_missing, Toast.LENGTH_LONG).show();
            return true;
        }

        versionManager.selectVersion(target);
        setTextMinecraftVersion();
        updateViewModelVersion();
        if (viewModel != null) viewModel.refreshMods();
        binding.getRoot().post(this::performActualLaunch);
        return true;
    }

    private GameVersion findShortcutVersion(String type, String key) {
        if (key == null || key.isEmpty()) return null;
        if (InstanceShortcutManager.TYPE_INSTALLED.equals(type)) {
            List<GameVersion> installed = versionManager.getInstalledVersions();
            if (installed != null) {
                for (GameVersion candidate : installed) {
                    if (key.equals(candidate.packageName)) return candidate;
                }
            }
            return null;
        }
        if (InstanceShortcutManager.TYPE_CUSTOM.equals(type)) {
            List<GameVersion> custom = versionManager.getCustomVersions();
            if (custom != null) {
                for (GameVersion candidate : custom) {
                    if (key.equals(candidate.directoryName)) return candidate;
                }
            }
        }
        return null;
    }

    /** 外部分享的 .mcworld/.mcpack/.mcaddon/.mctemplate 由启动器导入到内容管理。
     *  不再转发给运行中的游戏：游戏只认 ACTION_VIEW 深链，转发过去会被静默忽略，
     *  表现为「卡住且无任何提示」。 */
    private void handleIncomingMinecraftResource() {
        Intent intent = getIntent();
        if (contentManager == null || versionManager == null) return;
        if (!MinecraftImportIntents.isMinecraftResourceIntent(this, intent)) return;

        java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (intent.getClipData() != null) {
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                Uri u = intent.getClipData().getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        }
        if (intent.getData() != null) uris.add(intent.getData());
        if (uris.isEmpty()) return;

        for (Uri uri : uris) {
            if (MinecraftImportIntents.isWorldResource(this, uri)) {
                contentManager.importWorld(uri, new org.levimc.launcher.core.content.WorldManager.WorldOperationCallback() {
                    @Override
                    public void onSuccess(String message) {
                        runOnUiThread(() -> {
                            UIHelper.showToast(MainActivity.this, getString(R.string.share_import_world_success));
                            refreshContentCounts();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> UIHelper.showToast(MainActivity.this,
                                getString(R.string.share_import_failed, error != null ? error : "")));
                    }

                    @Override
                    public void onProgress(int progress) {}
                });
            } else {
                contentManager.importResourcePack(uri, new org.levimc.launcher.core.content.ResourcePackManager.PackOperationCallback() {
                    @Override
                    public void onSuccess(String message) {
                        runOnUiThread(() -> {
                            UIHelper.showToast(MainActivity.this, getString(R.string.share_import_pack_success));
                            refreshContentCounts();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> UIHelper.showToast(MainActivity.this,
                                getString(R.string.share_import_failed, error != null ? error : "")));
                    }

                    @Override
                    public void onProgress(int progress) {}
                });
            }
        }
        clearIncomingIntent();
    }

    private void initModsSection() {
        if (viewModel == null) return;
        modsListContainer = binding.modsListContainer;

        binding.manageModsButton.setOnClickListener(v -> openModsFullscreen());
        DynamicAnim.applyPressScale(binding.manageModsButton);

        org.levimc.launcher.util.PersonalizationManager pm = new org.levimc.launcher.util.PersonalizationManager(this);
        int accent = pm.getAccentColor();
        if (accent != 0) {
            binding.manageModsButton.setTextColor(accent);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            gd.setColor(android.graphics.Color.argb(26, android.graphics.Color.red(accent), android.graphics.Color.green(accent), android.graphics.Color.blue(accent)));
            gd.setCornerRadius(5 * getResources().getDisplayMetrics().density);
            gd.setStroke((int)(1 * getResources().getDisplayMetrics().density),
                    android.graphics.Color.argb(51, android.graphics.Color.red(accent), android.graphics.Color.green(accent), android.graphics.Color.blue(accent)));
            binding.manageModsButton.setBackground(gd);

            if (binding.minecraftTitleText != null) {
                pm.applySolidAccentText(binding.minecraftTitleText, accent);
            }
        }

        viewModel.getModsLiveData().observe(this, this::updateModsUI);
    }

    private void updateViewModelVersion() {
        if (viewModel == null) return;
        GameVersion selectedVersion = versionManager.getSelectedVersion();
        if (selectedVersion != null) {
            viewModel.setCurrentVersion(selectedVersion);
        }
    }

    private void checkResourcepack() {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        new ResourcepackHandler(
                this, minecraftLauncher, executorService
        ).checkIntentForResourcepack();
    }

    private void repairNeededVersions() {
        GameVersion selectedVersion = versionManager != null ? versionManager.getSelectedVersion() : null;
        if (selectedVersion != null && selectedVersion.needsRepair) {
            VersionManager.attemptRepairLibs(this, selectedVersion);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sForeground = true;
        // v618：邀请深链补发——游戏 finish 后回启动器走 onResume（双后台
        // 已删，MainActivity 不重建），v601 只挂 onCreate 导致深链丢失
        firePendingDeepLink();
        // v518 久置自愈：后台久置后回前台只显示背景色（实测）——
        // 游戏也在后台时同进程渲染整体停摆，整进程静默重启自愈
        // （游戏靠自动存档兜底）；无游戏时仅重建本窗口。
        if (lastPauseElapsed > 0
                && android.os.SystemClock.elapsedRealtime() - lastPauseElapsed > LONG_PAUSE_HEAL_MS) {
            if (MinecraftActivityState.isRunning()) {
                MinecraftProcessRestarter.INSTANCE.restartLauncherAfterMinecraftExit(this, true);
                finish();
                return;
            }
            recreate();
            return;
        }
        refreshAccountHeaderUI();
        if (versionManager != null) {
            versionManager.loadAllVersions();
            setTextMinecraftVersion();
            viewModel.refreshMods();
            refreshContentCounts();
        }
        if (binding != null && versionManager != null) {
            binding.launchButton.setEnabled(true);
        }
        checkHardcoreBackups();
        // 延迟执行：冷启动时版本列表可能尚未加载完成，立即扫描会空转。
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::captureXalTemplate, 5000);
        // v400 静默烘焙扫描：导入/游玩过的存档缓存不完整时，后台慢速
        // 补烘（启动器开着就持续烘，无需打开卫星图）。延迟 3 秒——
        // 等版本/世界目录就绪；扫描本身轻量（只读缓存文件头）
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                if (contentManager != null) {
                    org.levimc.launcher.core.content.worldmap.SilentBakeManager sb =
                            org.levimc.launcher.core.content.worldmap.SilentBakeManager.get();
                    sb.init(this);
                    sb.scanWorldsDirectories(
                            contentManager.getWorldManager()
                                    .getWorldsDirectories());
                }
            } catch (Throwable ignored) {
            }
        }, 3000);
        // v412：帧监控注册（主线程）——静默烘焙按掉帧率动态调速
        try {
            org.levimc.launcher.core.content.worldmap.SilentBakeManager
                    .registerFrameMonitor();
        } catch (Throwable ignored) {
        }
    }

    /** 游戏退出回到启动器时，扫描版本的 xal 目录，
     *  若存在游戏内登录写出的完整状态则保存为模板（过渡方案）。 */
    private void captureXalTemplate() {
        if (versionManager == null) return;
        new Thread(() -> {
            try {
                java.util.List<GameVersion> versions = new java.util.ArrayList<>();
                versions.addAll(versionManager.getInstalledVersions());
                versions.addAll(versionManager.getCustomVersions());
                for (GameVersion v : versions) {
                    if (v == null) continue;
                    try {
                        java.io.File filesDir = org.levimc.launcher.util.LauncherStorage.getStorageFilesRoot(
                                this, MinecraftLauncher.getStorageProfileId(v), v.versionIsolation, false);
                        if (org.levimc.launcher.core.auth.storage.XalTemplateStore.capture(this, filesDir)) {
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                android.util.Log.w("MainActivity", "Xal template capture failed", t);
            }
        }, "xal-template-capture").start();
    }

    /** 启动器回到前台时静默检查极限存档是否需要定时备份（仅游玩过且达到间隔才备份）。 */
    private void checkHardcoreBackups() {
        HardcoreBackupManager manager = new HardcoreBackupManager(this);
        if (!manager.isEnabled()) return;
        // 仅在「退出后备份」时机下，回到启动器时触发备份
        if (!HardcoreBackupManager.TRIGGER_EXIT.equals(manager.getBackupTrigger())) return;
        new Thread(() -> {
            java.util.List<org.levimc.launcher.core.content.WorldItem> worlds =
                    HardcoreBackupManager.scanHardcoreWorlds(this);
            manager.checkAndBackup(worlds, new HardcoreBackupManager.Callback() {
                @Override
                public void onBackedUp(org.levimc.launcher.core.content.WorldItem world, String backupPath) {
                    android.util.Log.i("HardcoreBackup", "Backed up: " + backupPath);
                }

                @Override
                public void onSkipped(org.levimc.launcher.core.content.WorldItem world, String reason) {
                    android.util.Log.d("HardcoreBackup", "Skipped " + world.getWorldName() + ": " + reason);
                }

                @Override
                public void onFinished(int backedUp, int skipped) {
                    if (backedUp > 0) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                getString(R.string.hardcore_backup_done, backedUp), Toast.LENGTH_SHORT).show());
                    }
                }
            });
        }, "hardcore-auto-backup").start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        sForeground = false;
        lastPauseElapsed = android.os.SystemClock.elapsedRealtime();
    }






    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (permissionsHandler != null) {
            permissionsHandler.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @SuppressLint({"ClickableViewAccessibility", "UnsafeIntentLaunch"})
    private void initListeners() {
        binding.launchButton.setEnabled(false);
        binding.launchButton.setOnClickListener(v -> launchGame());
        DynamicAnim.applyPressScale(binding.launchButton);
        binding.selectVersionButton.setOnClickListener(v -> showVersionSelectDialog());
        DynamicAnim.applyPressScale(binding.selectVersionButton);

        FeatureSettings.init(getApplicationContext());
        showRandomTip();
    }

    private void showRandomTip() {
        String[] tips = getResources().getStringArray(R.array.launcher_tips);
        if (tips.length == 0 || binding.tipText == null) return;
        binding.tipText.setText(tips[new java.util.Random().nextInt(tips.length)]);
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        Runnable rotateTip = new Runnable() {
            @Override
            public void run() {
                if (binding == null || binding.tipText == null) return;
                binding.tipText.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                    if (binding == null || binding.tipText == null) return;
                    binding.tipText.setText(tips[new java.util.Random().nextInt(tips.length)]);
                    binding.tipText.animate().alpha(1f).setDuration(300).start();
                }).start();
                handler.postDelayed(this, 8000);
            }
        };
        handler.postDelayed(rotateTip, 8000);
    }

    private void initContentManagementSection() {
        worldsCountText = binding.contentWorldsCount;
        resourcePacksCountText = binding.contentResourcePacksCount;
        behaviorPacksCountText = binding.contentBehaviorPacksCount;

        contentManager = ContentManager.getInstance(this);
        contentManager.getWorldsLiveData().observe(this, worlds -> {
            if (worldsCountText != null)
                worldsCountText.setText(String.valueOf(worlds != null ? worlds.size() : 0));
        });
        contentManager.getResourcePacksLiveData().observe(this, packs -> {
            if (resourcePacksCountText != null)
                resourcePacksCountText.setText(String.valueOf(packs != null ? packs.size() : 0));
        });
        contentManager.getBehaviorPacksLiveData().observe(this, packs -> {
            if (behaviorPacksCountText != null)
                behaviorPacksCountText.setText(String.valueOf(packs != null ? packs.size() : 0));
        });

        binding.contentViewAll.setOnClickListener(v -> openContentManagement());
        DynamicAnim.applyPressScale(binding.contentViewAll);

        binding.contentWorldsRow.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_WORLDS));
        binding.contentResourcePacksRow.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_RESOURCE_PACKS));
        binding.contentBehaviorPacksRow.setOnClickListener(v -> openContentList(ContentListActivity.TYPE_BEHAVIOR_PACKS));

        refreshContentCounts();
    }

    private void refreshContentCounts() {
        if (versionManager == null || contentManager == null) return;
        GameVersion currentVersion = versionManager.getSelectedVersion();
        if (currentVersion == null) return;

        org.levimc.launcher.settings.FeatureSettings.StorageType storageType =
                org.levimc.launcher.settings.FeatureSettings.StorageType.EXTERNAL;
        storageType = LauncherStorage.normalizeContentStorageType(
                storageType,
                currentVersion.versionIsolation
        );

        java.io.File baseDir = LauncherStorage.getContentGameDataDir(
                this,
                currentVersion.getStorageProfileId(),
                storageType
        );

        contentManager.setStorageDirectories(
                new java.io.File(baseDir, "minecraftWorlds"),
                new java.io.File(baseDir, "resource_packs"),
                new java.io.File(baseDir, "behavior_packs"),
                new java.io.File(baseDir, "skin_packs"),
                new java.io.File(baseDir, "Screenshots"),
                new java.io.File(baseDir, "minecraftpe"));
    }

    private void openContentList(int contentType) {
        GameVersion currentVersion = versionManager != null ? versionManager.getSelectedVersion() : null;
        if (currentVersion == null) {
            Toast.makeText(this, getString(R.string.not_found_version), Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, ContentListActivity.class);
        intent.putExtra(ContentListActivity.EXTRA_CONTENT_TYPE, contentType);
        startActivity(intent);
    }

    private void initMiscellaneousSection() {
        // CurseForge 需要 API Key，无 Key 时隐藏入口避免 403 报错
        String curseforgeKey = org.levimc.launcher.BuildConfig.CURSEFORGE_API_KEY;
        if (curseforgeKey == null || curseforgeKey.trim().isEmpty()) {
            binding.miscCurseforgeRow.setVisibility(View.GONE);
        } else {
            binding.miscCurseforgeRow.setOnClickListener(v -> startActivity(new Intent(this, CurseForgeActivity.class)));
        }
        binding.miscAccountsRow.setOnClickListener(v -> startActivity(new Intent(this, AccountsActivity.class)));
        binding.miscQuickLaunchRow.setOnClickListener(v -> startActivity(new Intent(this, QuickLaunchActivity.class)));
    }

    /** 分享最近一次启动日志（参照 PC 版 LeviLauncher 的日志分享）。 */
    private void shareLaunchLog() {
        java.io.File log = LaunchTrace.getLatestLogFile(this);
        if (log == null || !log.exists()) {
            Toast.makeText(this, R.string.share_launch_log_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", log);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.share_launch_log)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.share_launch_log_empty, Toast.LENGTH_SHORT).show();
        }
    }

    /** 崩溃自动分析（严格模式，参照 PCL）：仅检测到原生崩溃转储时弹出中文分析提示。 */
    private void checkCrashReport() {
        org.levimc.launcher.util.CrashAnalyzer.CrashReport report =
                org.levimc.launcher.util.CrashAnalyzer.consumeCrashReport(this);
        if (report == null) return;

        String stage = (report.lastStage == null || report.lastStage.isEmpty())
                ? getString(R.string.crash_analysis_unknown_stage) : report.lastStage;
        boolean hasMods = report.modNames != null && !report.modNames.isEmpty();

        StringBuilder message = new StringBuilder();
        switch (report.category) {
            case org.levimc.launcher.util.CrashAnalyzer.CATEGORY_MOD_LOADING:
                message.append(getString(R.string.crash_analysis_mod_loading_mods, stage,
                        hasMods ? report.modNames : getString(R.string.crash_analysis_unknown_stage))).append('\n');
                break;
            case org.levimc.launcher.util.CrashAnalyzer.CATEGORY_IN_GAME:
                message.append(hasMods
                        ? getString(R.string.crash_analysis_in_game_mods, report.modNames)
                        : getString(R.string.crash_analysis_in_game_no_mods)).append('\n');
                break;
            case org.levimc.launcher.util.CrashAnalyzer.CATEGORY_LAUNCH_PHASE:
            default:
                message.append(hasMods
                        ? getString(R.string.crash_analysis_launch_phase_mods, stage, report.modNames)
                        : getString(R.string.crash_analysis_launch_phase_no_mods, stage)).append('\n');
                break;
        }
        message.append(getString(R.string.crash_analysis_dump_found)).append('\n');
        message.append(getString(R.string.crash_analysis_hint));

        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.crash_analysis_title))
                .setMessage(message.toString().trim())
                .setPositiveButton(getString(R.string.crash_analysis_share), v -> shareLaunchLog())
                .setNegativeButton(getString(R.string.crash_analysis_ok), null)
                .show();
    }

    private void openModsFullscreen() {
        Intent intent = new Intent(this, ModsFullscreenActivity.class);
        startActivity(intent);
    }


    private void launchGame() {
        if (!isVersionManagerReady()) return;
        performActualLaunch();
    }
    private void performActualLaunch() {
        binding.launchButton.setEnabled(false);
        LaunchTrace trace = LaunchTrace.create(null);
        trace.milestone("Launch requested");

        GameVersion version = versionManager != null ? versionManager.getSelectedVersion() : null;

        if (version == null) {
            trace.warning("Launch cancelled", "No version selected");
            binding.launchButton.setEnabled(true);
            new CustomAlertDialog(this)
                    .setTitleText(getString(R.string.dialog_title_no_version))
                    .setMessage(getString(R.string.dialog_message_no_version))
                    .setPositiveButton(getString(R.string.dialog_positive_ok), null)
                    .show();
            return;
        }

        trace.mark("Launch validation completed", version.directoryName + " " + version.versionCode);

        if (MinecraftActivityState.isRunning()) {
            // 正在运行的版本号与要启动的相同：直接「回到游戏」（把游戏任务栈带回前台）
            String runningDir = MinecraftActivityState.getRunningVersionDir();
            boolean sameVersion = version.directoryName != null && version.directoryName.equals(runningDir);
            if (sameVersion && MinecraftActivityState.bringGameToFront(this)) {
                trace.mark("Returned to running game", version.directoryName);
                binding.launchButton.setEnabled(true);
                return;
            }
            trace.warning("Launch blocked", "Game already running");
            Toast.makeText(this, R.string.game_already_running, Toast.LENGTH_LONG).show();
            binding.launchButton.setEnabled(true);
            return;
        }

        try {
            Intent launchIntent = createMinecraftLaunchIntent();
            launchIntent.putExtra(LaunchTrace.EXTRA_SESSION_ID, trace.getSessionId());
            launchIntent.putExtra(LaunchTrace.EXTRA_STARTED_ELAPSED_MS,
                    android.os.SystemClock.elapsedRealtime() - trace.elapsedMs());
            minecraftLauncher.launch(launchIntent, version, new MinecraftLauncher.LaunchCallback() {
                @Override
                public void onLaunchStarted() {
                    trace.milestone("Loading screen requested");
                }

                @Override
                public void onLaunchFailed(Exception e) {
                    trace.error("Launch failed before loading screen", e.getMessage());
                    runOnUiThread(() -> {
                        if (binding != null) binding.launchButton.setEnabled(true);
                    });
                }
            });
        } catch (Exception e) {
            trace.error("Launch failed before activity start", e.getMessage());
            binding.launchButton.setEnabled(true);
            new CustomAlertDialog(this)
                    .setTitleText(getString(R.string.dialog_title_launch_failed))
                    .setMessage(getString(R.string.dialog_message_launch_failed, e.getMessage()))
                    .setPositiveButton(getString(R.string.dialog_positive_ok), null)
                    .show();
        }
    }

    private Intent createMinecraftLaunchIntent() {
        Intent launchIntent = new Intent();
        Intent sourceIntent = getIntent();
        if (sourceIntent == null) return launchIntent;

        if (sourceIntent.hasExtra("MINECRAFT_URI")) {
            String uriStr = sourceIntent.getStringExtra("MINECRAFT_URI");
            launchIntent.putExtra("MINECRAFT_URI", uriStr);
            // 关键：把 minecraft:// URI 设为 intent 的 data，
            // 游戏（Mojang MainActivity/native）读 intent.getData() 处理 deep link。
            // 注意：Mojang MainActivity.processIntent 只在 action 为 ACTION_VIEW
            //（或 org.chromium.arc.intent.action.VIEW）时才处理 minecraft:// 深链，
            // 不设 action 游戏会直接忽略 URI。
            if (uriStr != null && uriStr.startsWith("minecraft://")) {
                launchIntent.setData(android.net.Uri.parse(uriStr));
                launchIntent.setAction(Intent.ACTION_VIEW);
            }
        }
        if (sourceIntent.hasExtra("MINECRAFT_URI_ACTION")) {
            launchIntent.putExtra("MINECRAFT_URI_ACTION", sourceIntent.getStringExtra("MINECRAFT_URI_ACTION"));
        }
        return launchIntent;
    }

     private void showVersionSelectDialog() {
        if (!isVersionManagerReady()) return;

        List<GameVersion> allVersions = new ArrayList<>();
        List<GameVersion> installed = versionManager.getInstalledVersions();
        List<GameVersion> custom = versionManager.getCustomVersions();
        if (installed != null) allVersions.addAll(installed);
        if (custom != null) allVersions.addAll(custom);

        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_instance_selector, null);
        new PersonalizationManager(this).applyAccentToView(popupView, this);
        PopupWindow popup = new PopupWindow(popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);
        popup.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setElevation(16f);
        popup.setOutsideTouchable(true);

        RecyclerView recycler = popupView.findViewById(R.id.recycler_instances);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        GameVersion selectedVersion = versionManager.getSelectedVersion();
        InstancePopupAdapter adapter = new InstancePopupAdapter(allVersions, selectedVersion);
        recycler.setAdapter(adapter);

        android.widget.EditText searchInput = popupView.findViewById(R.id.search_input);
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s.toString());
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        adapter.setOnItemClickListener(version -> {
            versionManager.selectVersion(version);
            viewModel.setCurrentVersion(version);
            setTextMinecraftVersion();
            popup.dismiss();
        });

        adapter.setOnShortcutClickListener(version -> {
            popup.dismiss();
            addShortcut(version);
        });

        popupView.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int popupWidth = popupView.getMeasuredWidth();
        int anchorWidth = binding.selectVersionButton.getWidth();
        int xOffset = anchorWidth - popupWidth;
        popup.showAsDropDown(binding.selectVersionButton, xOffset, 4);
    }

    /** 把某个版本号添加到桌面快捷方式（官方 InstanceShortcutManager 逻辑）。 */
    private void addShortcut(GameVersion version) {
        if (version == null) return;
        boolean accepted = InstanceShortcutManager.createOrUpdate(this, version, null, null);
        if (!accepted) {
            Toast.makeText(this, getString(R.string.instance_shortcut_not_supported), Toast.LENGTH_SHORT).show();
        }
    }

    private static class InstancePopupAdapter extends RecyclerView.Adapter<InstancePopupAdapter.VH> {
        private static final int TYPE_ITEM = 0;

        private final List<GameVersion> allVersions;
        private List<GameVersion> filteredVersions;
        private final GameVersion selectedVersion;
        private OnItemClickListener listener;
        private OnShortcutClickListener shortcutListener;

        interface OnItemClickListener {
            void onClick(GameVersion version);
        }

        interface OnShortcutClickListener {
            void onShortcutClick(GameVersion version);
        }

        void setOnItemClickListener(OnItemClickListener l) { this.listener = l; }

        void setOnShortcutClickListener(OnShortcutClickListener l) { this.shortcutListener = l; }

        InstancePopupAdapter(List<GameVersion> versions, GameVersion selected) {
            this.allVersions = versions;
            this.filteredVersions = new ArrayList<>(versions);
            this.selectedVersion = selected;
        }

        void filter(String query) {
            if (query == null || query.isEmpty()) {
                filteredVersions = new ArrayList<>(allVersions);
            } else {
                String q = query.toLowerCase();
                filteredVersions = new ArrayList<>();
                for (GameVersion v : allVersions) {
                    String name = getInstanceDisplayName(v).toLowerCase();
                    String code = v.versionCode != null ? v.versionCode.toLowerCase() : "";
                    String dir = v.directoryName != null ? v.directoryName.toLowerCase() : "";
                    if (name.contains(q) || code.contains(q) || dir.contains(q)) {
                        filteredVersions.add(v);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_instance_popup, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            GameVersion v = filteredVersions.get(position);
            boolean isSelected = selectedVersion != null
                    && selectedVersion.directoryName != null
                    && selectedVersion.directoryName.equals(v.directoryName);

            holder.name.setText(getInstanceDisplayName(v));
            String versionText = getInstanceVersionText(v);
            holder.version.setText(versionText);
            holder.version.setVisibility(TextUtils.isEmpty(versionText) ? View.GONE : View.VISIBLE);
            holder.itemView.setActivated(isSelected);
            holder.check.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            holder.tag.setVisibility(View.GONE);
            applyInstanceSelectionStyle(holder, isSelected);

            holder.itemView.setOnClickListener(_v -> {
                if (listener != null) listener.onClick(v);
            });

            holder.shortcut.setOnClickListener(_v -> {
                if (shortcutListener != null) shortcutListener.onShortcutClick(v);
            });
        }

        private static void applyInstanceSelectionStyle(@NonNull VH holder, boolean isSelected) {
            android.content.Context context = holder.itemView.getContext();
            PersonalizationManager pm = new PersonalizationManager(context);
            int accent = pm.getAccentColor();
            if (accent == 0) {
                accent = ContextCompat.getColor(context, R.color.primary);
            }

            float density = context.getResources().getDisplayMetrics().density;
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.RECTANGLE);
            background.setCornerRadius(10 * density);
            if (isSelected) {
                background.setColor(Color.argb(26, Color.red(accent), Color.green(accent), Color.blue(accent)));
                background.setStroke(Math.max(1, (int) (1 * density)), accent);
            } else {
                background.setColor(Color.TRANSPARENT);
                background.setStroke(0, Color.TRANSPARENT);
            }
            holder.itemView.setBackground(background);
            holder.check.setImageTintList(ColorStateList.valueOf(accent));
        }

        @Override public int getItemCount() { return filteredVersions.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView name, version, tag;
            ImageView check, shortcut;
            VH(View v) {
                super(v);
                name = v.findViewById(R.id.instance_name);
                version = v.findViewById(R.id.instance_version);
                tag = v.findViewById(R.id.instance_tag);
                check = v.findViewById(R.id.instance_check);
                shortcut = v.findViewById(R.id.instance_shortcut);
            }
        }
    }

    private void startFilePicker(String type, ActivityResultLauncher<Intent> launcher) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type);
        launcher.launch(intent);
    }

    private void startApkFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {"application/vnd.android.package-archive", "application/octet-stream", "application/zip"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        apkImportResultLauncher.launch(intent);
    }

    private void openContentManagement() {
        GameVersion currentVersion = versionManager != null ? versionManager.getSelectedVersion() : null;
        if (currentVersion == null) {
            Toast.makeText(this, getString(R.string.not_found_version), Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, ContentManagementActivity.class);
        startActivity(intent);
    }


     public void setTextMinecraftVersion() {
        if (binding == null) return;
        if (versionManager == null) {
            binding.textMinecraftVersion.setText(getString(R.string.not_found_version));
            return;
        }
        GameVersion selectedVersion = versionManager.getSelectedVersion();
        String instanceName = selectedVersion != null ? getInstanceDisplayName(selectedVersion) : null;
        binding.textMinecraftVersion.setText(TextUtils.isEmpty(instanceName) ? getString(R.string.not_found_version) : instanceName);
    }

    private boolean isVersionManagerReady() {
        if (versionManager != null) return true;
        Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show();
        return false;
    }

    private static String getInstanceDisplayName(GameVersion version) {
        if (version == null) return "";

        String displayName = stripVersionSuffix(version.displayName, version.versionCode);
        if (!TextUtils.isEmpty(displayName)) return displayName;
        if (!TextUtils.isEmpty(version.directoryName)) return version.directoryName;
        return !TextUtils.isEmpty(version.versionCode) ? version.versionCode : "";
    }

    private static String getInstanceVersionText(GameVersion version) {
        if (version == null) return "";
        if (!TextUtils.isEmpty(version.versionCode)) return version.versionCode;
        return !TextUtils.isEmpty(version.directoryName) ? version.directoryName : "";
    }

    private static String stripVersionSuffix(String displayName, String versionCode) {
        if (TextUtils.isEmpty(displayName)) return "";

        String trimmedName = displayName.trim();
        if (TextUtils.isEmpty(versionCode)) return trimmedName;

        String suffix = " (" + versionCode + ")";
        if (trimmedName.endsWith(suffix)) {
            return trimmedName.substring(0, trimmedName.length() - suffix.length()).trim();
        }
        return trimmedName;
    }

    private void handleIncomingFiles() {
        if (fileHandler == null) return;
        Intent intent = getIntent();
        if (MinecraftImportIntents.isMinecraftResourceIntent(this, intent)) {
            return;
        }
        if (intent != null && intent.getData() != null) {
            Uri data = intent.getData();
            if ("minecraft".equals(data.getScheme())) {
                return;
            }
        }
        fileHandler.processIncomingFilesWithConfirmation(intent, new FileHandler.FileOperationCallback() {
            @Override
            public void onSuccess(int processedFiles) {
                if (processedFiles > 0)
                    UIHelper.showToast(MainActivity.this, getString(R.string.files_processed, processedFiles));
            }

            @Override
            public void onError(String errorMessage) {
                if (errorMessage != null && !errorMessage.isEmpty()) {
                    UIHelper.showToast(MainActivity.this, errorMessage);
                }
            }

            @Override
            public void onProgressUpdate(int progress) {
                if (binding != null) binding.progressLoader.setProgress(progress);
            }
        }, false);
    }

    private void clearIncomingIntent() {
        Intent cleanIntent = new Intent(this, MainActivity.class);
        setIntent(cleanIntent);
    }

    private void handleMinecraftUriLaunch() {
        Intent intent = getIntent();
        if (intent == null) return;
        if (intent.getBooleanExtra("LAUNCH_WITH_URI", false)) {
            intent.removeExtra("LAUNCH_WITH_URI");
            setIntent(intent);
            binding.getRoot().post(this::launchGame);
        }
    }

    private void updateModsUI(List<Mod> mods) {
        if (binding == null || modsListContainer == null) return;
        modsListContainer.removeAllViews();

        // Add enabled external mods
        if (mods != null) {
            for (Mod mod : mods) {
                if (mod.isEnabled()) {
                    addModNameEntry(mod.getDisplayName());
                }
            }
        }


    }

    private void addModNameEntry(String name) {
        TextView tv = new TextView(this);
        tv.setText(name);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.on_surface));
        tv.setFontFeatureSettings(null);
        tv.setTypeface(getResources().getFont(R.font.misans));
        tv.setPadding(0, (int)(3 * getResources().getDisplayMetrics().density), 0, (int)(3 * getResources().getDisplayMetrics().density));
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        modsListContainer.addView(tv);
    }

    private void setupNavBar() {
        setActiveNavTab(R.id.nav_tab_launch);
        findViewById(R.id.nav_tab_launch).setOnClickListener(v -> {});
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

 }

