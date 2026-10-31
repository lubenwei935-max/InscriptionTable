package com.example.inscriptiontable.client.render;

import com.example.inscriptiontable.InscriptionTableMod;
import com.example.inscriptiontable.tile.TileEntityInscriptionTable;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;

/**
 * 把 {@link ModelInscriptionTable} 渲染到世界里。
 * <p>
 * 方块本身 getRenderType() == -1，因此实体外形完全由这里绘制。
 */
@SideOnly(Side.CLIENT)
public class TileEntityInscriptionTableRenderer extends TileEntitySpecialRenderer {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(InscriptionTableMod.MODID, "textures/blocks/inscription_table.png");

    private final ModelInscriptionTable model = new ModelInscriptionTable();

    @Override
    public void renderTileEntityAt(TileEntity tile, double x, double y, double z, float partialTicks) {
        if (!(tile instanceof TileEntityInscriptionTable)) {
            return;
        }
        TileEntityInscriptionTable table = (TileEntityInscriptionTable) tile;
        int meta = tile.getBlockMetadata() & 3;

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);

        GL11.glDisable(GL11.GL_LIGHTING);
        // 模型是双面绘制的，关掉背面剔除可以避免手写顶点绕序出错时面消失
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

        bindTexture(TEXTURE);

        // 以方块中心为基准旋转（meta 0 时正面朝北/朝向放置者）
        GL11.glTranslated(x, y, z);
        GL11.glTranslatef(0.5F, 0.0F, 0.5F);
        GL11.glRotatef(-90.0F * meta, 0.0F, 1.0F, 0.0F);
        GL11.glTranslatef(-0.5F, 0.0F, -0.5F);

        // 跟随世界光照，否则 TESR 渲染出来会像自发光一样死白
        if (table.getWorldObj() != null) {
            int packed = table.getWorldObj().getLightBrightnessForSkyBlocks(
                    table.xCoord, table.yCoord, table.zCoord, 0);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit,
                    (float) (packed & 0xFFFF), (float) (packed >> 16));
        } else {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
        }

        model.render(table.getGlow(partialTicks));

        GL11.glPopAttrib();
        GL11.glPopMatrix();
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
