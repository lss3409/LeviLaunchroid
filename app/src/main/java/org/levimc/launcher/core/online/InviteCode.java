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

    /** 生成的房间（v497）：邀请码 + 解析结果。 */
    public static final class Generated {
        /** 邀请码（4-4-4-4 分组，不带 P/ 前缀）。 */
        public final String code;
        /** 网络名/密钥。 */
        public final Parsed parsed;

        Generated(String code, Parsed parsed) {
            this.code = code;
            this.parsed = parsed;
        }
    }

    /**
     * 生成新房间邀请码（v497）：随机 15 位 + 推导校验位。
     * 校验：Σ v_i·34^i ≡ 0 (mod 7)。第 16 位权值 34^15 ≡ 6 (mod 7)，
     * 6v ≡ −total → v = (7 − 6·total mod 7) mod 7（v<7 必在字符集内）。
     */
    public static Generated generate() {
        java.security.SecureRandom r = new java.security.SecureRandom();
        char[] cs = new char[16];
        long total = 0;
        long pow = 1;
        for (int i = 0; i < 15; i++) {
            int v = r.nextInt(CHARSET.length());
            cs[i] = CHARSET.charAt(v);
            total = (total + v * pow) % 7;
            pow = (pow * 34) % 7;
        }
        int v = (int) ((7 - (6 * total) % 7) % 7);
        cs[15] = CHARSET.charAt(v);
        String raw = new String(cs);
        String code = raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-"
                + raw.substring(8, 12) + "-" + raw.substring(12, 16);
        // 用 parse 自校验（防御生成逻辑回归）
        Result check = parse("P/" + code);
        if (!check.ok()) {
            throw new IllegalStateException("邀请码生成自校验失败: " + code);
        }
        return new Generated(code, check.parsed);
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

    /**
     * 输入自动格式化：去非法字符、转大写、剥前置 P//U/ 前缀、补短横线。
     * 注意：P/ 前缀由布局层 TextView 渲染，不属于文本（v494）——
     * 文本里只放 16 位码本体，避免手输前缀时 P 混入数据位造成码移位。
     */
    public static String formatInput(String input) {
        if (input == null) {
            return "";
        }
        // 粘贴带前缀的完整码时先剥掉前缀，避免 P 混入数据位
        String upper = input.toUpperCase(Locale.ROOT);
        if (upper.length() >= 2 && (upper.startsWith("P/") || upper.startsWith("U/"))) {
            upper = upper.substring(2);
        }
        StringBuilder s = new StringBuilder();
        for (char ch : upper.toCharArray()) {
            if (CHARSET.indexOf(ch) >= 0) {
                s.append(ch);
            }
        }
        if (s.length() > 16) {
            s.setLength(16);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                out.append('-');
            }
            out.append(s.charAt(i));
        }
        return out.toString();
    }
}
