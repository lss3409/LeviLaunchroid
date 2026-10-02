package org.levimc.launcher.filemanager.ui;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件管理器内置编辑器的轻量语法高亮（v668）。
 * 按扩展名选用规则集，正则扫描：字符串/注释优先，数字/JSON 键/XML 标签/MD 标题次之。
 * 大文件（>512KB）跳过，由调用方防抖调度。
 */
public class FmHighlighter {

    private static final int MAX_LEN = 512 * 1024;

    /** 暗色主题亮色系（VS Code Dark+ 风格） */
    private static final int D_STRING = 0xFFCE9178;
    private static final int D_COMMENT = 0xFF6A9955;
    private static final int D_NUMBER = 0xFFB5CEA8;
    private static final int D_KEY = 0xFF9CDCFE;
    private static final int D_TAG = 0xFF569CD6;

    /** 浅色主题暗色系（VS Code Light+ 风格） */
    private static final int L_STRING = 0xFFA31515;
    private static final int L_COMMENT = 0xFF008000;
    private static final int L_NUMBER = 0xFF098658;
    private static final int L_KEY = 0xFF0451A5;
    private static final int L_TAG = 0xFF800000;

    // 字符串："..."（转义感知）
    private static final Pattern P_STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    // 注释：// 到行尾；# 到行尾（lang/properties/yaml/md 的 # 由规则集决定）
    private static final Pattern P_LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern P_HASH_COMMENT = Pattern.compile("#[^\\n]*");
    private static final Pattern P_BLOCK_COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/");
    // 数字
    private static final Pattern P_NUMBER = Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    // JSON 键："key" :
    private static final Pattern P_KEY = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"\\s*:");
    // XML 标签
    private static final Pattern P_TAG = Pattern.compile("</?[a-zA-Z][^>]*>");
    // Markdown 标题
    private static final Pattern P_MD_HEAD = Pattern.compile("^#{1,6}\\s.*$", Pattern.MULTILINE);

    public static Spannable apply(String text, String fileName, boolean dark) {
        if (text == null || text.length() > MAX_LEN) {
            return new SpannableString(text == null ? "" : text);
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        boolean json = lower.endsWith(".json") || lower.endsWith(".json5") || lower.endsWith(".mcmeta");
        boolean xml = lower.endsWith(".xml") || lower.endsWith(".html") || lower.endsWith(".htm");
        boolean md = lower.endsWith(".md") || lower.endsWith(".markdown");
        boolean hashComment = lower.endsWith(".lang") || lower.endsWith(".properties")
                || lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".ini")
                || lower.endsWith(".cfg") || lower.endsWith(".conf") || lower.endsWith(".txt");
        boolean jsLike = lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".css")
                || lower.endsWith(".json") || lower.endsWith(".java") || lower.endsWith(".kt")
                || lower.endsWith(".c") || lower.endsWith(".cpp") || lower.endsWith(".h");

        Spannable sp = new SpannableString(text);
        int sString = dark ? D_STRING : L_STRING;
        int sComment = dark ? D_COMMENT : L_COMMENT;
        int sNumber = dark ? D_NUMBER : L_NUMBER;
        int sKey = dark ? D_KEY : L_KEY;
        int sTag = dark ? D_TAG : L_TAG;

        // 1) 字符串（所有规则集）
        applyPattern(sp, P_STRING, sString, false);
        // 2) 注释
        applyPattern(sp, P_BLOCK_COMMENT, sComment, false);
        if (jsLike || xml) {
            applyPattern(sp, P_LINE_COMMENT, sComment, false);
        }
        if (hashComment) {
            applyPattern(sp, P_HASH_COMMENT, sComment, false);
        }
        if (xml) {
            applyPattern(sp, Pattern.compile("<!--[\\s\\S]*?-->"), sComment, false);
        }
        // 3) 数字
        applyPattern(sp, P_NUMBER, sNumber, true);
        // 4) JSON 键
        if (json) {
            applyPattern(sp, P_KEY, sKey, true);
        }
        // 5) XML 标签
        if (xml) {
            applyPattern(sp, P_TAG, sTag, true);
        }
        // 6) MD 标题：加粗
        if (md) {
            Matcher m = P_MD_HEAD.matcher(text);
            while (m.find()) {
                sp.setSpan(new StyleSpan(Typeface.BOLD), m.start(), m.end(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sp;
    }

    /** 匹配染色；skipColored 为 true 时跳过已有前景色的区间。 */
    private static void applyPattern(Spannable sp, Pattern pattern, int color, boolean skipColored) {
        Matcher m = pattern.matcher(sp.toString());
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            if (skipColored) {
                ForegroundColorSpan[] existing = sp.getSpans(start, end, ForegroundColorSpan.class);
                if (existing.length > 0) continue;
            }
            sp.setSpan(new ForegroundColorSpan(color), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
