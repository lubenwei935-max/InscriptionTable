package com.example.inscriptiontable.util;

/**
 * 一段同色同格式的文字。
 */
public final class LoreSegment {

    public final String text;
    /** 0xRRGGBB */
    public final int color;
    public final boolean bold;
    public final boolean italic;
    public final boolean underline;

    public LoreSegment(String text, int color, boolean bold, boolean italic, boolean underline) {
        this.text = text;
        this.color = color & 0xFFFFFF;
        this.bold = bold;
        this.italic = italic;
        this.underline = underline;
    }

    /** § 格式前缀（不含颜色） */
    public String stylePrefix() {
        if (!bold && !italic && !underline) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (bold) {
            sb.append('\u00A7').append('l');
        }
        if (italic) {
            sb.append('\u00A7').append('o');
        }
        if (underline) {
            sb.append('\u00A7').append('n');
        }
        return sb.toString();
    }

    /** 加上 § 格式前缀后的文本，交给 FontRenderer 直接绘制 */
    public String styled() {
        String prefix = stylePrefix();
        return prefix.length() == 0 ? text : prefix + text;
    }
}
