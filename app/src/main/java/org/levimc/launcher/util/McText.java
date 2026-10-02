package org.levimc.launcher.util;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * v649：Minecraft 颜色代码（§）解析——资源包名字/描述里的
 * §a 等格式码渲染为真实颜色/样式（启动器与游戏内观感一致）。
 * 支持：§0-§9 §a-§g 颜色、§l 粗体、§o 斜体、§n 下划线、
 * §m 删除线、§r 重置；未知码原样忽略（§k 乱码除外）。
 */
public final class McText {

    /** MC 16 色表（§0-§f），与游戏一致。 */
    private static final int[] MC_COLORS = {
            0xFF000000, 0xFF0000AA, 0xFF00AA00, 0xFF00AAAA,
            0xFFAA0000, 0xFFAA00AA, 0xFFFFAA00, 0xFFAAAAAA,
            0xFF555555, 0xFF5555FF, 0xFF55FF55, 0xFF55FFFF,
            0xFFFF5555, 0xFFFF55FF, 0xFFFFFF55, 0xFFFFFFFF,
    };
    /** §g 铜色（minecoin gold，Bedrock 专属）。 */
    private static final int COLOR_MINECOIN = 0xFFDDD605;

    private McText() {
    }

    /** 解析 § 格式码，返回带样式区间的 SpannableString（无码原样返回）。 */
    public static SpannableString format(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new SpannableString("");
        }
        if (raw.indexOf('§') < 0) {
            return new SpannableString(raw);
        }
        StringBuilder text = new StringBuilder(raw.length());
        List<int[]> spans = new ArrayList<>(); // {start, end, kind, color}
        int color = -1;
        boolean bold = false;
        boolean italic = false;
        boolean underline = false;
        boolean strike = false;
        int i = 0;
        while (i < raw.length()) {
            char ch = raw.charAt(i);
            if (ch == '§' && i + 1 < raw.length()) {
                char code = Character.toLowerCase(raw.charAt(i + 1));
                if (code >= '0' && code <= '9') {
                    color = MC_COLORS[code - '0'];
                    i += 2;
                    continue;
                }
                if (code >= 'a' && code <= 'f') {
                    color = MC_COLORS[10 + (code - 'a')];
                    i += 2;
                    continue;
                }
                if (code == 'g') {
                    color = COLOR_MINECOIN;
                    i += 2;
                    continue;
                }
                if (code == 'l') {
                    bold = true;
                    i += 2;
                    continue;
                }
                if (code == 'o') {
                    italic = true;
                    i += 2;
                    continue;
                }
                if (code == 'n') {
                    underline = true;
                    i += 2;
                    continue;
                }
                if (code == 'm') {
                    strike = true;
                    i += 2;
                    continue;
                }
                if (code == 'r') {
                    color = -1;
                    bold = italic = underline = strike = false;
                    i += 2;
                    continue;
                }
                // 未知码（§k 等）：跳过码本身，保留后续字符
                i += 2;
                continue;
            }
            int start = text.length();
            text.append(ch);
            if (color >= 0) {
                spans.add(new int[]{start, start + 1, 0, color});
            }
            if (bold) {
                spans.add(new int[]{start, start + 1, 1, 0});
            }
            if (italic) {
                spans.add(new int[]{start, start + 1, 2, 0});
            }
            if (underline) {
                spans.add(new int[]{start, start + 1, 3, 0});
            }
            if (strike) {
                spans.add(new int[]{start, start + 1, 4, 0});
            }
            i++;
        }
        SpannableString ss = new SpannableString(text.toString());
        for (int[] sp : spans) {
            try {
                switch (sp[2]) {
                    case 0:
                        ss.setSpan(new ForegroundColorSpan(sp[3]), sp[0], sp[1],
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        break;
                    case 1:
                        ss.setSpan(new StyleSpan(Typeface.BOLD), sp[0], sp[1],
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        break;
                    case 2:
                        ss.setSpan(new StyleSpan(Typeface.ITALIC), sp[0], sp[1],
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        break;
                    case 3:
                        ss.setSpan(new UnderlineSpan(), sp[0], sp[1],
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        break;
                    case 4:
                        ss.setSpan(new StrikethroughSpan(), sp[0], sp[1],
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        break;
                    default:
                        break;
                }
            } catch (Throwable ignored) {
            }
        }
        return ss;
    }
}
