package com.example.inscriptiontable.tile;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

/**
 * 铭刻台的 TileEntity。
 * <p>
 * 只做三件事：
 * <ol>
 * <li>提供 TESR（自定义方块模型）用的动画时钟；</li>
 * <li>保存「待编辑物品」（原版 {@link ItemStack}，单槽、不叠加）；</li>
 * <li>负责持久化。界面与同步都由原版网络包完成，这里不依赖任何 UI 库。</li>
 * </ol>
 * 铭文本身仍然写在物品 NBT 上，所以拆掉方块不会丢数据（方块里有物品时会掉出来）。
 */
public class TileEntityInscriptionTable extends TileEntity {

    /** 新版存储键（直接存 ItemStack） */
    private static final String TAG_STACK = "StoredItem";
    /** 旧版（ModularUI ItemStackHandler）存储键，仅用于读档兼容 */
    private static final String TAG_LEGACY_STORAGE = "Storage";

    private ItemStack stored;
    private int ticks;

    public ItemStack getStack() {
        return stored;
    }

    public void setStack(ItemStack stack) {
        stored = stack;
        markDirty();
    }

    public boolean isEmpty() {
        return stored == null;
    }

    @Override
    public boolean canUpdate() {
        return true;
    }

    @Override
    public void updateEntity() {
        ++ticks;
    }

    /** 0.85F ~ 1.0F 的呼吸式亮度，用于铭文条 */
    public float getGlow(float partialTicks) {
        float t = ticks + partialTicks;
        return 0.85F + 0.15F * (float) Math.sin(t * 0.08D);
    }

    // ------------------------------------------------------------------
    // NBT
    // ------------------------------------------------------------------

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        tag.setInteger("Ticks", ticks);
        if (stored != null) {
            NBTTagCompound itemTag = new NBTTagCompound();
            stored.writeToNBT(itemTag);
            tag.setTag(TAG_STACK, itemTag);
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        ticks = tag.getInteger("Ticks");
        stored = null;
        if (tag.hasKey(TAG_STACK)) {
            stored = ItemStack.loadItemStackFromNBT(tag.getCompoundTag(TAG_STACK));
        } else if (tag.hasKey(TAG_LEGACY_STORAGE)) {
            // 旧存档：ItemStackHandler.serializeNBT() 的结构是 { Size:1, Items:[ {...} ] }
            NBTTagCompound legacy = tag.getCompoundTag(TAG_LEGACY_STORAGE);
            NBTTagList items = legacy.getTagList("Items", 10);
            if (items.tagCount() > 0) {
                stored = ItemStack.loadItemStackFromNBT(items.getCompoundTagAt(0));
            }
        }
    }
}
