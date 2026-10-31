package com.example.inscriptiontable.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 铭文颜色代码解析器。
 * <p>
 * 支持的写法：
 * <ul>
 * <li>{@code &0} ~ {@code &f}：原版 16 色</li>
 * <li>{@code &#RRGGBB} 或 {@code #RRGGBB}：任意 RGB 颜色</li>
 * <li>{@code &l} 粗体、{@code &o} 斜体、{@code &n} 下划线、{@code &r} 复位</li>
 * <li>{@code \n}：换行（GUI 里输入框是单行，用这两个字符换行）</li>
 * <li>{@code &&} 与 {@code \\}：转义</li>
 * <li>原版的 {@code §} 与 {@code &} 等价</li>
 * </ul>
 * 本类不依赖客户端类，服务端也可以调用（用于校验/转换）。
 */
public final class LoreParser {

    public static final int DEFAULT_COLOR = 0xFFFFFF;

    private static final String LEGACY_CODES = "0123456789abcdef";
    /** 16 个原版颜色，编辑器色板与代码解析共用同一份数据 */
    public static final int[] LEGACY_COLORS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
            0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
            0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    private LoreParser() {
    }

    private static final class State {
        int color = DEFAULT_COLOR;
        boolean bold;
        boolean italic;
        boolean underline;

        State copy() {
            State s = new State();
            s.color = color;
            s.bold = bold;
            s.italic = italic;
            s.underline = underline;
            return s;
        }
    }

    public static List<LoreLine> parse(String raw) {
        List<LoreLine> lines = new ArrayList<LoreLine>();
        if (raw == null || raw.length() == 0) {
            return lines;
        }

        State state = new State();
        State before = new State();
        LoreLine line = new LoreLine();
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);

            // \n 换行 / \\ 转义
            if (c == '\\' && i + 1 < raw.length()) {
                char n = raw.charAt(i + 1);
                if (n == 'n' || n == 'N') {
                    flush(text, before, line);
                    lines.add(line);
                    line = new LoreLine();
                    i += 2;
                    continue;
                }
                if (n == '\\') {
                    text.append('\\');
                    i += 2;
                    continue;
                }
            }

            // 真换行（编辑器的 Enter 会写入 '\n'）
            if (c == '\n') {
                flush(text, state, line);
                lines.add(line);
                line = new LoreLine();
                i++;
                continue;
            }

            // 颜色 / 格式代码
            before = state.copy();
            int next = tryCode(raw, i, state);
            if (next > i) {
                flush(text, before, line);
                i = next;
                continue;
            }

            text.append(c);
            i++;
        }

        if (text.length() > 0 || !line.isEmpty() || lines.isEmpty()) {
            flush(text, state, line);
            lines.add(line);
        }
        return lines;
    }

    private static void flush(StringBuilder text, State state, LoreLine line) {
        if (text.length() > 0) {
            line.add(new LoreSegment(text.toString(), state.color, state.bold, state.italic, state.underline));
            text.setLength(0);
        }
    }

    /**
     * 尝试在 index 处解析一个代码，成功返回下一个位置，失败返回 index。
     */
    private static int tryCode(String raw, int index, State state) {
        char c = raw.charAt(index);
        if (c != '&' && c != '\u00A7') {
            return index;
        }
        if (index + 1 >= raw.length()) {
            return index;
        }
        char n = Character.toLowerCase(raw.charAt(index + 1));

        if (n == '#') {
            if (index + 7 < raw.length()) {
                int rgb = parseHex(raw, index + 2);
                if (rgb >= 0) {
                    state.color = rgb;
                    return index + 8;
                }
            }
            return index;
        }

        int idx = LEGACY_CODES.indexOf(n);
        if (idx >= 0) {
            state.color = LEGACY_COLORS[idx];
            return index + 2;
        }

        switch (n) {
            case 'l':
                state.bold = true;
                return index + 2;
            case 'o':
                state.italic = true;
                return index + 2;
            case 'n':
                state.underline = true;
                return index + 2;
            case 'm':
            case 'k':
                // 删除线/乱码字符在自定义渲染里没有意义，直接忽略
                return index + 2;
            case 'r':
                state.color = DEFAULT_COLOR;
                state.bold = false;
                state.italic = false;
                state.underline = false;
                return index + 2;
            default:
                return index;
        }
    }

    /** 解析 6 位十六进制，失败返回 -1 */
    private static int parseHex(String raw, int from) {
        if (from + 6 > raw.length()) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < 6; i++) {
            int d = Character.digit(raw.charAt(from + i), 16);
            if (d < 0) {
                return -1;
            }
            value = (value << 4) | d;
        }
        return value;
    }

    /** 该字符是否是 16 色之一的代码（0-9a-f） */
    public static boolean isLegacyColorCode(char code) {
        return LEGACY_CODES.indexOf(Character.toLowerCase(code)) >= 0;
    }

    /** 该字符是否是格式代码（l/o/n/m/k/r） */
    public static boolean isFormatCode(char code) {
        char c = Character.toLowerCase(code);
        return c == 'l' || c == 'o' || c == 'n' || c == 'm' || c == 'k' || c == 'r';
    }

    /** 代码 '0'~'f' 对应的颜色，非颜色代码返回 -1 */
    public static int legacyColor(char code) {
        int index = LEGACY_CODES.indexOf(Character.toLowerCase(code));
        return index < 0 ? -1 : LEGACY_COLORS[index];
    }

    /**
     * index 处若是一个合法的 {@code &}/{@code §} 代码，返回它占用的字符数（2 或 8）；否则返回 0。
     * 编辑器排版、光标移动与颜色渲染都以它为准。
     */
    public static int codeLength(String raw, int index) {
        if (raw == null || index < 0 || index + 1 >= raw.length()) {
            return 0;
        }
        char c = raw.charAt(index);
        if (c != '&' && c != '\u00A7') {
            return 0;
        }
        char next = Character.toLowerCase(raw.charAt(index + 1));
        if (next == '#') {
            if (index + 7 >= raw.length()) {
                return 0;
            }
            for (int i = 2; i < 8; i++) {
                if (Character.digit(raw.charAt(index + i), 16) < 0) {
                    return 0;
                }
            }
            return 8;
        }
        if (isLegacyColorCode(next) || isFormatCode(next)) {
            return 2;
        }
        return 0;
    }

    /** 把一段解析结果转回 § 代码字符串（用于无法做 RGB 覆盖绘制的场合） */
    public static String toLegacy(LoreLine line) {
        StringBuilder sb = new StringBuilder();
        for (LoreSegment segment : line.segments) {
            sb.append('\u00A7').append('r').append('\u00A7').append(nearestCode(segment.color));
            if (segment.bold) {
                sb.append('\u00A7').append('l');
            }
            if (segment.italic) {
                sb.append('\u00A7').append('o');
            }
            if (segment.underline) {
                sb.append('\u00A7').append('n');
            }
            sb.append(segment.text);
        }
        return sb.toString();
    }

    /** 找最接近的原版颜色代码 */
    public static char nearestCode(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < LEGACY_COLORS.length; i++) {
            int cr = (LEGACY_COLORS[i] >> 16) & 0xFF;
            int cg = (LEGACY_COLORS[i] >> 8) & 0xFF;
            int cb = LEGACY_COLORS[i] & 0xFF;
            int distance = (r - cr) * (r - cr) + (g - cg) * (g - cg) + (b - cb) * (b - cb);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return LEGACY_CODES.charAt(best);
    }
}
