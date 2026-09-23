package org.levimc.launcher.ui.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.levimc.launcher.R;
import org.levimc.launcher.core.online.RelayStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 中转服务器设置页（v500）：服务器列表（连通测试+删除）+ 添加表单。
 */
public final class OnlineRelayActivity extends BaseActivity {

    private LinearLayout listContainer;
    private EditText addInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_online_relay);
        listContainer = findViewById(R.id.relay_list_container);
        addInput = findViewById(R.id.relay_add_input);
        findViewById(R.id.relay_add_button).setOnClickListener(v -> onAddClicked());
        refreshList();
    }

    private void onAddClicked() {
        String uri = RelayStore.normalize(addInput.getText().toString());
        if (uri.isEmpty()) {
            return;
        }
        List<String> uris = new ArrayList<>(RelayStore.load(this));
        if (!uris.contains(uri)) {
            uris.add(uri);
            RelayStore.save(this, uris);
            addInput.setText("");
        }
        refreshList();
    }

    /** 重建列表：每行 = 地址 + 状态徽章 + 测试按钮 + 删除按钮。 */
    private void refreshList() {
        listContainer.removeAllViews();
        List<String> uris = RelayStore.load(this);
        if (uris.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.online_relay_none);
            empty.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
            empty.setPadding(0, 16, 0, 16);
            listContainer.addView(empty);
            return;
        }
        for (int i = 0; i < uris.size(); i++) {
            final String uri = uris.get(i);
            listContainer.addView(buildRow(uri));
        }
    }

    private View buildRow(final String uri) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(24, 20, 24, 20);
        row.setBackgroundResource(R.drawable.bg_rounded_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);
        row.setLayoutParams(lp);

        TextView addr = new TextView(this);
        addr.setText(uri);
        addr.setTextSize(13);
        addr.setTextColor(getResources().getColor(R.color.on_surface, getTheme()));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(addr, alp);

        TextView status = new TextView(this);
        status.setText(R.string.online_relay_untested);
        status.setTextSize(12);
        status.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
        row.addView(status);

        // 测试按钮：TCP 连接 2s 超时判连通
        TextView test = new TextView(this);
        test.setText(R.string.online_relay_test);
        test.setTextSize(12);
        test.setTextColor(getResources().getColor(R.color.primary, getTheme()));
        test.setPadding(24, 8, 24, 8);
        test.setOnClickListener(v -> {
            status.setText(R.string.online_relay_testing);
            status.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
            new Thread(() -> {
                boolean ok = testRelay(uri);
                runOnUiThread(() -> {
                    status.setText(ok ? R.string.online_relay_ok : R.string.online_relay_fail);
                    status.setTextColor(getResources().getColor(
                            ok ? R.color.primary : R.color.error, getTheme()));
                });
            }, "relay-test").start();
        });
        row.addView(test);

        TextView del = new TextView(this);
        del.setText(R.string.online_relay_delete);
        del.setTextSize(12);
        del.setTextColor(getResources().getColor(R.color.error, getTheme()));
        del.setPadding(24, 8, 0, 8);
        del.setOnClickListener(v -> {
            List<String> uris = new ArrayList<>(RelayStore.load(this));
            uris.remove(uri);
            RelayStore.save(this, uris);
            refreshList();
        });
        row.addView(del);
        return row;
    }

    /** TCP 连接测试（2 秒超时）。 */
    private boolean testRelay(String uri) {
        try (java.net.Socket s = new java.net.Socket()) {
            String host = uri;
            int port = 11010;
            String t = uri.substring(uri.indexOf("://") + 3);
            int colon = t.lastIndexOf(':');
            if (colon > 0) {
                host = t.substring(0, colon);
                try {
                    port = Integer.parseInt(t.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                }
            } else {
                host = t;
            }
            s.connect(new java.net.InetSocketAddress(host, port), 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
