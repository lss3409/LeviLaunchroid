package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;
import android.widget.Toast;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextEditorActivity extends BaseActivity {

    public static final String EXTRA_PATH = "path";

    private File file;
    private TextView previewText;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_editor);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        file = path != null ? new File(path) : null;

        TextView titleText = findViewById(R.id.text_title);
        previewText = findViewById(R.id.text_preview);

        if (file != null) {
            titleText.setText(file.getName());
        }

        findViewById(R.id.text_back).setOnClickListener(v -> finish());
        findViewById(R.id.text_close).setOnClickListener(v -> finish());
        findViewById(R.id.text_external_button).setOnClickListener(v -> {
            if (file != null) openInMt(file);
        });

        loadContent();
    }

    @Override
    protected boolean shouldSkipNavBar() {
        return true;
    }

    private void loadContent() {
        if (file == null || !file.exists()) {
            previewText.setText(getString(R.string.file_not_found));
            return;
        }
        if (file.length() > 256 * 1024) {
            previewText.setText(getString(R.string.file_too_large));
            return;
        }
        executor.execute(() -> {
            String text = readFile();
            if (isJsonFile()) {
                SpannableString highlighted = highlightJson(text);
                mainHandler.post(() -> previewText.setText(highlighted));
            } else {
                mainHandler.post(() -> previewText.setText(text));
            }
        });
    }

    private String readFile() {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 5 * 1024 * 1024)];
            int read = fis.read(data);
            return new String(data, 0, Math.max(0, read), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private boolean isJsonFile() {
        return file != null && file.getName().toLowerCase().endsWith(".json");
    }

    private SpannableString highlightJson(String text) {
        SpannableString spannable = new SpannableString(text);
        int blue = 0xFF2196F3;
        int green = 0xFF4CAF50;
        int orange = 0xFFFF9800;
        int purple = 0xFF9C27B0;

        applyPattern(spannable, text, Pattern.compile("\"([^\"\\\\]|\\\\.)*\""), green);
        applyPattern(spannable, text, Pattern.compile("-?\\b\\d+(\\.\\d+)?([eE][+-]?\\d+)?\\b"), orange);
        applyPattern(spannable, text, Pattern.compile("\\b(true|false|null)\\b"), purple);
        applyPattern(spannable, text, Pattern.compile("\"[^\"]+\"\\s*:"), blue);

        return spannable;
    }

    private void applyPattern(SpannableString spannable, String text, Pattern pattern, int color) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            spannable.setSpan(new ForegroundColorSpan(color), matcher.start(), matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void openInMt(File file) {
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.setPackage("bin.mt.plus");
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.no_file_manager, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
