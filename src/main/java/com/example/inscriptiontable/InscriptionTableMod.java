package com.example.inscriptiontable;

import com.example.inscriptiontable.block.BlockInscriptionTable;
import com.example.inscriptiontable.proxy.CommonProxy;
import com.example.inscriptiontable.tile.TileEntityInscriptionTable;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.Mod.Instance;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

/**
 * 铭刻台（Inscription Table）
 * <p>
 * 1.7.10 + Forge 模组入口。界面是原版 {@code GuiScreen} 手写实现
 * （见 {@link com.example.inscriptiontable.gui.GuiInscriptionTable}），
 * 不依赖任何第三方 UI 库；同步用原版网络包（{@code network} 包）。
 */
@Mod(modid = InscriptionTableMod.MODID, name = InscriptionTableMod.NAME, version = InscriptionTableMod.VERSION,
        acceptedMinecraftVersions = "[1.7.10]")
public class InscriptionTableMod {

    public static final String MODID = "inscriptiontable";
    public static final String NAME = "Inscription Table";
    public static final String VERSION = "1.2.2";

    @Instance(MODID)
    public static InscriptionTableMod instance;

    @SidedProxy(clientSide = "com.example.inscriptiontable.proxy.ClientProxy",
            serverSide = "com.example.inscriptiontable.proxy.CommonProxy")
    public static CommonProxy proxy;

    public static Block blockInscriptionTable;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        InscriptionConfig.load(event.getSuggestedConfigurationFile());

        blockInscriptionTable = new BlockInscriptionTable();
        GameRegistry.registerBlock(blockInscriptionTable, "inscription_table");
        GameRegistry.registerTileEntity(TileEntityInscriptionTable.class, MODID + ":inscription_table");

        proxy.preInit(event);
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        // 木板 + 书 + 石头
        GameRegistry.addRecipe(new ItemStack(blockInscriptionTable),
                "PPP", "PBP", "SSS",
                'P', Blocks.planks,
                'B', Items.book,
                'S', Blocks.stone);

        proxy.init(event);
    }
}
