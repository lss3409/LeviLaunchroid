package org.levimc.launcher.core.online;

import java.util.Locale;

/**
 * PaperConnect / Scaffolding 联机邀请码编解码（v492）。
 *
 * 格式：P/NNNN-NNNN-SSSS-SSSS（P/ 与 U/ 前缀均接受，不区分大小写）。
 * 字符集 34 位（去 I、O）：0123456789ABCDEFGHJKLMNPQRSTUVWXYZ。
 * 校验：16 位字符按小端序读作 34 进制整数，须能被 7 整除。
 * 解码：网络名 = paper-connect-NNNN-NNNN，网络密钥 = SSSS-SSSS。
 * 参考：Florolding（Scaffolding 协议实现）+ PaperConnect 协议文档。
 */
public final class InviteCode {

    public static final String CHARSET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    /** 解析失败原因。 */
    public enum Error {
        NONE,
        /** 前缀/分组格式不对（应为 X/XXXX-XXXX-XXXX-XXXX） */
        FORMAT,
        /** 含字符集外字符（如 I、O） */
        CHARSET,
        /** 模 7 校验失败 */
        CHECKSUM
    }

    /** 解析结果。 */
    public static final class Parsed {
        /** paper-connect-NNNN-NNNN（EasyTier 网络名） */
        public final String networkName;
        /** SSSS-SSSS（EasyTier 网络密钥） */
        public final String networkSecret;

        Parsed(String networkName, String networkSecret) {
            this.networkName = networkName;
            this.networkSecret = networkSecret;
        }
    }

    public static final class Result {
        public final Error error;
        public final Parsed parsed;

        Result(Error error, Parsed parsed) {
            this.error = error;
            this.parsed = parsed;
        }

        public boolean ok() {
            return error == Error.NONE && parsed != null;
        }
    }

    private InviteCode() {
    }

    /** 解析并校验邀请码。 */
    public static Result parse(String code) {
        if (code == null) {
            return new Result(Error.FORMAT, null);
        }
        String c = code.trim().toUpperCase(Locale.ROOT);
        if (c.length() >= 2 && (c.startsWith("P/") || c.startsWith("U/"))) {
            c = c.substring(2);
        }
        String[] parts = c.split("-");
        if (parts.length != 4) {
            return new Result(Error.FORMAT, null);
        }
        StringBuilder sb = new StringBuilder(16);
        for (String p : parts) {
            if (p.length() != 4) {
                return new Result(Error.FORMAT, null);
            }
            sb.append(p);
        }
        String raw = sb.toString();
        // 小端序 34 进制整数模 7 校验（增量计算，无需大整数）：
        // total = Σ v_i · 34^i，34 mod 7 = 6
        long total = 0;
        long pow = 1;
        for (int i = 0; i < 16; i++) {
            int v = CHARSET.indexOf(raw.charAt(i));
            if (v < 0) {
                return new Result(Error.CHARSET, null);
            }
            total = (total + v * pow) % 7;
            pow = (pow * 34) % 7;
        }
        if (total != 0) {
            return new Result(Error.CHECKSUM, null);
        }
        return new Result(Error.NONE, new Parsed(
                "paper-connect-" + raw.substring(0, 4) + "-" + raw.substring(4, 8),
                raw.substring(8, 12) + "-" + raw.substring(12, 16)));
    }

    /** 输入自动格式化：去非法字符、转大写、补 P/ 前缀与短横线。 */
    public static String formatInput(String input) {
        if (input == null) {
            return "P/";
        }
        StringBuilder s = new StringBuilder();
        for (char ch : input.toUpperCase(Locale.ROOT).toCharArray()) {
            if (CHARSET.indexOf(ch) >= 0) {
                s.append(ch);
            }
        }
        if (s.length() > 16) {
            s.setLength(16);
        }
        StringBuilder out = new StringBuilder("P/");
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                out.append('-');
            }
            out.append(s.charAt(i));
        }
        return out.toString();
    }
}
