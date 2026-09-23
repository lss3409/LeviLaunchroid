package org.levimc.launcher.ui.activities;

import android.os.Bundle;

import org.levimc.launcher.R;

/**
 * 联机页占位（v488）：栏位预留，页面仅居中显示"虚位以待"。
 * 联机模块落地后在此实现真实内容。
 */
public final class OnlineActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_online);
        setActiveNavTab(R.id.nav_tab_online);
    }
}
