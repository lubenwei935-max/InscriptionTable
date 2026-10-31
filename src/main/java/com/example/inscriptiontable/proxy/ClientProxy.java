package com.example.inscriptiontable.proxy;

import com.example.inscriptiontable.InscriptionTableMod;
import com.example.inscriptiontable.client.render.ItemRenderInscriptionTable;
import com.example.inscriptiontable.client.render.TileEntityInscriptionTableRenderer;
import com.example.inscriptiontable.client.tooltip.LoreTooltipHandler;
import com.example.inscriptiontable.gui.GuiInscriptionTable;
import com.example.inscriptiontable.tile.TileEntityInscriptionTable;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.common.MinecraftForge;

@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);

        // tooltip（RGB 颜色）渲染：ItemTooltipEvent 在 MC 总线，RenderTickEvent 在 FML 总线
        LoreTooltipHandler tooltips = new LoreTooltipHandler();
        MinecraftForge.EVENT_BUS.register(tooltips);
        FMLCommonHandler.instance().bus().register(tooltips);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        ClientRegistry.bindTileEntitySpecialRenderer(TileEntityInscriptionTable.class,
                new TileEntityInscriptionTableRenderer());

        // 背包图标 / 掉落物 / 手持（第一、第三人称）统一用 3D 模型渲染（1.7.10 的 IItemRenderer）
        MinecraftForgeClient.registerItemRenderer(
                Item.getItemFromBlock(InscriptionTableMod.blockInscriptionTable),
                new ItemRenderInscriptionTable());
    }

    @Override
    public void openTableGui(TileEntityInscriptionTable table) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiInscriptionTable(table));
    }

    @Override
    public void handleSlotUpdate(byte status, ItemStack stack) {
        GuiInscriptionTable.queueUpdate(status, stack);
    }
}
