package org.levimc.launcher.ui.activities;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.SeekBar;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FileManagerActivity extends BaseActivity {

    public static final String EXTRA_PATH = "path";

    private File currentDir;
    private File rootDir;
    private File pendingCopy;
    private android.media.MediaPlayer mediaPlayer;
    private android.widget.LinearLayout audioPanel;
    private ImageView audioPlayPause;
    private TextView audioName;
    private TextView audioCurrent;
    private TextView audioDuration;
    private SeekBar audioSeek;
    private final Handler audioHandler = new Handler(Looper.getMainLooper());

    private android.widget.LinearLayout pathContainer;
    private Button pasteButton;
    private FileAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        currentDir = resolveStartDir(path);
        rootDir = currentDir;

        pathContainer = findViewById(R.id.fm_path_container);
        pasteButton = findViewById(R.id.fm_paste_button);
        RecyclerView listView = findViewById(R.id.fm_list);

        adapter = new FileAdapter();
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setAdapter(adapter);

        audioPanel = findViewById(R.id.fm_audio_panel);
        audioPlayPause = findViewById(R.id.fm_audio_play_pause);
        audioName = findViewById(R.id.fm_audio_name);
        audioCurrent = findViewById(R.id.fm_audio_current);
        audioDuration = findViewById(R.id.fm_audio_duration);
        audioSeek = findViewById(R.id.fm_audio_seek);

        findViewById(R.id.fm_back).setOnClickListener(v -> goUpOrFinish());
        findViewById(R.id.fm_close).setOnClickListener(v -> finish());
        findViewById(R.id.fm_new_folder_button).setOnClickListener(v -> promptNewFolder());
        pasteButton.setOnClickListener(v -> doPaste());
        audioPlayPause.setOnClickListener(v -> togglePlayPause());
        audioSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer.seekTo(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        loadDirectory();
    }

    @Override
    protected boolean shouldSkipNavBar() {
        return true;
    }

    private File resolveStartDir(String path) {
        File dir = null;
        if (path != null && !path.isEmpty()) {
            dir = new File(path);
        }
        // 传入的是文件（如 .mcstructure）时，定位到它所在目录
        if (dir != null && dir.isFile()) {
            dir = dir.getParentFile();
        }
        if (dir == null || !dir.isDirectory()) {
            dir = new File("/storage/emulated/0");
            if (!dir.isDirectory()) {
                dir = getFilesDir();
            }
        }
        return dir;
    }

    private void loadDirectory() {
        buildBreadcrumb();
        File[] files = currentDir.listFiles();
        List<File> list = new ArrayList<>();
        if (files != null) {
            list.addAll(Arrays.asList(files));
        }
        Collections.sort(list, (a, b) -> {
            boolean aDir = a.isDirectory();
            boolean bDir = b.isDirectory();
            if (aDir != bDir) return aDir ? -1 : 1;
            return a.getName().toLowerCase().compareTo(b.getName().toLowerCase());
        });
        adapter.setFiles(list);
        updatePasteButton();
    }

    private void buildBreadcrumb() {
        pathContainer.removeAllViews();
        // 第一段是根目录（包目录名），之后是到当前目录的子目录
        addBreadcrumbSegment(rootDir.getName(), rootDir);
        List<File> chain = new ArrayList<>();
        File f = currentDir;
        while (f != null && !f.equals(rootDir)) {
            chain.add(0, f);
            f = f.getParentFile();
        }
        for (File seg : chain) {
            addSeparator();
            addBreadcrumbSegment(seg.getName(), seg);
        }
    }

    private void addBreadcrumbSegment(String label, File target) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFF2196F3);
        tv.setTextSize(14);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setOnClickListener(v -> {
            currentDir = target;
            loadDirectory();
        });
        pathContainer.addView(tv);
    }

    private void addSeparator() {
        TextView tv = new TextView(this);
        tv.setText(" / ");
        tv.setTextColor(getColor(R.color.text_secondary));
        tv.setTextSize(14);
        pathContainer.addView(tv);
    }

    private void goUpOrFinish() {
        if (currentDir.equals(rootDir)) {
            finish();
            return;
        }
        File parent = currentDir.getParentFile();
        if (parent != null && parent.exists()) {
            currentDir = parent;
            loadDirectory();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        goUpOrFinish();
    }

    private void updatePasteButton() {
        pasteButton.setVisibility(pendingCopy != null ? View.VISIBLE : View.GONE);
    }

    private boolean isImageFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".webp") || name.endsWith(".gif");
    }

    private boolean isAudioFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".ogg")
                || name.endsWith(".m4a") || name.endsWith(".flac") || name.endsWith(".aac");
    }

    private boolean isTextFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".txt") || name.endsWith(".json") || name.endsWith(".xml")
                || name.endsWith(".log") || name.endsWith(".properties") || name.endsWith(".mcmeta")
                || name.endsWith(".lang") || name.endsWith(".html") || name.endsWith(".css")
                || name.endsWith(".js") || name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private void promptNewFolder() {
        promptName(getString(R.string.new_folder), "", name -> {
            File dir = new File(currentDir, name);
            if (dir.exists()) {
                Toast.makeText(this, R.string.file_exists, Toast.LENGTH_SHORT).show();
                return;
            }
            if (dir.mkdirs()) {
                loadDirectory();
            } else {
                Toast.makeText(this, R.string.operation_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void doPaste() {
        if (pendingCopy == null) return;
        File target = new File(currentDir, pendingCopy.getName());
        if (target.exists()) {
            Toast.makeText(this, R.string.file_exists, Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            boolean ok = copyRecursively(pendingCopy, target);
            runOnUiThread(() -> {
                if (ok) {
                    Toast.makeText(this, getString(R.string.copied_to, target.getAbsolutePath()), Toast.LENGTH_SHORT).show();
                    pendingCopy = null;
                    updatePasteButton();
                    loadDirectory();
                } else {
                    Toast.makeText(this, R.string.operation_failed, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private boolean copyRecursively(File source, File target) {
        try {
            if (source.isDirectory()) {
                if (!target.mkdirs()) return false;
                File[] children = source.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (!copyRecursively(child, new File(target, child.getName()))) return false;
                    }
                }
                return true;
            } else {
                try (FileInputStream in = new FileInputStream(source);
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                }
                return true;
            }
        } catch (IOException e) {
            return false;
        }
    }

    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return file.delete();
    }

    private void showFileActions(File file) {
        String[] actions = {
                getString(R.string.copy),
                getString(R.string.rename),
                getString(R.string.delete)
        };
        new CustomAlertDialog(this)
                .setTitleText(file.getName())
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        pendingCopy = file;
                        updatePasteButton();
                        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
                    } else if (which == 1) {
                        promptRename(file);
                    } else if (which == 2) {
                        confirmDelete(file);
                    }
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void promptRename(File file) {
        promptName(getString(R.string.rename), file.getName(), name -> {
            File target = new File(file.getParentFile(), name);
            if (target.exists()) {
                Toast.makeText(this, R.string.file_exists, Toast.LENGTH_SHORT).show();
                return;
            }
            if (file.renameTo(target)) {
                Toast.makeText(this, getString(R.string.renamed_to, name), Toast.LENGTH_SHORT).show();
                loadDirectory();
            } else {
                Toast.makeText(this, R.string.operation_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void confirmDelete(File file) {
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.delete))
                .setMessage(getString(R.string.delete_confirm, file.getName()))
                .setPositiveButton(getString(R.string.delete), v -> {
                    new Thread(() -> {
                        boolean ok = deleteRecursively(file);
                        runOnUiThread(() -> {
                            if (ok) {
                                Toast.makeText(this, getString(R.string.deleted, file.getName()), Toast.LENGTH_SHORT).show();
                                loadDirectory();
                            } else {
                                Toast.makeText(this, R.string.operation_failed, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }).start();
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void promptName(String title, String initial, NameCallback callback) {
        final EditText input = new EditText(this);
        input.setText(initial);
        input.setSingleLine(true);
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, pad);
        container.addView(input);

        new CustomAlertDialog(this)
                .setTitleText(title)
                .setCustomView(container)
                .setPositiveButton(getString(R.string.confirm), v -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        callback.onName(name);
                    }
                })
                .setNegativeButton(getString(R.string.cancel), null)
                .show();
    }

    private void showImagePreview(File file) {
        ImageView imageView = new ImageView(this);
        imageView.setAdjustViewBounds(true);
        imageView.setMaxHeight((int) (getResources().getDisplayMetrics().heightPixels * 0.45f));

        new CustomAlertDialog(this)
                .setTitleText(file.getName())
                .setCustomView(imageView)
                .setNegativeButton(getString(R.string.close), null)
                .show();

        new Thread(() -> {
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            runOnUiThread(() -> {
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap);
                }
            });
        }).start();
    }

    private void openTextEditor(File file) {
        Intent intent = new Intent(this, TextEditorActivity.class);
        intent.putExtra(TextEditorActivity.EXTRA_PATH, file.getAbsolutePath());
        startActivity(intent);
    }

    private void toggleAudioPlayback(File file) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }
            mediaPlayer = new android.media.MediaPlayer();
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> runOnUiThread(() -> {
                audioPlayPause.setImageResource(R.drawable.ic_play);
                audioSeek.setProgress(audioSeek.getMax());
                audioCurrent.setText(audioDuration.getText());
            }));
            mediaPlayer.start();
            audioPanel.setVisibility(View.VISIBLE);
            audioName.setText(file.getName());
            int duration = mediaPlayer.getDuration();
            if (duration <= 0) duration = 1000;
            audioSeek.setMax(duration);
            audioSeek.setProgress(0);
            audioDuration.setText(formatTime(duration));
            audioCurrent.setText("0:00");
            audioPlayPause.setImageResource(R.drawable.ic_pause);
            startAudioProgress();
        } catch (Exception e) {
            Toast.makeText(this, R.string.audio_play_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            audioPlayPause.setImageResource(R.drawable.ic_play);
        } else {
            mediaPlayer.start();
            audioPlayPause.setImageResource(R.drawable.ic_pause);
        }
    }

    private void startAudioProgress() {
        audioHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (mediaPlayer != null) {
                    audioSeek.setProgress(mediaPlayer.getCurrentPosition());
                    audioCurrent.setText(formatTime(mediaPlayer.getCurrentPosition()));
                    audioHandler.postDelayed(this, 250);
                }
            }
        }, 500);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        audioHandler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) {
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    private interface NameCallback {
        void onName(String name);
    }

    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.FileViewHolder> {
        private List<File> files = new ArrayList<>();

        void setFiles(List<File> files) {
            this.files = files;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_file_manager, parent, false);
            return new FileViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
            File file = files.get(position);
            holder.nameText.setText(file.getName());

            // 文件夹/普通文件图标需要主题色 tint；彩色缩略图必须清掉 tint，
            // 否则 app:tint 的 SRC_IN 滤镜会把 bitmap 染成单色（黑白）。
            ColorStateList iconTint = ColorStateList.valueOf(
                    ContextCompat.getColor(holder.iconView.getContext(), R.color.on_surface));

            if (file.isDirectory()) {
                holder.iconView.setImageTintList(iconTint);
                holder.iconView.setImageResource(R.drawable.ic_folder);
                holder.sizeText.setText("");
            } else if (isImageFile(file)) {
                holder.iconView.setImageTintList(null);
                holder.iconView.setImageResource(R.drawable.ic_file);
                holder.sizeText.setText(formatSize(file.length()));
                loadThumbnail(file, holder);
            } else {
                holder.iconView.setImageTintList(iconTint);
                holder.iconView.setImageResource(R.drawable.ic_file);
                holder.sizeText.setText(formatSize(file.length()));
            }

            holder.itemView.setOnClickListener(v -> {
                if (file.isDirectory()) {
                    currentDir = file;
                    loadDirectory();
                } else if (isImageFile(file)) {
                    showImagePreview(file);
                } else if (isAudioFile(file)) {
                    toggleAudioPlayback(file);
                } else if (isTextFile(file)) {
                    openTextEditor(file);
                }
            });

            holder.itemView.setOnLongClickListener(v -> {
                showFileActions(file);
                return true;
            });
        }

        private void loadThumbnail(File file, FileViewHolder holder) {
            final String path = file.getAbsolutePath();
            holder.iconView.setTag(path);
            new Thread(() -> {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 4;
                Bitmap bitmap = BitmapFactory.decodeFile(path, opts);
                runOnUiThread(() -> {
                    if (bitmap != null && path.equals(holder.iconView.getTag())) {
                        holder.iconView.setImageBitmap(bitmap);
                    }
                });
            }).start();
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        class FileViewHolder extends RecyclerView.ViewHolder {
            ImageView iconView;
            TextView nameText;
            TextView sizeText;

            FileViewHolder(@NonNull View itemView) {
                super(itemView);
                iconView = itemView.findViewById(R.id.file_icon);
                nameText = itemView.findViewById(R.id.file_name);
                sizeText = itemView.findViewById(R.id.file_size);
            }
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(java.util.Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private String formatTime(int millis) {
        int totalSeconds = millis / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(java.util.Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

}
