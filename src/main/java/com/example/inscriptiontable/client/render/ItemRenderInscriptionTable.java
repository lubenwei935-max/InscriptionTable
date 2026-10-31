package com.example.inscriptiontable.client.render;

import org.lwjgl.opengl.GL11;

import com.example.inscriptiontable.InscriptionTableMod;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.IItemRenderer;

/**
 * 物品形态的铭刻台：背包图标、掉落物、手持（第一 / 第三人称）全部用 3D 模型渲染。
 * <p>
 * 1.7.10 没有 JSON 模型，方块物品默认是 16x16 的平面图标；这里注册 {@link IItemRenderer}，
 * 接管 {@code INVENTORY / ENTITY / EQUIPPED / EQUIPPED_FIRST_PERSON} 四种形态。
 * 每种形态的相机都由 Forge 或原版调用方摆好，本类只负责「在给定空间里把方块摆正」。
 * <p>
 * 四个坐标空间（对着字节码核对过 {@code ForgeHooksClient / RenderItem / RenderBiped}）：
 * <table border="1">
 * <tr><th>形态</th><th>调用方给的变换</th><th>模型该画在哪</th></tr>
 * <tr><td>{@code INVENTORY}</td>
 * <td>平移 (x-2,y+3,z-3)、缩放 10、z 翻转、绕 X 210°、绕 Y 45°-90°</td>
 * <td>以方块中心为原点，1 格 = 1.0</td></tr>
 * <tr><td>{@code ENTITY}</td>
 * <td>只平移到实体位置；按需绕 Y 自转、再整体缩放 0.5</td>
 * <td>方块角点原点、y 向上（与原版掉落方块一致）</td></tr>
 * <tr><td>{@code EQUIPPED}</td>
 * <td>手臂变换 + 手持方块的 {@code translate(0.1875,-0.3125) · rotate(20,X) · rotate(45,Y)}
 * · {@code scale(-0.375,-0.375,0.375)}</td>
 * <td>方块角点原点（与原版 {@code renderBlockAsItem} 一致）</td></tr>
 * <tr><td>{@code EQUIPPED_FIRST_PERSON}</td>
 * <td>第一人称手部变换（{@code translate(0.56,-0.52,-0.72) · rotate(45,Y) · scale(0.4)}
 * + 摆动/使用动画）</td>
 * <td>方块角点原点</td></tr>
 * </table>
 * <p>
 * 朝向：背包沿用 Forge 相机的斜视角（与普通方块图标一致，模型自带 180° 让外斜面朝观察者）；
 * 手持两种形态都完整复刻「原版手里的方块」那套变换（见 {@link #YAW_HELD}），
 * 铭文面板与倾斜书写台因此朝向玩家（第一人称）/ 正前方（第三人称），
 * 模型中心也正好落在原版方块的位置上，不会偏右；再沿手上方向抬 {@link #HELD_RAISE}，
 * 免得整格高的台子被屏幕下沿切掉一半。
 * <p>
 * 注意 {@code EQUIPPED_BLOCK} 助手：Forge 只是在调用前 {@code glTranslatef(-0.5,-0.5,-0.5)}，
 * 它并不能替代原版 {@code renderBlockAsItem} 里那句 {@code rotate(90,Y) · translate(-0.5)}；
 * 少掉那个 90° 会让模型相对原版方块整体歪 90° 并偏移半个方块，看起来就是「拿反了 / 拿歪了」。
 */
@SideOnly(Side.CLIENT)
public class ItemRenderInscriptionTable implements IItemRenderer {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(InscriptionTableMod.MODID, "textures/blocks/inscription_table.png");

    /**
     * 背包 / 快捷栏：Forge 的 INVENTORY_BLOCK 相机已经把方块摆成 210°/45°，模型再绕 Y 转 180°，
     * 让 -Z 面的铭文板朝向观察者。
     */
    private static final float YAW_INVENTORY = 180.0F;

    /**
     * 手持：原版 {@code RenderBlocks.renderBlockAsItem} 画方块物品时会自带
     * {@code rotate(90,Y) · translate(-0.5)}（先绕方块中心轴转 90°，再平移到中心原点），
     * 手持方块的「正面朝向玩家 / 正前方」就是这句 90° 带来的。
     * <p>
     * Forge 的 {@code EQUIPPED_BLOCK} 只替自定义渲染器补了那句 {@code translate(-0.5)}，
     * <b>没有</b>补 90° 旋转；缺了它，模型会相对原版方块少转 90°，
     * 又因为模型是按角点原点（0..1）写的，整体还会偏右、偏上半个方块 ——
     * 表现就是铭文板/书写台朝向别扭、方块像是飘在手上。
     * 这里把 90° 补回来，两种手持形态的姿态与落点就和手里拿一个原版方块完全一致。
     */
    private static final float YAW_HELD = 90.0F;

    /** 抵消 Forge {@code EQUIPPED_BLOCK} 的 -0.5，把坐标空间还原成原版方块角点原点。 */
    private static final float EQUIPPED_OFFSET = 0.5F;

    /**
     * 手持时的额外抬高量，单位是「方块角点原点空间」里的方块单位（0.5 = 半格），
     * 会被外层的手持缩放等比缩小：第一人称 0.4 倍、第三人称 0.375 倍，
     * 所以 0.3 大约等于把物品往上托 0.12 格 —— 视觉上约为屏高的 12%。
     * <p>
     * 铭刻台是一块「整格高 + 有背板」的方块，按原版方块的持握位置平端在手里时，
     * 顶面离视线偏远、下半截又都被屏幕下沿切掉；这里沿手持局部 +Y（两种视角都是世界向上）抬一点，
     * 让铭文面板和书写台进入画面。想更高就调大，想还原原版方块位置就改成 0。
     */
    private static final float HELD_RAISE = 0.3F;

    private final ModelInscriptionTable model = new ModelInscriptionTable();

    @Override
    public boolean handleRenderType(ItemStack item, ItemRenderType type) {
        return type == ItemRenderType.INVENTORY
                || type == ItemRenderType.ENTITY
                || type == ItemRenderType.EQUIPPED
                || type == ItemRenderType.EQUIPPED_FIRST_PERSON;
    }

    @Override
    public boolean shouldUseRenderHelper(ItemRenderType type, ItemStack item, ItemRendererHelper helper) {
        switch (type) {
            case INVENTORY:
                return helper == ItemRendererHelper.INVENTORY_BLOCK;

            case ENTITY:
                // 自转用原版掉落方块的角度，bobbing 保留调用方已经加上的上下浮动。
                // BLOCK_3D 必须为 false：getRenderType() == -1 走不到 3D 分支，
                // 拿到的正是「0.5 缩放 + 单次绘制」，不会按堆叠数复制小方块。
                return helper == ItemRendererHelper.ENTITY_ROTATION
                        || helper == ItemRendererHelper.ENTITY_BOBBING;

            case EQUIPPED:
            case EQUIPPED_FIRST_PERSON:
                // EQUIPPED_BLOCK：拿到 Forge 的方块分支（而不是 2D 精灵分支）。
                // BLOCK_3D：让 RenderBiped 套用手持方块的 0.625 缩放与 90° 旋转，
                // 否则物品会按平面图标的手部空间绘制，视觉上大一圈。
                return helper == ItemRendererHelper.EQUIPPED_BLOCK
                        || helper == ItemRendererHelper.BLOCK_3D;

            default:
                return false;
        }
    }

    @Override
    public void renderItem(ItemRenderType type, ItemStack item, Object... data) {
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);

        // 世界里的实体渲染默认开着光照，而模型是手写顶点、不带法线，
        // 沿用同一套路会把面照亮成随机亮度；统一关掉，让逐面明暗与背包里完全一致。
        GL11.glDisable(GL11.GL_LIGHTING);
        // 模型绕序不统一，关掉背面剔除免得丢面。
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

        Minecraft.getMinecraft().getTextureManager().bindTexture(TEXTURE);

        switch (type) {
            case INVENTORY:
                // GL 后写的变换先作用：先把方块中心移到原点，再绕原点转 180°。
                GL11.glRotatef(YAW_INVENTORY, 0.0F, 1.0F, 0.0F);
                GL11.glTranslatef(-0.5F, -0.5F, -0.5F);
                model.render(1.0F);
                break;

            case ENTITY:
                // 掉落物在世界里，跟随所在位置的光照，不然暗处会像自发光一样死白。
                // 手持不覆盖：第一/第三人称沿用原版已经设好的手持光照。
                applyWorldLight(data);
                // 只在水平方向居中，底边仍留在实体坐标原点上：
                // Forge 随后整体缩放 0.5，正好等价于原版掉落方块（0..0.5 格，坐在地面）。
                GL11.glTranslatef(-0.5F, 0.0F, -0.5F);
                model.render(1.0F);
                break;

            case EQUIPPED:
            case EQUIPPED_FIRST_PERSON:
                // 1) 抵掉 Forge EQUIPPED_BLOCK 的 -0.5；
                // 2) 补上原版方块物品自带的 rotate(90,Y) · translate(-0.5)：
                //    模型绕自身中心转正，再落到与原版方块相同的持握位置。
                //    结果：铭文面板/倾斜书写台朝向玩家（第一人称）与正前方（第三人称）。
                GL11.glTranslatef(EQUIPPED_OFFSET, EQUIPPED_OFFSET, EQUIPPED_OFFSET);
                // 3) 在手上再抬高一点（铭刻台比普通方块「重」一些，持平拿会显得偏下）
                GL11.glTranslatef(0.0F, HELD_RAISE, 0.0F);
                GL11.glRotatef(YAW_HELD, 0.0F, 1.0F, 0.0F);
                GL11.glTranslatef(-0.5F, -0.5F, -0.5F);
                model.render(1.0F);
                break;

            default:
                break;
        }

        GL11.glPopAttrib();
        GL11.glPopMatrix();
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** 用实体所在位置的打包光照更新光照贴图坐标（与 TESR 的做法一致）。 */
    private static void applyWorldLight(Object[] data) {
        if (data == null || data.length < 2 || !(data[1] instanceof Entity)) {
            return;
        }
        int packed = ((Entity) data[1]).getBrightnessForRender(0.0F);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit,
                (float) (packed & 0xFFFF), (float) (packed >> 16));
    }
}
