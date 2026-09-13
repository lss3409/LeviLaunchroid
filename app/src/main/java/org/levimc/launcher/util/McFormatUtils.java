package org.levimc.launcher.util;

import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

/**
 * Minecraft 格式化代码解析：把 §x 颜色/样式代码渲染成带颜色的文本。
 * 支持 §0-§f 颜色、§r 重置；样式代码（加粗/斜体等）暂按忽略处理。
 */
public final class McFormatUtils {

    private McFormatUtils() {}

    public static CharSequence format(String text) {
        if (text == null || text.isEmpty()) return "";
        if (!text.contains("§") && !text.contains("&")) {
            return text;
        }
        return parseMcColors(text);
    }

    private static CharSequence parseMcColors(String input) {
        StringBuilder sb = new StringBuilder();
        java.util.List<int[]> ranges = new java.util.ArrayList<>(); // {start, end, color}

        int currentColor = Color.WHITE;
        int currentStart = 0;
        boolean open = false;

        int i = 0;
        int len = input.length();
        while (i < len) {
            char c = input.charAt(i);
            if (c == '§' || c == '&') {
                if (i + 1 < len) {
                    char code = input.charAt(i + 1);
                    int color = resolveColor(code);
                    if (color != Integer.MIN_VALUE) {
                        // 关闭当前 span
                        if (open && sb.length() > currentStart) {
                            ranges.add(new int[]{currentStart, sb.length(), currentColor});
                        }
                        currentColor = color;
                        currentStart = sb.length();
                        open = true;
                        i += 2;
                        continue;
                    } else if (code == 'r' || code == 'R') {
                        if (open && sb.length() > currentStart) {
                            ranges.add(new int[]{currentStart, sb.length(), currentColor});
                        }
                        open = false;
                        currentColor = Color.WHITE;
                        i += 2;
                        continue;
                    }
                }
                // 无法解析的 §，当作普通字符跳过（不显示 §）
                i += 1;
                continue;
            }
            sb.append(c);
            i++;
        }
        if (open && sb.length() > currentStart) {
            ranges.add(new int[]{currentStart, sb.length(), currentColor});
        }

        SpannableString spannable = new SpannableString(sb.toString());
        for (int[] range : ranges) {
            if (range[1] > range[0]) {
                spannable.setSpan(new ForegroundColorSpan(range[2]), range[0], range[1],
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return spannable;
    }

    private static int resolveColor(char code) {
        switch (code) {
            case '0': return Color.BLACK;
            case '1': return Color.rgb(0, 0, 170);       // 深蓝
            case '2': return Color.rgb(0, 170, 0);       // 深绿
            case '3': return Color.rgb(0, 170, 170);     // 深青
            case '4': return Color.rgb(170, 0, 0);       // 深红
            case '5': return Color.rgb(170, 0, 170);     // 深紫
            case '6': return Color.rgb(255, 170, 0);     // 金
            case '7': return Color.rgb(170, 170, 170);   // 浅灰
            case '8': return Color.rgb(85, 85, 85);      // 深灰
            case '9': return Color.rgb(85, 85, 255);     // 蓝
            case 'a': return Color.rgb(85, 255, 85);     // 绿
            case 'b': return Color.rgb(85, 255, 255);    // 青
            case 'c': return Color.rgb(255, 85, 85);     // 红
            case 'd': return Color.rgb(255, 85, 255);    // 粉
            case 'e': return Color.rgb(255, 255, 85);    // 黄
            case 'f': return Color.WHITE;                // 白
            default: return Integer.MIN_VALUE;
        }
    }
}
