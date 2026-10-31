package com.example.inscriptiontable.client.tooltip;

import java.util.List;

import com.example.inscriptiontable.InscriptionConfig;
import com.example.inscriptiontable.util.LoreData;
import com.example.inscriptiontable.util.LoreLine;
import com.example.inscriptiontable.util.LoreParser;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent.RenderTickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;

/**
 * 把铭文写进物品 tooltip，并且能做到「真正的 RGB 颜色」。
 *
 * <h3>为什么要自己画一遍</h3>
 * 1.7.10 原版 tooltip 是一组 {@code List<String>}，最终由
 * {@code GuiScreen.drawHoveringText} 交给 FontRenderer 绘制，而 FontRenderer 只认识
 * {@code §0}~{@code §f} 这 16 种颜色，没有任意 RGB 的能力，也没有 RenderTooltipEvent 可以拦截。
 *
 * <h3>做法</h3>
 * 1. 在 {@link ItemTooltipEvent} 里，把每行铭文替换成一个「等宽的空格占位行」，
 *    这样原版 tooltip 依然会为它留出正确的行高与文本框宽度，但看不到任何文字；
 * 2. 记录下占位行在 tooltip 里的起始下标与内容；
 * 3. 在 {@link RenderTickEvent.Phase#END}（FML 总线，此时整帧的 GUI 与 tooltip 都已画完）里，
 *    按照与原版完全一致的定位算法，在占位行所在的位置用真实 RGB 颜色把文字补画上去。
 * <p>
 * 关闭 {@link InscriptionConfig#rgbTooltip} 时会退化为「取最近的原版颜色」，
 * 用于与其它修改 tooltip 的模组冲突时兜底。
 */
@SideOnly(Side.CLIENT)
public class LoreTooltipHandler {

    private static List<LoreLine> pendingLines;
    private static int pendingBoxWidth;
    private static int pendingFirstIndex;
    private static int pendingLineCount;

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.itemStack;
        if (stack == null) {
            return;
        }
        String raw = LoreData.getLore(stack);
        if (raw.length() == 0) {
            return;
        }
        List<LoreLine> lines = LoreParser.parse(raw);
        if (lines.isEmpty()) {
            return;
        }
        // 老数据可能是没有行数限制时存下的，这里再兜一次底，避免 tooltip 撑满屏幕
        int maxLines = Math.max(1, InscriptionConfig.maxLoreLines);
        if (lines.size() > maxLines) {
            lines = lines.subList(0, maxLines);
        }

        if (!InscriptionConfig.rgbTooltip) {
            for (LoreLine line : lines) {
                event.toolTip.add(LoreParser.toLegacy(line));
            }
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRenderer;
        List<String> tooltip = event.toolTip;

        int boxWidth = 0;
        for (String existing : tooltip) {
            if (existing != null) {
                boxWidth = Math.max(boxWidth, font.getStringWidth(existing));
            }
        }

        int firstIndex = tooltip.size();
        for (LoreLine line : lines) {
            int width = LoreRenderUtil.lineWidth(font, line);
            boxWidth = Math.max(boxWidth, width);
            tooltip.add(spaces(font, width));
        }

        pendingLines = lines;
        pendingBoxWidth = boxWidth;
        pendingFirstIndex = firstIndex;
        pendingLineCount = tooltip.size();
    }

    @SubscribeEvent
    public void onRenderTick(RenderTickEvent event) {
        if (event.phase != RenderTickEvent.Phase.END) {
            return;
        }
        List<LoreLine> lines = pendingLines;
        pendingLines = null;
        if (lines != null) {
            drawRealColor(lines, pendingBoxWidth, pendingFirstIndex, pendingLineCount);
        }
    }

    private static void drawRealColor(List<LoreLine> lines, int boxWidth, int firstIndex, int lineCount) {
        Minecraft mc = Minecraft.getMinecraft();
        GuiScreen screen = mc.currentScreen;
        if (screen == null || mc.displayWidth <= 0 || mc.displayHeight <= 0) {
            return;
        }

        int mouseX = Mouse.getX() * screen.width / mc.displayWidth;
        int mouseY = screen.height - Mouse.getY() * screen.height / mc.displayHeight - 1;

        // 与原版 GuiScreen.drawHoveringText 完全一致的定位
        int boxHeight = 8;
        if (lineCount > 1) {
            boxHeight += 2 + (lineCount - 1) * 10;
        }
        int x = mouseX + 12;
        int y = mouseY - 12;
        if (x + boxWidth > screen.width) {
            x -= 28 + boxWidth;
        }
        if (y + boxHeight + 6 > screen.height) {
            y = screen.height - boxHeight - 6;
        }

        FontRenderer font = mc.fontRenderer;

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

        for (int i = 0; i < lines.size(); i++) {
            int index = firstIndex + i;
            int lineY = y + (index == 0 ? 0 : 2 + index * 10);
            LoreRenderUtil.drawLine(font, lines.get(i), x, lineY, true);
        }

        GL11.glPopAttrib();
        GL11.glPopMatrix();
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** 生成与目标像素宽度最接近的一串空格，用来占位 */
    private static String spaces(FontRenderer font, int targetWidth) {
        if (targetWidth <= 0) {
            return "";
        }
        int unit = Math.max(1, font.getStringWidth(" "));
        int count = (targetWidth + unit - 1) / unit;
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
