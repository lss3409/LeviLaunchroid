package org.levimc.launcher.core.online;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 调试后门入口（仅 debug 构建，v512）：vivo OriginOS 拦截调试广播且 adb 输入乱码，
 * OnlineActivity 又未导出——用这个导出的中转 Activity 把启动参数转交给联机页：
 * 加入：adb shell am start -n org.levimc.launcher/core.online.OnlineDebugActivity \
 *   --es debug_join_code 8ZOZMCJDZFFAL450 --es debug_join_peer tcp://111.230.150.198:11010
 * 建房（v516，固定码 TEST-TEST-TEST-TES5，免 OCR）：
 *   adb shell am start -n org.levimc.launcher/core.online.OnlineDebugActivity --ez debug_host true
 */
public final class OnlineDebugActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent fwd = new Intent(this, org.levimc.launcher.ui.activities.OnlineActivity.class);
        fwd.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        String code = getIntent().getStringExtra("debug_join_code");
        String peer = getIntent().getStringExtra("debug_join_peer");
        boolean host = getIntent().getBooleanExtra("debug_host", false);
        if (code != null) {
            fwd.putExtra("debug_join_code", code);
        }
        if (peer != null) {
            fwd.putExtra("debug_join_peer", peer);
        }
        if (host) {
            fwd.putExtra("debug_host", true);
        }
        startActivity(fwd);
        finish();
    }
}
