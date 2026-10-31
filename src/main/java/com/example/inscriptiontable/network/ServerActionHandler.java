package com.example.inscriptiontable.network;

import com.example.inscriptiontable.InscriptionConfig;
import com.example.inscriptiontable.tile.TileEntityInscriptionTable;
import com.example.inscriptiontable.util.LoreData;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * 服务端逻辑：所有改动都在服务端主线程执行（由 {@link ModNetwork#drainServerTasks()} 调度）。
 * <p>
 * 客户端只负责显示与输入，不能直接改 NBT，也不能决定物品归属。
 */
public final class ServerActionHandler {

    /** 玩家离方块超过 8 格（平方 64）就丢弃请求 */
    private static final double MAX_DISTANCE_SQ = 64.0D;

    private ServerActionHandler() {
    }

    public static void handle(EntityPlayerMP player, PacketAction message) {
        World world = player.worldObj;
        int x = message.getX();
        int y = message.getY();
        int z = message.getZ();

        if (!world.blockExists(x, y, z)) {
            return;
        }
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityInscriptionTable)) {
            return;
        }
        if (player.getDistanceSq(x + 0.5D, y + 0.5D, z + 0.5D) > MAX_DISTANCE_SQ) {
            return;
        }
        TileEntityInscriptionTable table = (TileEntityInscriptionTable) tile;

        byte status;
        switch (message.getAction()) {
            case PacketAction.OPEN:
                status = PacketUpdate.OK;
                break;
            case PacketAction.SLOT_CLICK:
                status = toggleItem(player, table);
                break;
            case PacketAction.SAVE:
                status = saveLore(table, message.getText());
                break;
            default:
                return;
        }
        // 拷贝一份再发：PacketUpdate 的 toBytes 在 netty 线程执行，
        // 直接把 TileEntity 里的活引用交出去会和主线程的修改撞车
        ItemStack snapshot = table.getStack();
        ModNetwork.sendTo(new PacketUpdate(status, snapshot == null ? null : snapshot.copy()), player);
    }

    /** 空槽：从手持物品里取 1 个放入；有物品：放回背包（背包满则掉落） */
    private static byte toggleItem(EntityPlayerMP player, TileEntityInscriptionTable table) {
        ItemStack inSlot = table.getStack();
        if (inSlot == null) {
            ItemStack held = player.getCurrentEquippedItem();
            if (held == null || held.stackSize <= 0) {
                return PacketUpdate.NO_HELD;
            }
            ItemStack one = held.copy();
            one.stackSize = 1;
            table.setStack(one);

            held.stackSize--;
            if (held.stackSize <= 0) {
                player.inventory.mainInventory[player.inventory.currentItem] = null;
            }
            player.inventory.markDirty();
            player.inventoryContainer.detectAndSendChanges();
            return PacketUpdate.OK;
        }

        ItemStack copy = inSlot.copy();
        table.setStack(null);
        if (!player.inventory.addItemStackToInventory(copy)) {
            player.dropPlayerItemWithRandomChoice(copy, false);
        }
        player.inventory.markDirty();
        player.inventoryContainer.detectAndSendChanges();
        return PacketUpdate.OK;
    }

    /** 保存：服务端再清洗一遍并截断，空文本等于删除铭文 */
    private static byte saveLore(TileEntityInscriptionTable table, String text) {
        ItemStack stack = table.getStack();
        if (stack == null) {
            return PacketUpdate.NO_ITEM;
        }
        String raw = text == null ? "" : text;
        String clean = LoreData.sanitize(raw);
        LoreData.setLore(stack, clean);
        // 读取过原版 display.Lore 的话，这里把它去掉，避免保存后 tooltip 出现两份
        LoreData.clearVanillaLore(stack);
        table.markDirty();

        if (clean.length() == 0) {
            return PacketUpdate.CLEARED;
        }
        if (raw.length() > Math.max(1, InscriptionConfig.maxLoreLength)) {
            return PacketUpdate.TRUNCATED;
        }
        return PacketUpdate.SAVED;
    }
}
