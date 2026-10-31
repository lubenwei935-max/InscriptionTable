package com.example.inscriptiontable.block;

import com.example.inscriptiontable.InscriptionTableMod;
import com.example.inscriptiontable.tile.TileEntityInscriptionTable;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * 铭刻台方块。
 * <p>
 * 方块本体不可见（getRenderType() == -1），实体外形完全由
 * {@link com.example.inscriptiontable.client.render.TileEntityInscriptionTableRenderer}
 * 用自定义立方体模型绘制，这样可以做出原版方块模型系统（1.7.10 没有 JSON 方块模型）做不到的斜面结构。
 */
public class BlockInscriptionTable extends BlockContainer {

    public BlockInscriptionTable() {
        super(Material.wood);
        setBlockName("inscription_table");
        // 物品栏 / 破坏粒子使用的小图标（16x16）
        setBlockTextureName(InscriptionTableMod.MODID + ":inscription_table_icon");
        setHardness(2.5F);
        setResistance(8.0F);
        setStepSound(Block.soundTypeWood);
        setCreativeTab(net.minecraft.creativetab.CreativeTabs.tabDecorations);
        setLightOpacity(0);
        setBlockBounds(0.06F, 0.0F, 0.06F, 0.94F, 0.95F, 0.94F);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileEntityInscriptionTable();
    }

    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    @Override
    public int getRenderType() {
        return -1;
    }

    /** 铭文条自发光，给周围一点亮度 */
    @Override
    public int getLightValue(IBlockAccess world, int x, int y, int z) {
        return 4;
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player,
                                    int side, float hitX, float hitY, float hitZ) {
        if (player.isSneaking()) {
            return false;
        }
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityInscriptionTable)) {
            return false;
        }
        if (world.isRemote) {
            // 纯客户端界面；打开后由界面自己向服务端请求槽内物品
            InscriptionTableMod.proxy.openTableGui((TileEntityInscriptionTable) tile);
        }
        return true;
    }

    /** 拆掉方块时，把还放在铭刻台里的物品掉出来 */
    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int meta) {
        TileEntity tile = world.getTileEntity(x, y, z);
        // 只在服务端掉落：客户端世界里的 TileEntity 内容从未同步（永远是空），
        // 不判断 isRemote 的话，一旦以后加了槽位同步，拆方块就会多出一个客户端幽灵物品
        if (!world.isRemote && tile instanceof TileEntityInscriptionTable) {
            ItemStack stack = ((TileEntityInscriptionTable) tile).getStack();
            if (stack != null) {
                ((TileEntityInscriptionTable) tile).setStack(null);
                world.spawnEntityInWorld(new EntityItem(world, x + 0.5D, y + 0.5D, z + 0.5D, stack));
            }
        }
        super.breakBlock(world, x, y, z, block, meta);
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        int meta = MathHelper.floor_double((double) (placer.rotationYaw * 4.0F / 360.0F) + 0.5D) & 3;
        world.setBlockMetadataWithNotify(x, y, z, meta, 2);
    }
}
