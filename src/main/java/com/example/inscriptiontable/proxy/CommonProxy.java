package com.example.inscriptiontable.proxy;

import com.example.inscriptiontable.network.ModNetwork;
import com.example.inscriptiontable.tile.TileEntityInscriptionTable;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.item.ItemStack;

/**
 * 服务端/通用代理。
 * <p>
 * 网络通道在这里初始化；服务端 tick 末尾统一处理来自客户端的请求（netty 线程 → 主线程）。
 */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        ModNetwork.init();
        FMLCommonHandler.instance().bus().register(new ServerTickHandler());
    }

    public void init(FMLInitializationEvent event) {
    }

    /** 客户端专用：打开界面。服务端没有界面，留空。 */
    public void openTableGui(TileEntityInscriptionTable table) {
    }

    /** 客户端专用：收到服务端的槽位/保存结果。 */
    public void handleSlotUpdate(byte status, ItemStack stack) {
    }

    public static class ServerTickHandler {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.END) {
                ModNetwork.drainServerTasks();
            }
        }
    }
}
