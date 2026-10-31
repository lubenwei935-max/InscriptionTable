package com.example.inscriptiontable.gui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.lwjgl.input.Keyboard;

import com.example.inscriptiontable.InscriptionConfig;
import com.example.inscriptiontable.util.LoreParser;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.StatCollector;

/**
 * 极简文本编辑器（原版 FontRenderer 手绘，不依赖任何 UI 库）。
 *
 * <h3>支持</h3>
 * 多行、软换行、光标/选区、点击与拖选、Home/End、PageUp/PageDown、
 * Ctrl+A/C/X/V/Z/Y、Delete/Backspace、回车换行。
 *
 * <h3>所见即所得</h3>
 * 直接解析 {@code &0}~{@code &f}、{@code &#RRGGBB}、{@code &l/&o/&n/&r}：
 * 颜色即时生效（含任意 RGB），加粗与下划线也按效果绘制；
 * 代码本身用灰色显示，方便定位与修改；斜体在编辑区内保留代码、由最终 tooltip 呈现。
 *
 * <h3>为什么底色是深的</h3>
 * 铭文默认是白字，画在浅色底上会看不见；深色编辑区可以做到和最终 tooltip 近似一致的配色。
 */
@SideOnly(Side.CLIENT)
public class LoreEditor {

    private static final int ROW_H = 10;
    private static final int PAD = 3;

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_CODE = 0xFF8A94A6;
    private static final int COLOR_SELECTION = 0x664C8DFF;
    private static final int COLOR_CARET = 0xFFFFFFFF;
    private static final int COLOR_COUNTER = 0xFF8A94A6;
    private static final int COLOR_DISABLED = 0xFF8A94A6;

    private static final int MAX_UNDO = 200;

    private final FontRenderer font;
    private final List<String> lines = new ArrayList<String>();
    private final List<Row> rows = new ArrayList<Row>();
    private final Deque<Snapshot> undoStack = new ArrayDeque<Snapshot>();
    private final Deque<Snapshot> redoStack = new ArrayDeque<Snapshot>();

    private int x;
    private int y;
    private int width;
    private int height;

    private int caretLine;
    private int caretCol;
    private int anchorLine = -1;
    private int anchorCol = -1;

    private boolean enabled = true;
    private boolean focused;
    private boolean dragging;
    private int scrollRow;
    private int ticks;
    private int preferredX = -1;
    private boolean layoutDirty = true;
    private List<Style[]> styleCache;

    public LoreEditor(FontRenderer font) {
        this.font = font;
        lines.add("");
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.layoutDirty = true;
    }

    // ------------------------------------------------------------------
    // 对外状态
    // ------------------------------------------------------------------

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            clearSelection();
            dragging = false;
            focused = false;
        }
    }

    /**
     * 是否处于编辑状态。
     * <p>
     * 未聚焦时编辑器只读：不接收按键、不画光标。界面据此把「背包键」让给关闭界面，
     * 否则玩家永远打不出字母 e。
     */
    public void setFocused(boolean focused) {
        this.focused = focused && enabled;
        if (!this.focused) {
            dragging = false;
        }
    }

    public boolean isFocused() {
        return focused;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    public void setText(String text) {
        lines.clear();
        String[] parts = (text == null ? "" : text).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String part : parts) {
            lines.add(part);
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        caretLine = 0;
        caretCol = 0;
        clearSelection();
        scrollRow = 0;
        preferredX = -1;
        undoStack.clear();
        redoStack.clear();
        invalidate();
    }

    public int getLength() {
        return getText().length();
    }

    /** 每 tick 调一次：光标闪烁 */
    public void tick() {
        ++ticks;
    }

    /** 光标在界面坐标系里的 X（输入法的预编辑/候选窗就贴在这个点上） */
    public int getCaretScreenX() {
        ensureLayout();
        int raw = xFor(rows.get(caretRowIndex()), caretCol);
        return Math.max(x + 1, Math.min(raw, x + width - 1));
    }

    /** 光标在界面坐标系里的 Y（被滚出可视区时钳制在编辑区内） */
    public int getCaretScreenY() {
        ensureLayout();
        int raw = rowY(caretRowIndex());
        int top = y + 1;
        int bottom = Math.max(top, y + height - font.FONT_HEIGHT - 1);
        return Math.max(top, Math.min(raw, bottom));
    }

    private int caretRowIndex() {
        ensureLayout();
        int result = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.line == caretLine && caretCol >= row.start && caretCol <= row.end) {
                // 软换行处同一个 col 属于两行，取后一行：光标应该出现在下一行行首
                result = i;
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    public void draw() {
        ensureLayout();
        if (!enabled) {
            String hint = StatCollector.translateToLocal("gui.inscription_table.need_item");
            font.drawString(hint, x + (width - font.getStringWidth(hint)) / 2,
                    y + height / 2 - 4, COLOR_DISABLED);
            return;
        }

        int visible = visibleRows();
        int first = scrollRow;
        int last = Math.min(rows.size(), scrollRow + visible);
        for (int i = first; i < last; i++) {
            Row row = rows.get(i);
            int rowY = rowY(i);
            drawSelection(row, rowY);
            drawRowText(row, rowY);
        }
        drawCaret();
        drawCounter();
    }

    private void drawRowText(Row row, int rowY) {
        String text = lines.get(row.line);
        Style[] styles = stylesFor(row.line);
        int i = row.start;
        while (i < row.end) {
            int codeLength = LoreParser.codeLength(text, i);
            if (codeLength > 0 && i + codeLength <= text.length()) {
                font.drawString(text.substring(i, i + codeLength), xFor(row, i), rowY, COLOR_CODE);
                i += codeLength;
                continue;
            }
            Style style = styles[Math.min(i, styles.length - 1)];
            int j = i + 1;
            while (j < row.end && LoreParser.codeLength(text, j) == 0
                    && styles[Math.min(j, styles.length - 1)].same(style)) {
                j++;
            }
            drawRun(text.substring(i, j), xFor(row, i), rowY, style);
            i = j;
        }
    }

    private void drawRun(String text, int drawX, int rowY, Style style) {
        int color = style.color;
        font.drawString(text, drawX, rowY, color);
        if (style.bold) {
            font.drawString(text, drawX + 1, rowY, color);
        }
        if (style.underline) {
            int width = font.getStringWidth(text);
            Gui.drawRect(drawX, rowY + font.FONT_HEIGHT, drawX + width, rowY + font.FONT_HEIGHT + 1, color);
        }
    }

    private void drawSelection(Row row, int rowY) {
        if (!hasSelection()) {
            return;
        }
        int[] range = selectionRange();
        if (row.line < range[0] || row.line > range[2]) {
            return;
        }
        int from = row.line == range[0] ? Math.max(row.start, range[1]) : row.start;
        int to = row.line == range[2] ? Math.min(row.end, range[3]) : row.end;
        from = Math.max(from, row.start);
        to = Math.min(to, row.end);
        if (from >= to) {
            return;
        }
        Gui.drawRect(xFor(row, from), rowY - 1, xFor(row, to), rowY + font.FONT_HEIGHT + 1, COLOR_SELECTION);
    }

    private void drawCaret() {
        if (!enabled || !focused || (ticks / 10) % 2 == 0) {
            return;
        }
        int rowIndex = rowIndexFor(caretLine, caretCol);
        if (rowIndex < scrollRow || rowIndex >= scrollRow + visibleRows()) {
            return;
        }
        Row row = rows.get(rowIndex);
        int caretX = xFor(row, caretCol);
        Gui.drawRect(caretX, rowY(rowIndex), caretX + 1, rowY(rowIndex) + font.FONT_HEIGHT, COLOR_CARET);
    }

    private void drawCounter() {
        int max = Math.max(1, InscriptionConfig.maxLoreLength);
        String counter = getLength() + "/" + max;
        int color = getLength() >= max ? 0xFFFF7A7A : COLOR_COUNTER;
        font.drawString(counter, x + width - font.getStringWidth(counter) - 1,
                y + height - font.FONT_HEIGHT, color);
    }

    private int rowY(int rowIndex) {
        return y + PAD + (rowIndex - scrollRow) * ROW_H;
    }

    private int xFor(Row row, int col) {
        return x + PAD + widthOf(lines.get(row.line), row.start, Math.min(col, lines.get(row.line).length()));
    }

    // ------------------------------------------------------------------
    // 排版
    // ------------------------------------------------------------------

    private int visibleRows() {
        return Math.max(1, (height - PAD * 2 - font.FONT_HEIGHT) / ROW_H);
    }

    private void ensureLayout() {
        if (!layoutDirty) {
            return;
        }
        layoutDirty = false;
        rows.clear();
        int innerWidth = Math.max(8, width - PAD * 2);

        for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            String text = lines.get(lineIndex);
            int length = text.length();
            int start = 0;
            do {
                int col = start;
                int rowWidth = 0;
                int lastSpace = -1;
                while (col < length) {
                    int charWidth = charWidth(text.charAt(col));
                    if (rowWidth + charWidth > innerWidth && col > start) {
                        break;
                    }
                    if (text.charAt(col) == ' ' || text.charAt(col) == '\u3000') {
                        lastSpace = col;
                    }
                    rowWidth += charWidth;
                    col++;
                }
                if (col < length && lastSpace >= start) {
                    col = lastSpace + 1;
                }
                if (col == start) {
                    col = Math.min(length, start + 1);
                }
                rows.add(new Row(lineIndex, start, col));
                start = col;
            } while (start < length);
        }
    }

    private Style[] stylesFor(int lineIndex) {
        if (styleCache == null) {
            styleCache = new ArrayList<Style[]>();
        }
        while (styleCache.size() <= lineIndex) {
            styleCache.add(null);
        }
        Style[] cached = styleCache.get(lineIndex);
        if (cached != null) {
            return cached;
        }

        String text = lines.get(lineIndex);
        Style[] result = new Style[text.length() + 1];
        Style current = new Style();
        int i = 0;
        while (i < text.length()) {
            result[i] = current.copy();
            int codeLength = LoreParser.codeLength(text, i);
            if (codeLength > 0) {
                applyCode(text, i, codeLength, current);
                for (int k = 1; k < codeLength; k++) {
                    result[i + k] = current.copy();
                }
                i += codeLength;
                continue;
            }
            i++;
        }
        result[text.length()] = current.copy();
        styleCache.set(lineIndex, result);
        return result;
    }

    private static void applyCode(String text, int index, int codeLength, Style style) {
        char next = Character.toLowerCase(text.charAt(index + 1));
        if (next == '#' && codeLength == 8) {
            try {
                style.color = 0xFF000000 | Integer.parseInt(text.substring(index + 2, index + 8), 16);
            } catch (NumberFormatException ignored) {
                // codeLength 已校验过十六进制，这里理论上不会发生
            }
            style.bold = false;
            style.italic = false;
            style.underline = false;
            return;
        }
        if (LoreParser.isLegacyColorCode(next)) {
            style.color = 0xFF000000 | LoreParser.legacyColor(next);
            style.bold = false;
            style.italic = false;
            style.underline = false;
            return;
        }
        switch (next) {
            case 'l':
                style.bold = true;
                break;
            case 'o':
                style.italic = true;
                break;
            case 'n':
                style.underline = true;
                break;
            case 'r':
                style.reset();
                break;
            default:
                // 'm'/'k' 等没有可视效果，忽略
                break;
        }
    }

    private void invalidate() {
        layoutDirty = true;
        styleCache = null;
    }

    // ------------------------------------------------------------------
    // 光标 / 选区
    // ------------------------------------------------------------------

    private int[] selectionRange() {
        if (anchorLine < 0) {
            return null;
        }
        int line1 = anchorLine;
        int col1 = anchorCol;
        int line2 = caretLine;
        int col2 = caretCol;
        if (line1 > line2 || (line1 == line2 && col1 > col2)) {
            int tmpLine = line1;
            int tmpCol = col1;
            line1 = line2;
            col1 = col2;
            line2 = tmpLine;
            col2 = tmpCol;
        }
        return new int[] { line1, col1, line2, col2 };
    }

    private boolean hasSelection() {
        return anchorLine >= 0 && (anchorLine != caretLine || anchorCol != caretCol);
    }

    private void clearSelection() {
        anchorLine = -1;
        anchorCol = -1;
    }

    private void setCaret(int line, int col) {
        caretLine = Math.max(0, Math.min(line, lines.size() - 1));
        String text = lines.get(caretLine);
        caretCol = Math.max(0, Math.min(col, text.length()));
    }

    private int rowIndexFor(int line, int col) {
        ensureLayout();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.line == line && col >= row.start && col <= row.end) {
                return i;
            }
        }
        return Math.max(0, rows.size() - 1);
    }

    private void ensureCaretVisible() {
        int rowIndex = rowIndexFor(caretLine, caretCol);
        int visible = visibleRows();
        if (rowIndex < scrollRow) {
            scrollRow = rowIndex;
        } else if (rowIndex >= scrollRow + visible) {
            scrollRow = rowIndex - visible + 1;
        }
        scrollRow = Math.max(0, Math.min(scrollRow, Math.max(0, rows.size() - visible)));
    }

    // ------------------------------------------------------------------
    // 鼠标
    // ------------------------------------------------------------------

    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (!enabled || button != 0) {
            return false;
        }
        if (mouseX < x || mouseX >= x + width || mouseY < y || mouseY >= y + height) {
            return false;
        }
        focused = true;
        ensureLayout();
        if (GuiScreen.isShiftKeyDown()) {
            if (anchorLine < 0) {
                anchorLine = caretLine;
                anchorCol = caretCol;
            }
        } else {
            clearSelection();
        }
        int[] pos = positionAt(mouseX, mouseY);
        setCaret(pos[0], pos[1]);
        dragging = true;
        preferredX = -1;
        ensureCaretVisible();
        return true;
    }

    public void mouseDragged(int mouseX, int mouseY) {
        if (!dragging || !enabled) {
            return;
        }
        int[] pos = positionAt(mouseX, mouseY);
        setCaret(pos[0], pos[1]);
        if (anchorLine < 0) {
            anchorLine = pos[0];
            anchorCol = pos[1];
        }
        ensureCaretVisible();
    }

    public void mouseReleased() {
        dragging = false;
    }

    public void scroll(int direction) {
        if (!enabled) {
            return;
        }
        ensureLayout();
        int visible = visibleRows();
        scrollRow = Math.max(0, Math.min(scrollRow + direction, Math.max(0, rows.size() - visible)));
    }

    private int[] positionAt(int mouseX, int mouseY) {
        ensureLayout();
        int visible = visibleRows();
        int rowIndex = (mouseY - (y + PAD)) / ROW_H + scrollRow;
        rowIndex = Math.max(0, Math.min(rowIndex, rows.size() - 1));
        Row row = rows.get(rowIndex);
        String text = lines.get(row.line);

        int col = row.start;
        int cursorX = x + PAD;
        while (col < row.end) {
            int charWidth = charWidth(text.charAt(col));
            if (mouseX < cursorX + charWidth / 2) {
                break;
            }
            cursorX += charWidth;
            col++;
        }
        return new int[] { row.line, Math.min(col, row.end) };
    }

    // ------------------------------------------------------------------
    // 键盘
    // ------------------------------------------------------------------

    public boolean keyTyped(char typedChar, int keyCode) {
        if (!enabled || !focused) {
            return false;
        }
        boolean ctrl = GuiScreen.isCtrlKeyDown();
        boolean shift = GuiScreen.isShiftKeyDown();

        // 可打印字符一律当文本处理，而且必须排在按键分支前面。
        // 输入法上屏时 keyCode 可能是 0（VK_PACKET），也可能沿用「确认候选词」的那个物理键
        // （回车/退格/空格）——先走按键分支的话，整串字符里的第一个会被变成换行或被删掉，
        // 看起来就像「一次只能输入一个字」。
        if (!ctrl && typedChar >= 0x20 && typedChar != 0x7F) {
            insertText(String.valueOf(typedChar));
            return true;
        }

        switch (keyCode) {
            case Keyboard.KEY_LEFT:
                moveHorizontal(-1, shift);
                return true;
            case Keyboard.KEY_RIGHT:
                moveHorizontal(1, shift);
                return true;
            case Keyboard.KEY_UP:
                moveVertical(-1, shift);
                return true;
            case Keyboard.KEY_DOWN:
                moveVertical(1, shift);
                return true;
            case Keyboard.KEY_HOME:
                moveLineEdge(true, ctrl, shift);
                return true;
            case Keyboard.KEY_END:
                moveLineEdge(false, ctrl, shift);
                return true;
            case Keyboard.KEY_PRIOR:
                page(-1, shift);
                return true;
            case Keyboard.KEY_NEXT:
                page(1, shift);
                return true;
            case Keyboard.KEY_BACK:
                backspace();
                return true;
            case Keyboard.KEY_DELETE:
                deleteForward();
                return true;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
                insertText("\n");
                return true;
            case Keyboard.KEY_TAB:
                return false;
            default:
                break;
        }

        if (ctrl) {
            switch (keyCode) {
                case Keyboard.KEY_A:
                    selectAll();
                    return true;
                case Keyboard.KEY_C:
                    copySelection();
                    return true;
                case Keyboard.KEY_X:
                    cutSelection();
                    return true;
                case Keyboard.KEY_V:
                    paste();
                    return true;
                case Keyboard.KEY_Z:
                    undo();
                    return true;
                case Keyboard.KEY_Y:
                    redo();
                    return true;
                default:
                    return false;
            }
        }

        return false;
    }

    private void moveHorizontal(int direction, boolean shift) {
        if (!shift && hasSelection()) {
            int[] range = selectionRange();
            setCaret(direction < 0 ? range[0] : range[2], direction < 0 ? range[1] : range[3]);
            clearSelection();
            preferredX = -1;
            ensureCaretVisible();
            return;
        }
        if (shift && anchorLine < 0) {
            anchorLine = caretLine;
            anchorCol = caretCol;
        }
        if (direction < 0) {
            if (caretCol > 0) {
                caretCol--;
            } else if (caretLine > 0) {
                caretLine--;
                caretCol = lines.get(caretLine).length();
            }
        } else {
            if (caretCol < lines.get(caretLine).length()) {
                caretCol++;
            } else if (caretLine < lines.size() - 1) {
                caretLine++;
                caretCol = 0;
            }
        }
        if (!shift) {
            clearSelection();
        }
        preferredX = -1;
        ensureCaretVisible();
    }

    private void moveVertical(int direction, boolean shift) {
        if (!shift && hasSelection()) {
            int[] range = selectionRange();
            setCaret(direction < 0 ? range[0] : range[2], direction < 0 ? range[1] : range[3]);
            clearSelection();
        }
        if (shift && anchorLine < 0) {
            anchorLine = caretLine;
            anchorCol = caretCol;
        }
        ensureLayout();
        int rowIndex = rowIndexFor(caretLine, caretCol);
        if (preferredX < 0) {
            Row current = rows.get(rowIndex);
            preferredX = xFor(current, caretCol) - x - PAD;
        }
        int target = Math.max(0, Math.min(rowIndex + direction, rows.size() - 1));
        Row row = rows.get(target);
        caretLine = row.line;
        caretCol = columnAt(row, preferredX);
        if (!shift) {
            clearSelection();
        }
        ensureCaretVisible();
    }

    private void moveLineEdge(boolean toStart, boolean wholeText, boolean shift) {
        if (shift && anchorLine < 0) {
            anchorLine = caretLine;
            anchorCol = caretCol;
        }
        if (wholeText) {
            setCaret(toStart ? 0 : lines.size() - 1,
                    toStart ? 0 : lines.get(lines.size() - 1).length());
        } else {
            setCaret(caretLine, toStart ? 0 : lines.get(caretLine).length());
        }
        if (!shift) {
            clearSelection();
        }
        preferredX = -1;
        ensureCaretVisible();
    }

    private void page(int direction, boolean shift) {
        if (shift && anchorLine < 0) {
            anchorLine = caretLine;
            anchorCol = caretCol;
        }
        ensureLayout();
        int rowIndex = rowIndexFor(caretLine, caretCol);
        int visible = visibleRows();
        int target = Math.max(0, Math.min(rowIndex + direction * (visible - 1), rows.size() - 1));
        Row row = rows.get(target);
        caretLine = row.line;
        caretCol = columnAt(row, preferredX < 0 ? xFor(rows.get(rowIndex), caretCol) - x - PAD : preferredX);
        if (!shift) {
            clearSelection();
        }
        ensureCaretVisible();
        scrollRow = Math.max(0, Math.min(scrollRow + direction * visible, Math.max(0, rows.size() - visible)));
    }

    private int columnAt(Row row, int offsetX) {
        String text = lines.get(row.line);
        int col = row.start;
        int cursorX = 0;
        while (col < row.end) {
            int charWidth = charWidth(text.charAt(col));
            if (offsetX < cursorX + charWidth / 2) {
                break;
            }
            cursorX += charWidth;
            col++;
        }
        return Math.min(col, row.end);
    }

    private void selectAll() {
        anchorLine = 0;
        anchorCol = 0;
        setCaret(lines.size() - 1, lines.get(lines.size() - 1).length());
        ensureCaretVisible();
    }

    // ------------------------------------------------------------------
    // 编辑
    // ------------------------------------------------------------------

    public void insertText(String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return;
        }
        StringBuilder clean = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                continue;
            }
            if (c == '\n' || (c >= 0x20 && c != 0x7F)) {
                clean.append(c);
            }
        }
        if (clean.length() == 0) {
            return;
        }

        int max = Math.max(1, InscriptionConfig.maxLoreLength);
        int currentLength = getLength() - selectionLength();
        int remaining = max - currentLength;
        if (remaining <= 0) {
            return;
        }
        String toInsert = clean.length() > remaining ? clean.substring(0, remaining) : clean.toString();

        // 行数上限：选区会先被删除，所以可用行数按「删掉选区之后」的行数算
        int maxLines = Math.max(1, InscriptionConfig.maxLoreLines);
        int baseLines = lines.size();
        if (hasSelection()) {
            int[] range = selectionRange();
            baseLines -= range[2] - range[0];
        }
        toInsert = limitBreaks(toInsert, Math.max(0, maxLines - baseLines));
        if (toInsert.length() == 0) {
            return;
        }

        pushUndo();
        deleteSelectionInternal();

        int lineIndex = caretLine;
        String line = lines.get(lineIndex);
        String before = line.substring(0, caretCol);
        String after = line.substring(caretCol);
        String[] parts = toInsert.split("\n", -1);

        if (parts.length == 1) {
            lines.set(lineIndex, before + toInsert + after);
            caretCol = before.length() + toInsert.length();
        } else {
            lines.set(lineIndex, before + parts[0]);
            for (int i = 1; i < parts.length - 1; i++) {
                lines.add(lineIndex + i, parts[i]);
            }
            lines.add(lineIndex + parts.length - 1, parts[parts.length - 1] + after);
            caretLine = lineIndex + parts.length - 1;
            caretCol = parts[parts.length - 1].length();
        }
        afterEdit();
    }

    /** 最多保留 room 个换行，超出部分连同后面的文本一起丢弃 */
    private static String limitBreaks(String text, int room) {
        int breaks = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && ++breaks > room) {
                return text.substring(0, i);
            }
        }
        return text;
    }

    /**
     * 应用颜色/格式代码：
     * 没有选区时插在光标处；有选区时在选区首尾各插一个（尾部用 resetCode 复位），
     * 被包裹的文字保持选中，方便连续调整。
     */
    public void applyCode(String code, String resetCode) {
        if (!enabled || code == null || code.length() == 0) {
            return;
        }
        if (!hasSelection()) {
            insertText(code);
            return;
        }
        pushUndo();
        int[] range = selectionRange();
        if (resetCode != null && resetCode.length() > 0) {
            insertRaw(range[2], range[3], resetCode);
        }
        insertRaw(range[0], range[1], code);

        anchorLine = range[0];
        anchorCol = range[1] + code.length();
        int endCol = range[3] + (range[0] == range[2] ? code.length() : 0);
        setCaret(range[2], endCol);
        afterEdit();
    }

    private void insertRaw(int line, int col, String text) {
        if (text == null || text.length() == 0) {
            return;
        }
        String source = lines.get(line);
        lines.set(line, source.substring(0, col) + text + source.substring(col));
    }

    private void backspace() {
        if (!enabled) {
            return;
        }
        if (hasSelection()) {
            pushUndo();
            deleteSelectionInternal();
            afterEdit();
            return;
        }
        if (caretCol > 0) {
            pushUndo();
            String line = lines.get(caretLine);
            lines.set(caretLine, line.substring(0, caretCol - 1) + line.substring(caretCol));
            caretCol--;
        } else if (caretLine > 0) {
            pushUndo();
            String previous = lines.get(caretLine - 1);
            String current = lines.get(caretLine);
            lines.set(caretLine - 1, previous + current);
            lines.remove(caretLine);
            caretLine--;
            caretCol = previous.length();
        } else {
            return;
        }
        afterEdit();
    }

    private void deleteForward() {
        if (!enabled) {
            return;
        }
        if (hasSelection()) {
            pushUndo();
            deleteSelectionInternal();
            afterEdit();
            return;
        }
        String line = lines.get(caretLine);
        if (caretCol < line.length()) {
            pushUndo();
            lines.set(caretLine, line.substring(0, caretCol) + line.substring(caretCol + 1));
        } else if (caretLine < lines.size() - 1) {
            pushUndo();
            lines.set(caretLine, line + lines.get(caretLine + 1));
            lines.remove(caretLine + 1);
        } else {
            return;
        }
        afterEdit();
    }

    private void deleteSelectionInternal() {
        if (!hasSelection()) {
            return;
        }
        int[] range = selectionRange();
        int line1 = range[0];
        int col1 = range[1];
        int line2 = range[2];
        int col2 = range[3];
        if (line1 == line2) {
            String text = lines.get(line1);
            lines.set(line1, text.substring(0, col1) + text.substring(col2));
        } else {
            String merged = lines.get(line1).substring(0, col1) + lines.get(line2).substring(col2);
            for (int i = line2; i > line1; i--) {
                lines.remove(i);
            }
            lines.set(line1, merged);
        }
        caretLine = line1;
        caretCol = col1;
        clearSelection();
        invalidate();
    }

    private void afterEdit() {
        invalidate();
        preferredX = -1;
        ensureLayout();
        ensureCaretVisible();
    }

    private void pushUndo() {
        undoStack.addLast(new Snapshot(getText(), caretLine, caretCol));
        if (undoStack.size() > MAX_UNDO) {
            undoStack.removeFirst();
        }
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            return;
        }
        Snapshot snapshot = undoStack.removeLast();
        redoStack.addLast(new Snapshot(getText(), caretLine, caretCol));
        applySnapshot(snapshot);
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            return;
        }
        Snapshot snapshot = redoStack.removeLast();
        undoStack.addLast(new Snapshot(getText(), caretLine, caretCol));
        applySnapshot(snapshot);
    }

    private void applySnapshot(Snapshot snapshot) {
        lines.clear();
        String[] parts = snapshot.text.split("\n", -1);
        for (String part : parts) {
            lines.add(part);
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        caretLine = Math.max(0, Math.min(snapshot.line, lines.size() - 1));
        caretCol = Math.max(0, Math.min(snapshot.col, lines.get(caretLine).length()));
        clearSelection();
        invalidate();
        ensureLayout();
        ensureCaretVisible();
    }

    // ------------------------------------------------------------------
    // 剪贴板
    // ------------------------------------------------------------------

    private void copySelection() {
        String selected = getSelectedText();
        if (selected.length() > 0) {
            GuiScreen.setClipboardString(selected);
        }
    }

    private void cutSelection() {
        if (!hasSelection()) {
            return;
        }
        copySelection();
        pushUndo();
        deleteSelectionInternal();
        afterEdit();
    }

    private void paste() {
        String clipboard = GuiScreen.getClipboardString();
        if (clipboard != null && clipboard.length() > 0) {
            insertText(clipboard);
        }
    }

    /** 选区覆盖的字符数（含换行），用于计算还能输入多少 */
    private int selectionLength() {
        if (!hasSelection()) {
            return 0;
        }
        int[] range = selectionRange();
        if (range[0] == range[2]) {
            return range[3] - range[1];
        }
        int length = lines.get(range[0]).length() - range[1] + range[3];
        for (int i = range[0] + 1; i < range[2]; i++) {
            length += lines.get(i).length() + 1;
        }
        return length;
    }

    private String getSelectedText() {
        if (!hasSelection()) {
            return "";
        }
        int[] range = selectionRange();
        if (range[0] == range[2]) {
            return lines.get(range[0]).substring(range[1], range[3]);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(lines.get(range[0]).substring(range[1]));
        for (int i = range[0] + 1; i < range[2]; i++) {
            sb.append('\n').append(lines.get(i));
        }
        sb.append('\n').append(lines.get(range[2]).substring(0, range[3]));
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private int widthOf(String text, int from, int to) {
        int width = 0;
        int end = Math.min(to, text.length());
        for (int i = Math.max(0, from); i < end; i++) {
            width += charWidth(text.charAt(i));
        }
        return width;
    }

    private int charWidth(char c) {
        return font.getStringWidth(String.valueOf(c));
    }

    private static final class Row {

        final int line;
        final int start;
        final int end;

        Row(int line, int start, int end) {
            this.line = line;
            this.start = start;
            this.end = end;
        }
    }

    private static final class Style {

        int color = COLOR_TEXT;
        boolean bold;
        boolean italic;
        boolean underline;

        void reset() {
            color = COLOR_TEXT;
            bold = false;
            italic = false;
            underline = false;
        }

        boolean same(Style other) {
            return color == other.color && bold == other.bold && italic == other.italic
                    && underline == other.underline;
        }

        Style copy() {
            Style style = new Style();
            style.color = color;
            style.bold = bold;
            style.italic = italic;
            style.underline = underline;
            return style;
        }
    }

    private static final class Snapshot {

        final String text;
        final int line;
        final int col;

        Snapshot(String text, int line, int col) {
            this.text = text;
            this.line = line;
            this.col = col;
        }
    }
}
