package com.example.inscriptiontable.network;

import com.example.inscriptiontable.InscriptionTableMod;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.item.ItemStack;

/**
 * 服务端 → 客户端：槽内物品 + 本次操作结果。
 * <p>
 * 回调在 netty 线程上，所以不直接碰界面，交给代理缓存，由界面在自己的 tick 里取走。
 */
public class PacketUpdate implements IMessage {

    public static final byte OK = 0;
    public static final byte SAVED = 1;
    public static final byte CLEARED = 2;
    public static final byte NO_ITEM = 3;
    public static final byte NO_HELD = 4;
    public static final byte TRUNCATED = 5;

    private byte status;
    private ItemStack stack;

    public PacketUpdate() {
    }

    public PacketUpdate(byte status, ItemStack stack) {
        this.status = status;
        this.stack = stack;
    }

    public byte getStatus() {
        return status;
    }

    public ItemStack getStack() {
        return stack;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        status = buf.readByte();
        stack = ByteBufUtils.readItemStack(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(status);
        ByteBufUtils.writeItemStack(buf, stack);
    }

    public static class Handler implements IMessageHandler<PacketUpdate, IMessage> {

        @Override
        public IMessage onMessage(PacketUpdate message, MessageContext ctx) {
            InscriptionTableMod.proxy.handleSlotUpdate(message.getStatus(), message.getStack());
            return null;
        }
    }
}
