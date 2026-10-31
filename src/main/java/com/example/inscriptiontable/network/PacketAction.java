package com.example.inscriptiontable.network;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 客户端 → 服务端：一次铭刻台操作。
 * <p>
 * 使用方块的坐标定位（而不是容器 id），因为界面是纯客户端的 {@code GuiScreen}，
 * 没有原版 Container，但仍然要由服务端校验距离与物品。
 */
public class PacketAction implements IMessage {

    /** 请求同步当前槽内物品（界面打开时） */
    public static final byte OPEN = 0;
    /** 点击物品槽：空槽取走手持物品，有物品则放回背包 */
    public static final byte SLOT_CLICK = 1;
    /** 保存编辑框内容 */
    public static final byte SAVE = 2;

    private byte action;
    private int x;
    private int y;
    private int z;
    private String text = "";

    public PacketAction() {
    }

    public PacketAction(byte action, int x, int y, int z) {
        this(action, x, y, z, "");
    }

    public PacketAction(byte action, int x, int y, int z, String text) {
        this.action = action;
        this.x = x;
        this.y = y;
        this.z = z;
        this.text = text == null ? "" : text;
    }

    public byte getAction() {
        return action;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public String getText() {
        return text;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action = buf.readByte();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        text = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(action);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        ByteBufUtils.writeUTF8String(buf, text);
    }

    public static class Handler implements IMessageHandler<PacketAction, IMessage> {

        @Override
        public IMessage onMessage(final PacketAction message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            // netty 线程 → 服务端主线程
            ModNetwork.scheduleServer(new Runnable() {

                @Override
                public void run() {
                    if (player != null) {
                        ServerActionHandler.handle(player, message);
                    }
                }
            });
            return null;
        }
    }
}
