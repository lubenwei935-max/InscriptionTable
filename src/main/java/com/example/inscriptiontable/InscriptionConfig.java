package com.example.inscriptiontable;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/**
 * 模组配置。
 */
public final class InscriptionConfig {

    /** 铭文最大字符数（含颜色代码），服务端同样以此值截断，防止刷包 */
    public static int maxLoreLength = 512;

    /**
     * 铭文最大行数。
     * <p>
     * 1.7.10 的 tooltip 没有滚动条，行数完全由内容决定；一长串换行会把 tooltip
     * 撑满整个屏幕（还会画出一个同样高的深色背景框）。这里给一个上限，服务端同样以此值截断。
     */
    public static int maxLoreLines = 12;

    /**
     * 是否使用「占位行 + RGB 覆盖绘制」的方式渲染 tooltip。
     * <p>
     * 1.7.10 原版 tooltip 只能识别 § 的 16 种颜色，无法表达任意 RGB，
     * 因此开启时由本模组在 tooltip 文字位置上重新绘制真正的 RGB 文本。
     * 若与其他修改 tooltip 的模组冲突，可关闭此项，退化为「RGB 取最近的原版颜色」。
     */
    public static boolean rgbTooltip = true;

    private InscriptionConfig() {
    }

    public static void load(File file) {
        Configuration cfg = new Configuration(file);
        try {
            cfg.load();
            maxLoreLength = cfg.getInt("maxLoreLength", "general", 512, 1, 2048,
                    "铭文最大字符数（含颜色代码）");
            maxLoreLines = cfg.getInt("maxLoreLines", "general", 12, 1, 64,
                    "铭文最大行数（防止 tooltip 撑满屏幕）");
            rgbTooltip = cfg.getBoolean("rgbTooltip", "general", true,
                    "使用 RGB 覆盖绘制 tooltip（关闭后 tooltip 使用最接近的原版颜色）");
        } catch (Exception e) {
            System.err.println("[InscriptionTable] 读取配置失败: " + e.getMessage());
        } finally {
            if (cfg.hasChanged()) {
                cfg.save();
            }
        }
    }
}
