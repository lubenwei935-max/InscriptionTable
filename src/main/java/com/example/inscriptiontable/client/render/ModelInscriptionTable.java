package com.example.inscriptiontable.client.render;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.renderer.Tessellator;

/**
 * 铭刻台的自定义方块模型（用代码描述的立方体组合，1.7.10 没有 JSON 方块模型）。
 * <p>
 * 坐标单位为「像素」，1 像素 = 1/16 方块；模型以方块中心为原点、y 轴向上。
 * 纹理是一张 32x32 的 2x2 图集，四个 16x16 小图分别是：
 * <pre>
 *  0 石基    1 抛光台面
 *  2 深色木    3 发光铭文
 * </pre>
 */
public final class ModelInscriptionTable {

    private static final float PX = 1.0F / 16.0F;

    public static final int TILE_STONE = 0;
    public static final int TILE_POLISHED = 1;
    public static final int TILE_WOOD = 2;
    public static final int TILE_RUNE = 3;

    /** 台面倾角 */
    private static final float TILT = -22.0F;

    public void render(float glow) {
        Tessellator t = Tessellator.instance;

        t.startDrawingQuads();
        t.setColorOpaque_F(1.0F, 1.0F, 1.0F);

        // 石基
        box(t, 1, 0, 1, 15, 3, 15, TILE_STONE);
        // 木质束腰
        box(t, 2, 3, 2, 14, 4, 14, TILE_WOOD);
        // 中柱
        box(t, 5, 4, 5, 11, 8, 11, TILE_WOOD);
        // 背板（朝向玩家的 -Z 面是铭文面板）
        box(t, 2, 8, 13, 14, 15, 14, TILE_WOOD, TILE_WOOD, TILE_WOOD, TILE_RUNE);
        t.draw();

        // 倾斜的书写台面 + 台面上的铭文纸
        GL11.glPushMatrix();
        GL11.glTranslatef(0.5F, 0.5F, 0.46F);
        GL11.glRotatef(TILT, 1.0F, 0.0F, 0.0F);

        t.startDrawingQuads();
        t.setColorOpaque_F(1.0F, 1.0F, 1.0F);
        box(t, -7, 0, -6, 7, 1.5, 6, TILE_WOOD, TILE_POLISHED, TILE_WOOD, TILE_POLISHED);
        // 发光的铭文纸，亮度做一点呼吸
        float g = Math.max(0.6F, Math.min(1.0F, glow));
        boxGlow(t, -4, 1.5, -3.5, 4, 2.0, 3.5, TILE_POLISHED, TILE_RUNE, g);
        t.draw();

        GL11.glPopMatrix();
    }

    private static void box(Tessellator t, double x0, double y0, double z0,
                            double x1, double y1, double z1, int tile) {
        box(t, x0, y0, z0, x1, y1, z1, tile, tile, tile, tile);
    }

    private static void box(Tessellator t, double x0, double y0, double z0,
                            double x1, double y1, double z1,
                            int side, int top, int bottom, int front) {
        double ax = x0 * PX, ay = y0 * PX, az = z0 * PX;
        double bx = x1 * PX, by = y1 * PX, bz = z1 * PX;

        // 上
        quad(t, top, 1.00F, ax, by, az, ax, by, bz, bx, by, bz, bx, by, az);
        // 下
        quad(t, bottom, 0.50F, ax, ay, az, bx, ay, az, bx, ay, bz, ax, ay, bz);
        // 北（-z，朝向玩家的一侧）
        quad(t, front, 0.88F, bx, ay, az, ax, ay, az, ax, by, az, bx, by, az);
        // 南（+z）
        quad(t, side, 0.80F, ax, ay, bz, bx, ay, bz, bx, by, bz, ax, by, bz);
        // 西（-x）
        quad(t, side, 0.62F, ax, ay, az, ax, ay, bz, ax, by, bz, ax, by, az);
        // 东（+x）
        quad(t, side, 0.68F, bx, ay, bz, bx, ay, az, bx, by, az, bx, by, bz);
    }

    /** 只让顶面（铭文面）带呼吸亮度 */
    private static void boxGlow(Tessellator t, double x0, double y0, double z0,
                                double x1, double y1, double z1, int side, int top, float glow) {
        double ax = x0 * PX, ay = y0 * PX, az = z0 * PX;
        double bx = x1 * PX, by = y1 * PX, bz = z1 * PX;

        quad(t, top, glow, ax, by, az, ax, by, bz, bx, by, bz, bx, by, az);
        quad(t, side, 0.90F, ax, ay, az, bx, ay, az, bx, ay, bz, ax, ay, bz);
        quad(t, side, 0.90F, bx, ay, az, ax, ay, az, ax, by, az, bx, by, az);
        quad(t, side, 0.85F, ax, ay, bz, bx, ay, bz, bx, by, bz, ax, by, bz);
        quad(t, side, 0.70F, ax, ay, az, ax, ay, bz, ax, by, bz, ax, by, az);
        quad(t, side, 0.75F, bx, ay, bz, bx, ay, az, bx, by, az, bx, by, bz);
    }

    private static void quad(Tessellator t, int tile, float brightness,
                             double ax, double ay, double az,
                             double bx, double by, double bz,
                             double cx, double cy, double cz,
                             double dx, double dy, double dz) {
        float u0 = (tile & 1) * 0.5F;
        float v0 = (tile >> 1) * 0.5F;
        float u1 = u0 + 0.5F;
        float v1 = v0 + 0.5F;

        t.setColorOpaque_F(brightness, brightness, brightness);
        t.addVertexWithUV(ax, ay, az, u0, v1);
        t.addVertexWithUV(bx, by, bz, u0, v0);
        t.addVertexWithUV(cx, cy, cz, u1, v0);
        t.addVertexWithUV(dx, dy, dz, u1, v1);
    }
}
