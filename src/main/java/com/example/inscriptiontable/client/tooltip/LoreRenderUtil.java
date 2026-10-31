package com.example.inscriptiontable.client.tooltip;

import com.example.inscriptiontable.util.LoreLine;
import com.example.inscriptiontable.util.LoreSegment;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.FontRenderer;

/**
 * 用「每段一种颜色」的方式绘制铭文，因此可以显示任意 RGB 颜色
 * （原版 § 代码只有 16 种颜色）。
 */
@SideOnly(Side.CLIENT)
public final class LoreRenderUtil {

    private LoreRenderUtil() {
    }

    public static int lineWidth(FontRenderer font, LoreLine line) {
        int width = 0;
        for (LoreSegment segment : line.segments) {
            width += font.getStringWidth(segment.styled());
        }
        return width;
    }

    public static int drawLine(FontRenderer font, LoreLine line, int x, int y, boolean shadow) {
        return drawLine(font, line, x, y, shadow, Integer.MAX_VALUE);
    }

    /**
     * @param maxX 右边界（不含），超出的部分按字符裁剪，避免文字糊出框外
     * @return 绘制结束后的 x 坐标
     */
    public static int drawLine(FontRenderer font, LoreLine line, int x, int y, boolean shadow, int maxX) {
        int cursor = x;
        for (LoreSegment segment : line.segments) {
            int argb = 0xFF000000 | segment.color;
            String text = segment.styled();
            int width = font.getStringWidth(text);

            if (cursor + width <= maxX) {
                draw(shadow, font, text, cursor, y, argb);
                cursor += width;
                continue;
            }

            // 放不下整段：逐字符画，直到到边界
            String prefix = segment.stylePrefix();
            for (int i = 0; i < segment.text.length(); i++) {
                String ch = prefix + segment.text.charAt(i);
                int chWidth = font.getStringWidth(ch);
                if (cursor + chWidth > maxX) {
                    return cursor;
                }
                draw(shadow, font, ch, cursor, y, argb);
                cursor += chWidth;
            }
        }
        return cursor;
    }

    private static void draw(boolean shadow, FontRenderer font, String text, int x, int y, int argb) {
        if (shadow) {
            font.drawStringWithShadow(text, x, y, argb);
        } else {
            font.drawString(text, x, y, argb);
        }
    }
}
