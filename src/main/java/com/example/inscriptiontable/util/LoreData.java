package com.example.inscriptiontable.util;

import com.example.inscriptiontable.InscriptionConfig;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * 铭文在物品上的持久化读写（NBT）。
 * <p>
 * 存在 ItemStack 根标签的 {@link #TAG_LORE} 键下，因此：
 * 物品被合成/改名/放进箱子后铭文依然存在；不需要修改 NBT 的 display 结构，也不会和原版 Lore 冲突。
 * <p>
 * 文本允许换行：内部统一用 {@code '\n'} 保存。旧版用「反斜杠 + n」表示换行，
 * {@link LoreParser} 仍然兼容这种写法，所以老物品的显示不会变。
 */
public final class LoreData {

    public static final String TAG_LORE = "InscriptionLore";

    private LoreData() {
    }

    /** 读取本模组的铭文，没有则返回空串 */
    public static String getLore(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()) {
            return "";
        }
        NBTTagCompound tag = stack.getTagCompound();
        if (!tag.hasKey(TAG_LORE)) {
            return "";
        }
        String lore = tag.getString(TAG_LORE);
        return lore == null ? "" : lore;
    }

    public static boolean hasLore(ItemStack stack) {
        return getLore(stack).length() > 0;
    }

    /**
     * 编辑器要显示的文本：优先本模组铭文；没有则回退到原版 {@code display.Lore}
     * （也就是其它模组/原版命令写进去、tooltip 里能看到的那段介绍文本）。
     */
    public static String getEditableText(ItemStack stack) {
        String own = getLore(stack);
        if (own.length() > 0) {
            // 老数据用「反斜杠 + n」表示换行；编辑器里转成真换行，编辑体验一致
            return own.indexOf('\n') >= 0 ? own : own.replace("\\n", "\n");
        }
        return getVanillaLore(stack);
    }

    /** 原版 {@code display.Lore} 的内容，按行拼接（没有则空串） */
    public static String getVanillaLore(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()) {
            return "";
        }
        NBTTagCompound tag = stack.getTagCompound();
        if (!tag.hasKey("display")) {
            return "";
        }
        NBTTagCompound display = tag.getCompoundTag("display");
        if (!display.hasKey("Lore", 9)) {
            return "";
        }
        NBTTagList list = display.getTagList("Lore", 8);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.tagCount(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(list.getStringTagAt(i));
        }
        return sb.toString();
    }

    /** 写入铭文；空串等于删除 */
    public static void setLore(ItemStack stack, String lore) {
        if (stack == null) {
            return;
        }
        String clean = sanitize(lore);
        if (clean.length() == 0) {
            clearLore(stack);
            return;
        }
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setString(TAG_LORE, clean);
    }

    public static void clearLore(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()) {
            return;
        }
        NBTTagCompound tag = stack.getTagCompound();
        tag.removeTag(TAG_LORE);
        if (tag.hasNoTags()) {
            stack.setTagCompound(null);
        }
    }

    /**
     * 保存时清掉原版 {@code display.Lore}。
     * 否则「读取原版 lore → 编辑 → 保存」会让 tooltip 里出现两份文字。
     */
    public static void clearVanillaLore(ItemStack stack) {
        if (stack == null || !stack.hasTagCompound()) {
            return;
        }
        NBTTagCompound tag = stack.getTagCompound();
        if (!tag.hasKey("display")) {
            return;
        }
        NBTTagCompound display = tag.getCompoundTag("display");
        display.removeTag("Lore");
        if (display.hasNoTags()) {
            tag.removeTag("display");
        }
    }

    /**
     * 规范化编辑文本：统一换行、去掉控制字符、把 {@code §} 转成 {@code &}、截断到配置上限。
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\u00A7') {
                // 玩家从别处粘贴进来的 § 代码统一转成 & 写法，避免存进 NBT 后不可见
                sb.append('&');
                continue;
            }
            if (c == '\r') {
                continue;
            }
            if (c == '\n') {
                sb.append('\n');
                continue;
            }
            if (c == '\t') {
                sb.append(' ');
                continue;
            }
            if (c < 0x20) {
                continue;
            }
            if (c == 0x7F || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)) {
                // DEL 会显示成一个空框；双向文本控制符会让 tooltip 里的文字倒序/错位，都丢掉
                continue;
            }
            sb.append(c);
        }

        // 去掉每行行尾多余空格（保留空行与行首缩进）
        String[] lines = sb.toString().split("\n", -1);
        StringBuilder out = new StringBuilder(sb.length());
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int end = line.length();
            while (end > 0 && line.charAt(end - 1) == ' ') {
                end--;
            }
            if (i > 0) {
                out.append('\n');
            }
            out.append(line, 0, end);
        }

        String clean = out.toString().trim();
        int max = Math.max(1, InscriptionConfig.maxLoreLength);
        if (clean.length() > max) {
            clean = clean.substring(0, max);
        }
        return limitLines(clean, Math.max(1, InscriptionConfig.maxLoreLines));
    }

    /** 只保留前 maxLines 行（超出的整行丢弃） */
    private static String limitLines(String text, int maxLines) {
        int index = -1;
        for (int i = 0; i < maxLines; i++) {
            index = text.indexOf('\n', index + 1);
            if (index < 0) {
                return text;
            }
        }
        return text.substring(0, index);
    }
}
