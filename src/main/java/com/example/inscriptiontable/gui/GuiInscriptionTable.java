package com.example.inscriptiontable.gui;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.example.inscriptiontable.InscriptionTableMod;
import com.example.inscriptiontable.client.ime.IngameImeSupport;
import com.example.inscriptiontable.network.ModNetwork;
import com.example.inscriptiontable.network.PacketAction;
import com.example.inscriptiontable.network.PacketUpdate;
import com.example.inscriptiontable.tile.TileEntityInscriptionTable;
import com.example.inscriptiontable.util.LoreData;
import com.example.inscriptiontable.util.LoreParser;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

/**
 * 铭刻台界面（原版 {@link GuiScreen} 手绘，不依赖任何 UI 库）。
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────┐
 * │ 铭刻台                                        v1.2.2     │
 * │ 所见即所得 · 支持 &amp;6 / &amp;#RRGGBB                          │
 * ├──────────────────────────────────────────────────────────┤
 * │ ┌──┐ 物品名                                              │
 * │ │槽│ 手持物品点击放入，再点取回                            │
 * │ └──┘                                                      │
 * │ ■■■■■■■■  [RRGGBB    ] [应用]                            │
 * │ ■■■■■■■■  [B] [I] [U] [R]                                 │
 * │ ┌──────────────────────────────────────────────────────┐  │
 * │ │ 深色编辑区（多行 / 软换行 / 选区 / 撤销 / 颜色实时）  │  │
 * │ └──────────────────────────────────────────────────────┘  │
 * │ 状态提示                              [ 保存 ] [ 清空 ]  │
 * └──────────────────────────────────────────────────────────┘
 * </pre>
 *
 * 物品槽只有 1 个，没有玩家背包格子：点击槽位时从手持物品取 1 个放入，
 * 再点一次把物品放回背包。所有真正改动都由服务端完成（{@link PacketAction}）。
 */
@SideOnly(Side.CLIENT)
public class GuiInscriptionTable extends GuiScreen {

    // ---------------------------------------------------------------- 尺寸
    private static final int W = 300;
    private static final int H = 230;
    private static final int HEADER_H = 26;

    private static final int CARD_X = 8;
    private static final int CARD_Y = 30;
    private static final int CARD_W = W - 16;
    private static final int CARD_H = 24;

    private static final int SLOT_X = 12;
    private static final int SLOT_Y = 33;
    private static final int SLOT_SIZE = 18;

    private static final int TOOLBAR_ROW1 = 57;
    private static final int TOOLBAR_ROW2 = 71;
    private static final int SWATCH_SIZE = 10;
    private static final int SWATCH_PITCH = 12;

    private static final int RGB_X = 110;
    private static final int RGB_Y = 57;
    private static final int RGB_W = 64;
    private static final int RGB_H = 12;

    private static final int APPLY_X = 178;
    private static final int APPLY_Y = 57;
    private static final int APPLY_W = 34;
    private static final int APPLY_H = 12;

    private static final int B_X = 110;
    private static final int I_X = 132;
    private static final int U_X = 154;
    private static final int R_X = 176;
    private static final int FMT_Y = 71;
    private static final int FMT_W = 18;
    private static final int FMT_H = 12;

    private static final int EDITOR_X = 10;
    private static final int EDITOR_Y = 85;
    private static final int EDITOR_W = W - 20;
    private static final int EDITOR_H = 114;

    private static final int BUTTON_Y = 204;
    private static final int BUTTON_H = 18;
    private static final int SAVE_X = 196;
    private static final int SAVE_W = 44;
    private static final int CLEAR_X = 244;
    private static final int CLEAR_W = 44;

    private static final int STATUS_Y = 205;

    // ---------------------------------------------------------------- 配色
    private static final int C_PANEL = 0xFFF2F3F5;
    private static final int C_PANEL_BORDER = 0xFFB0B6C0;
    private static final int C_HEADER = 0xFF2E3440;
    private static final int C_ACCENT = 0xFF4C8DFF;
    private static final int C_TEXT = 0xFF333A46;
    private static final int C_MUTED = 0xFF7C8494;
    private static final int C_CARD = 0xFFF8F9FB;
    private static final int C_CARD_BORDER = 0xFFD5DAE1;
    private static final int C_EDITOR = 0xFF1E222A;
    private static final int C_EDITOR_BORDER = 0xFF3A4150;
    private static final int C_BUTTON = 0xFF3A4150;
    private static final int C_BUTTON_HOVER = 0xFF4C8DFF;
    private static final int C_BUTTON_DISABLED = 0xFFC6CBD4;
    private static final int C_BUTTON_TEXT = 0xFFFFFFFF;
    private static final int C_BUTTON_TEXT_DISABLED = 0xFF8A8F99;
    private static final int C_OK = 0xFF2E7D32;
    private static final int C_WARN = 0xFFB26A00;
    private static final int C_FAIL = 0xFFB00020;

    private static final int[] SWATCH_COLORS = LoreParser.LEGACY_COLORS;
    private static final String LEGACY_CODES = "0123456789abcdef";

    // ---------------------------------------------------------------- 服务端回包（netty 线程写入）
    private static boolean hasPending;
    private static byte pendingStatus;
    private static ItemStack pendingStack;

    public static synchronized void queueUpdate(byte status, ItemStack stack) {
        pendingStatus = status;
        pendingStack = stack;
        hasPending = true;
    }

    private static synchronized void clearPending() {
        hasPending = false;
        pendingStack = null;
        pendingStatus = 0;
    }

    // ---------------------------------------------------------------- 状态
    private final TileEntityInscriptionTable table;
    private ItemStack stack;
    private LoreEditor editor;
    private GuiTextField rgbField;

    private int left;
    private int top;
    private int mouseX;
    private int mouseY;

    private String statusText = "";
    private int statusColor = C_MUTED;
    private int statusTicks;

    private boolean fontChanged;
    private String loadedKey = "";
    /** 点槽位放入物品后，等回包到达再让编辑器进入编辑状态 */
    private boolean focusOnNextItem;

    public GuiInscriptionTable(TileEntityInscriptionTable table) {
        this.table = table;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void initGui() {
        buttonList.clear();
        left = (width - W) / 2;
        top = (height - H) / 2;

        FontRenderer font = mc.fontRenderer;
        if (font != null && !font.getUnicodeFlag()) {
            font.setUnicodeFlag(true);
            fontChanged = true;
        }
        Keyboard.enableRepeatEvents(true);

        editor = new LoreEditor(fontRendererObj);
        editor.setBounds(left + 12, top + EDITOR_Y + 2, W - 24, EDITOR_H - 6);
        editor.setEnabled(false);
        IngameImeSupport.bind(editor);
        clearPending();

        rgbField = new GuiTextField(fontRendererObj, left + RGB_X, top + RGB_Y, RGB_W, RGB_H);
        rgbField.setMaxStringLength(6);
        rgbField.setTextColor(0xFFFFFFFF);
        rgbField.setEnableBackgroundDrawing(true);

        statusText = translate("gui.inscription_table.need_item");
        statusColor = C_MUTED;
        statusTicks = 0;

        sendAction(PacketAction.OPEN);
    }

    @Override
    public void onGuiClosed() {
        IngameImeSupport.unbind();
        Keyboard.enableRepeatEvents(false);
        if (fontChanged) {
            FontRenderer font = mc.fontRenderer;
            if (font != null) {
                font.setUnicodeFlag(false);
            }
            fontChanged = false;
        }
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        // 方块被拆掉 / 被替换 / 区块卸载后界面不能继续留着：服务端已经找不到 TileEntity，
        // 之后所有请求都会被静默丢弃，玩家看到的就是「点什么都没反应」。
        if (!isTableStillThere()) {
            mc.displayGuiScreen(null);
            return;
        }
        editor.tick();
        IngameImeSupport.drain(editor);
        syncImeFocus();
        if (statusTicks > 0) {
            statusTicks--;
        }
        byte status = 0;
        ItemStack newStack = null;
        boolean updated = false;
        synchronized (GuiInscriptionTable.class) {
            if (hasPending) {
                status = pendingStatus;
                newStack = pendingStack;
                hasPending = false;
                updated = true;
            }
        }
        if (updated) {
            applyUpdate(status, newStack);
        }
    }

    /** 方块与 TileEntity 是否还在（客户端判断，用于及时关闭失效的界面） */
    private boolean isTableStillThere() {
        if (table == null || mc.theWorld == null) {
            return false;
        }
        int x = table.xCoord;
        int y = table.yCoord;
        int z = table.zCoord;
        if (!mc.theWorld.blockExists(x, y, z)) {
            return false;
        }
        return mc.theWorld.getTileEntity(x, y, z) instanceof TileEntityInscriptionTable;
    }

    private void applyUpdate(byte status, ItemStack newStack) {
        boolean hadItem = stack != null;
        stack = newStack;
        String key = keyOf(newStack);

        if (newStack == null) {
            editor.setEnabled(false);
            focusOnNextItem = false;
            if (hadItem) {
                editor.setText("");
                loadedKey = "";
            }
        } else {
            editor.setEnabled(true);
            if (focusOnNextItem) {
                // 玩家刚把物品放进台子，直接开始编辑
                editor.setFocused(true);
                focusOnNextItem = false;
            }
            if (!key.equals(loadedKey)) {
                editor.setText(LoreData.getEditableText(newStack));
                loadedKey = key;
            }
        }

        switch (status) {
            case PacketUpdate.SAVED:
                setStatus(translate("gui.inscription_table.saved"), C_OK);
                break;
            case PacketUpdate.CLEARED:
                setStatus(translate("gui.inscription_table.lore_cleared"), C_MUTED);
                break;
            case PacketUpdate.TRUNCATED:
                setStatus(translate("gui.inscription_table.truncated"), C_WARN);
                break;
            case PacketUpdate.NO_ITEM:
                setStatus(translate("gui.inscription_table.noitem"), C_FAIL);
                break;
            case PacketUpdate.NO_HELD:
                setStatus(translate("gui.inscription_table.no_held"), C_FAIL);
                break;
            default:
                if (newStack == null) {
                    setStatus(translate("gui.inscription_table.need_item"), C_MUTED);
                } else if (!hadItem) {
                    setStatus(translate("gui.inscription_table.item_loaded"), C_MUTED);
                }
                break;
        }

        if (status == PacketUpdate.TRUNCATED && newStack != null) {
            // 服务端按同一规则截断过了，编辑器要显示真正存下来的文本，否则玩家以为存了全文
            editor.setText(LoreData.getEditableText(newStack));
        }
    }

    private static String keyOf(ItemStack stack) {
        if (stack == null || stack.getItem() == null) {
            return "";
        }
        return stack.getItem().getUnlocalizedName() + ":" + stack.getItemDamage();
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;

        drawDefaultBackground();

        drawRect(left - 3, top - 3, left + W + 3, top + H + 3, 0x55000000);
        drawRect(left - 1, top - 1, left + W + 1, top + H + 1, C_PANEL_BORDER);
        drawRect(left, top, left + W, top + H, C_PANEL);

        // 页眉
        drawRect(left, top, left + W, top + HEADER_H, C_HEADER);
        drawRect(left, top + HEADER_H - 1, left + W, top + HEADER_H, C_ACCENT);
        fontRendererObj.drawString(translate("gui.inscription_table.title"), left + 12, top + 5, 0xFFF7F9FC);
        fontRendererObj.drawString(translate("gui.inscription_table.subtitle"), left + 12, top + 16, 0xFF9AA6BC);
        String version = "v" + InscriptionTableMod.VERSION;
        fontRendererObj.drawString(version, left + W - 12 - fontRendererObj.getStringWidth(version),
                top + 9, 0xFF9AA6BC);

        drawItemCard();
        drawToolbar();
        drawEditor();
        drawStatus();
        drawButtons();

        if (stack != null && inside(mouseX, mouseY, left + SLOT_X, top + SLOT_Y, SLOT_SIZE, SLOT_SIZE)) {
            renderToolTip(stack, mouseX, mouseY);
        }
    }

    private void drawItemCard() {
        drawRect(left + CARD_X, top + CARD_Y, left + CARD_X + CARD_W, top + CARD_Y + CARD_H, C_CARD_BORDER);
        drawRect(left + CARD_X + 1, top + CARD_Y + 1, left + CARD_X + CARD_W - 1,
                top + CARD_Y + CARD_H - 1, C_CARD);

        int slotX = left + SLOT_X;
        int slotY = top + SLOT_Y;
        boolean hover = inside(mouseX, mouseY, slotX, slotY, SLOT_SIZE, SLOT_SIZE);
        drawRect(slotX - 1, slotY - 1, slotX + SLOT_SIZE + 1, slotY + SLOT_SIZE + 1,
                hover ? C_ACCENT : 0xFF9AA0AA);
        drawRect(slotX, slotY, slotX + SLOT_SIZE, slotY + SLOT_SIZE, 0xFFE7E9EE);

        if (stack == null) {
            int cx = slotX + SLOT_SIZE / 2;
            int cy = slotY + SLOT_SIZE / 2;
            drawRect(cx - 4, cy, cx + 5, cy + 1, 0xFFB6BCC6);
            drawRect(cx, cy - 4, cx + 1, cy + 5, 0xFFB6BCC6);
        } else {
            RenderHelper.enableGUIStandardItemLighting();
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            RenderItem.getInstance().renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(),
                    stack, slotX + 1, slotY + 1);
            RenderItem.getInstance().renderItemOverlayIntoGUI(fontRendererObj, mc.getTextureManager(),
                    stack, slotX + 1, slotY + 1);
            RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        }

        String name = stack == null
                ? translate("gui.inscription_table.no_item")
                : trim(stack.getDisplayName(), CARD_W - 36);
        fontRendererObj.drawString(name, left + 34, top + 32, stack == null ? C_MUTED : C_TEXT);
        fontRendererObj.drawString(translate("gui.inscription_table.slot_hint"), left + 34, top + 43, C_MUTED);
    }

    private void drawToolbar() {
        for (int i = 0; i < SWATCH_COLORS.length; i++) {
            int x = left + 12 + (i % 8) * SWATCH_PITCH;
            int y = top + (i < 8 ? TOOLBAR_ROW1 : TOOLBAR_ROW2);
            boolean hover = inside(mouseX, mouseY, x, y, SWATCH_SIZE, SWATCH_SIZE);
            drawRect(x - 1, y - 1, x + SWATCH_SIZE + 1, y + SWATCH_SIZE + 1,
                    hover ? 0xFFFFFFFF : 0xFF6B7280);
            drawRect(x, y, x + SWATCH_SIZE, y + SWATCH_SIZE, 0xFF000000 | SWATCH_COLORS[i]);
        }

        rgbField.drawTextBox();
        if (rgbField.getText().isEmpty() && !rgbField.isFocused()) {
            fontRendererObj.drawString("RRGGBB", left + RGB_X + 4, top + RGB_Y + 3, 0xFF8A94A6);
        }

        boolean applyHover = inside(mouseX, mouseY, left + APPLY_X, top + APPLY_Y, APPLY_W, APPLY_H);
        drawSmallButton(left + APPLY_X, top + APPLY_Y, APPLY_W, APPLY_H,
                translate("gui.inscription_table.apply_color"), applyHover, true);

        drawSmallButton(left + B_X, top + FMT_Y, FMT_W, FMT_H, "B", inside(mouseX, mouseY,
                left + B_X, top + FMT_Y, FMT_W, FMT_H), true);
        drawSmallButton(left + I_X, top + FMT_Y, FMT_W, FMT_H, "I", inside(mouseX, mouseY,
                left + I_X, top + FMT_Y, FMT_W, FMT_H), true);
        drawSmallButton(left + U_X, top + FMT_Y, FMT_W, FMT_H, "U", inside(mouseX, mouseY,
                left + U_X, top + FMT_Y, FMT_W, FMT_H), true);
        drawSmallButton(left + R_X, top + FMT_Y, FMT_W, FMT_H, "R", inside(mouseX, mouseY,
                left + R_X, top + FMT_Y, FMT_W, FMT_H), true);
    }

    private void drawEditor() {
        int x = left + EDITOR_X;
        int y = top + EDITOR_Y;
        // 聚焦时高亮边框，未聚焦时是普通边框——玩家一眼能看出现在按键会被谁接收
        drawRect(x - 1, y - 1, x + EDITOR_W + 1, y + EDITOR_H + 1,
                editor.isFocused() ? C_ACCENT : C_EDITOR_BORDER);
        drawRect(x, y, x + EDITOR_W, y + EDITOR_H, C_EDITOR);
        editor.draw();
    }

    private void drawStatus() {
        String text = statusTicks > 0 ? statusText : defaultStatus();
        int color = statusTicks > 0 ? statusColor : C_MUTED;
        fontRendererObj.drawString(trim(text, SAVE_X - 20), left + 12, top + STATUS_Y, color);
    }

    private String defaultStatus() {
        if (stack == null) {
            return translate("gui.inscription_table.need_item");
        }
        if (!editor.isFocused()) {
            return translate("gui.inscription_table.focus_hint");
        }
        return translate("gui.inscription_table.hint_status");
    }

    private void drawButtons() {
        boolean canSave = stack != null;
        boolean canClear = editor.isEnabled();
        drawButton(left + SAVE_X, top + BUTTON_Y, SAVE_W, BUTTON_H,
                translate("gui.inscription_table.save"), canSave,
                inside(mouseX, mouseY, left + SAVE_X, top + BUTTON_Y, SAVE_W, BUTTON_H));
        drawButton(left + CLEAR_X, top + BUTTON_Y, CLEAR_W, BUTTON_H,
                translate("gui.inscription_table.clear"), canClear,
                inside(mouseX, mouseY, left + CLEAR_X, top + BUTTON_Y, CLEAR_W, BUTTON_H));
    }

    private void drawButton(int x, int y, int w, int h, String label, boolean enabled, boolean hover) {
        int fill = !enabled ? C_BUTTON_DISABLED : (hover ? C_BUTTON_HOVER : C_BUTTON);
        drawRect(x, y, x + w, y + h, fill);
        int textColor = enabled ? C_BUTTON_TEXT : C_BUTTON_TEXT_DISABLED;
        fontRendererObj.drawString(label, x + (w - fontRendererObj.getStringWidth(label)) / 2,
                y + (h - 8) / 2, textColor);
    }

    private void drawSmallButton(int x, int y, int w, int h, String label, boolean hover, boolean enabled) {
        drawRect(x, y, x + w, y + h, enabled ? (hover ? C_BUTTON_HOVER : 0xFF5A6373) : C_BUTTON_DISABLED);
        int textColor = enabled ? C_BUTTON_TEXT : C_BUTTON_TEXT_DISABLED;
        fontRendererObj.drawString(label, x + (w - fontRendererObj.getStringWidth(label)) / 2,
                y + (h - 8) / 2, textColor);
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // ESC：先让 RGB 输入框失焦，再关闭界面
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (rgbField.isFocused()) {
                rgbField.setFocused(false);
                return;
            }
            mc.displayGuiScreen(null);
            return;
        }
        // 「背包键」关闭界面：只在没有任何输入框接收按键时才生效。
        // RGB 框里 e 是合法的十六进制字符，编辑器聚焦时 e 也是正文，都不能被抢走。
        int invKey = mc.gameSettings.keyBindInventory.getKeyCode();
        if (invKey > 0 && keyCode == invKey && !GuiScreen.isCtrlKeyDown()
                && !rgbField.isFocused() && !editor.isFocused()) {
            mc.displayGuiScreen(null);
            return;
        }

        if (keyCode == Keyboard.KEY_TAB) {
            rgbField.setFocused(!rgbField.isFocused());
            if (rgbField.isFocused()) {
                editor.setFocused(false);
            }
            return;
        }
        if (GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_S) {
            save();
            return;
        }

        if (rgbField.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                applyRgb();
                return;
            }
            rgbField.textboxKeyTyped(typedChar, keyCode);
            return;
        }

        if (editor.keyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleKeyboardInput() {
        // 1.7.10 的 GuiScreen 只在「按键按下」时把字符转给 keyTyped。
        // 输入法提交出来的字常常挂在没有物理按键的抬起事件上（key = KEY_NONE），
        // 原版会直接丢掉，于是只留下第一个字。这里把落单的可打印字符补回来。
        if (!Keyboard.getEventKeyState()) {
            char typed = Keyboard.getEventCharacter();
            if (typed >= 0x80 && typed != 0x7F && Keyboard.getEventKey() == Keyboard.KEY_NONE
                    && editor.isFocused() && !rgbField.isFocused()) {
                editor.insertText(String.valueOf(typed));
            }
        }
        super.handleKeyboardInput();
    }

    /**
     * 每 tick 同步「谁在接收输入法」：
     * 编辑器可用且 RGB 输入框没被聚焦时，把输入法控制权交给编辑器；
     * 否则交还（RGB 框是原版 {@link GuiTextField}，IngameIME 自己会接管它）。
     */
    private void syncImeFocus() {
        boolean active = mc.currentScreen == this && editor.isEnabled() && editor.isFocused()
                && !rgbField.isFocused();
        if (active) {
            IngameImeSupport.setCaret(editor.getCaretScreenX(), editor.getCaretScreenY());
        }
        IngameImeSupport.setFocused(active);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;

        // 点到编辑区之外就退出编辑状态，之后按背包键可以直接关闭界面
        if (!inside(mouseX, mouseY, left + EDITOR_X, top + EDITOR_Y, EDITOR_W, EDITOR_H)) {
            editor.setFocused(false);
        }

        if (inside(mouseX, mouseY, left + SLOT_X, top + SLOT_Y, SLOT_SIZE, SLOT_SIZE)) {
            // 原本没物品 = 玩家刚把物品放进去，等回包到达后顺手进入编辑状态；取回则保持只读
            focusOnNextItem = !editor.isEnabled();
            sendAction(PacketAction.SLOT_CLICK);
            rgbField.setFocused(false);
            editor.setFocused(false);
            return;
        }

        for (int i = 0; i < SWATCH_COLORS.length; i++) {
            int x = left + 12 + (i % 8) * SWATCH_PITCH;
            int y = top + (i < 8 ? TOOLBAR_ROW1 : TOOLBAR_ROW2);
            if (inside(mouseX, mouseY, x, y, SWATCH_SIZE, SWATCH_SIZE)) {
                applyCode("&" + LEGACY_CODES.charAt(i));
                return;
            }
        }

        if (inside(mouseX, mouseY, left + APPLY_X, top + APPLY_Y, APPLY_W, APPLY_H)) {
            applyRgb();
            return;
        }
        if (inside(mouseX, mouseY, left + B_X, top + FMT_Y, FMT_W, FMT_H)) {
            applyCode("&l");
            return;
        }
        if (inside(mouseX, mouseY, left + I_X, top + FMT_Y, FMT_W, FMT_H)) {
            applyCode("&o");
            return;
        }
        if (inside(mouseX, mouseY, left + U_X, top + FMT_Y, FMT_W, FMT_H)) {
            applyCode("&n");
            return;
        }
        if (inside(mouseX, mouseY, left + R_X, top + FMT_Y, FMT_W, FMT_H)) {
            applyCode("&r");
            return;
        }

        if (inside(mouseX, mouseY, left + SAVE_X, top + BUTTON_Y, SAVE_W, BUTTON_H)) {
            save();
            return;
        }
        if (inside(mouseX, mouseY, left + CLEAR_X, top + BUTTON_Y, CLEAR_W, BUTTON_H)) {
            clearEditor();
            return;
        }

        if (inside(mouseX, mouseY, left + RGB_X, top + RGB_Y, RGB_W, RGB_H)) {
            rgbField.mouseClicked(mouseX, mouseY, button);
            return;
        }

        if (inside(mouseX, mouseY, left + EDITOR_X, top + EDITOR_Y, EDITOR_W, EDITOR_H)) {
            rgbField.setFocused(false);
            editor.mouseClicked(mouseX, mouseY, button);
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        editor.mouseDragged(mouseX, mouseY);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {
        super.mouseMovedOrUp(mouseX, mouseY, button);
        if (button >= 0) {
            editor.mouseReleased();
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (inside(mouseX, mouseY, left + EDITOR_X, top + EDITOR_Y, EDITOR_W, EDITOR_H)) {
            editor.scroll(wheel > 0 ? -1 : 1);
        }
    }

    // ------------------------------------------------------------------
    // 操作
    // ------------------------------------------------------------------

    private void applyCode(String code) {
        if (!editor.isEnabled()) {
            setStatus(translate("gui.inscription_table.need_item"), C_FAIL);
            return;
        }
        editor.setFocused(true);
        editor.applyCode(code, "&r");
        rgbField.setFocused(false);
    }

    private void applyRgb() {
        if (!editor.isEnabled()) {
            setStatus(translate("gui.inscription_table.need_item"), C_FAIL);
            return;
        }
        String raw = rgbField.getText() == null ? "" : rgbField.getText().trim();
        if (raw.startsWith("#")) {
            raw = raw.substring(1);
        }
        if (!isHex6(raw)) {
            setStatus(translate("gui.inscription_table.rgb_invalid"), C_FAIL);
            return;
        }
        editor.setFocused(true);
        editor.applyCode("&#" + raw.toUpperCase(), "&r");
        rgbField.setFocused(false);
        setStatus(translate("gui.inscription_table.rgb_applied") + " #" + raw.toUpperCase(), C_MUTED);
        rgbField.setFocused(false);
    }

    private static boolean isHex6(String text) {
        if (text.length() != 6) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            if (Character.digit(text.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private void save() {
        if (stack == null) {
            setStatus(translate("gui.inscription_table.noitem"), C_FAIL);
            return;
        }
        sendAction(PacketAction.SAVE, editor.getText());
    }

    private void clearEditor() {
        if (!editor.isEnabled()) {
            return;
        }
        editor.setText("");
        setStatus(translate("gui.inscription_table.editor_cleared"), C_MUTED);
    }

    private void sendAction(byte action) {
        sendAction(action, "");
    }

    private void sendAction(byte action, String text) {
        if (table == null) {
            return;
        }
        ModNetwork.sendToServer(new PacketAction(action, table.xCoord, table.yCoord, table.zCoord, text));
    }

    private void setStatus(String text, int color) {
        statusText = text;
        statusColor = color;
        statusTicks = 60;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static boolean inside(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private String trim(String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        return fontRendererObj.trimStringToWidth(text, Math.max(8, maxWidth));
    }

    private static String translate(String key) {
        return StatCollector.translateToLocal(key);
    }
}
