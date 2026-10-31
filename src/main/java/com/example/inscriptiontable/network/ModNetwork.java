package com.example.inscriptiontable.network;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.example.inscriptiontable.InscriptionTableMod;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 原版（Forge 1.7.10）网络通道。
 * <p>
 * 只有两个包：
 * <ul>
 * <li>{@link PacketAction}：客户端 → 服务端（请求同步 / 点击物品槽 / 保存铭文）；</li>
 * <li>{@link PacketUpdate}：服务端 → 客户端（槽内物品 + 处理结果）。</li>
 * </ul>
 * 1.7.10 的 netty 回调不在主线程上，所以收到的请求先入队，
 * 由服务端 tick 末尾在 {@link #drainServerTasks()} 里统一执行，避免并发改世界/NBT。
 */
public final class ModNetwork {

    private static SimpleNetworkWrapper channel;
    private static final Queue<Runnable> SERVER_TASKS = new ConcurrentLinkedQueue<Runnable>();

    private ModNetwork() {
    }

    public static void init() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel(InscriptionTableMod.MODID);
        channel.registerMessage(PacketAction.Handler.class, PacketAction.class, 0, Side.SERVER);
        channel.registerMessage(PacketUpdate.Handler.class, PacketUpdate.class, 1, Side.CLIENT);
    }

    public static void sendToServer(IMessage message) {
        channel.sendToServer(message);
    }

    public static void sendTo(IMessage message, EntityPlayerMP player) {
        channel.sendTo(message, player);
    }

    /** netty 线程调用：把任务排到服务端主线程 */
    public static void scheduleServer(Runnable task) {
        SERVER_TASKS.add(task);
    }

    /** 服务端 tick 末尾调用 */
    public static void drainServerTasks() {
        Runnable task;
        while ((task = SERVER_TASKS.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                System.err.println("[InscriptionTable] 处理网络请求失败: " + t);
            }
        }
    }
}
