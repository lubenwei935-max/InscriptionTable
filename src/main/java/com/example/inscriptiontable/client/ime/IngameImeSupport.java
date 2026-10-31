package com.example.inscriptiontable.client.ime;

import java.awt.Point;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import com.example.inscriptiontable.gui.LoreEditor;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 与「游戏内输入法」（IngameIME）的<strong>软兼容层</strong>。
 *
 * <h3>为什么需要它</h3>
 * IngameIME 只认识原版 {@code GuiTextField}：它通过 mixin 监听 {@code GuiTextField.setFocused}，
 * 把被聚焦的输入框包装成自己的 {@code IControl}，之后输入法提交的文本才会被写回游戏。
 * 本模组的编辑器是自绘的（没有 {@code GuiTextField}），所以在装了 IngameIME 时：
 * <ul>
 *   <li>输入法根本不会被激活（没有可用的 control），表现为「完全打不出中文」；</li>
 *   <li>即使手工打开输入法，提交的整串文本也没有接收方。</li>
 * </ul>
 *
 * <h3>做了什么</h3>
 * 这里不引入任何编译期依赖，全部走反射：
 * <ol>
 *   <li>用 {@link Proxy} 动态实现 {@code com.dhj.ingameime.control.IControl}；</li>
 *   <li>把自己的 control 通过 {@code ClientProxy#onControlFocus(IControl, boolean, boolean)}
 *       交给 IngameIME —— 这正是原版输入框被聚焦时走的同一个入口；</li>
 *   <li>输入法提交时 {@code writeText(String)} 会拿到<strong>完整的一串字符</strong>，
 *       先入队，再由界面在客户端 tick 里一次性插入编辑器。</li>
 * </ol>
 *
 * <h3>为什么不直接改 UI</h3>
 * {@code writeText} 由输入法自己的回调线程调用，直接改编辑器状态不是线程安全的；
 * 入队 + 客户端 tick 消费与现有的网络回包处理方式一致。
 *
 * <p>没装 IngameIME 时整个类不会触发任何反射调用，开销为零。</p>
 */
@SideOnly(Side.CLIENT)
public final class IngameImeSupport {

    /** mcmod.info 里登记的 modid */
    private static final String MOD_ID = "ingameime";
    private static final String CLASS_CONTROL = "com.dhj.ingameime.control.IControl";
    private static final String CLASS_PROXY = "com.dhj.ingameime.ClientProxy";
    private static final String METHOD_ON_CONTROL_FOCUS = "onControlFocus";

    /** 只解析一次：没装 / 解析失败都不再重试，避免每帧刷日志 */
    private static boolean resolved;
    private static boolean broken;

    private static Object clientProxy;
    private static Object control;
    private static Method onControlFocus;

    private static LoreEditor editor;
    private static boolean focused;
    private static int caretX;
    private static int caretY;

    /** 输入法线程写入、客户端 tick 取走 */
    private static final StringBuilder PENDING = new StringBuilder();
    private static boolean hasPending;

    private IngameImeSupport() {
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 界面打开时调用：绑定编辑器并尝试接入 IngameIME */
    public static void bind(LoreEditor target) {
        editor = target;
        focused = false;
        caretX = 0;
        caretY = 0;
        clearPending();
        ensure();
    }

    /** 界面关闭时调用：交还输入法控制权 */
    public static void unbind() {
        setFocused(false);
        editor = null;
        focused = false;
        clearPending();
    }

    /** 每 tick 更新光标位置：输入法的候选框就贴在这个点上 */
    public static void setCaret(int x, int y) {
        caretX = x;
        caretY = y;
    }

    /**
     * 让编辑器成为（或不再成为）输入法的目标输入框。
     * <p>
     * 多次调用同样状态不会重复触发，IngameIME 的状态机也就不会被反复激活。
     */
    public static void setFocused(boolean value) {
        if (focused == value) {
            return;
        }
        if (!ensure()) {
            focused = false;
            return;
        }
        focused = value;
        try {
            onControlFocus.invoke(clientProxy, control, Boolean.valueOf(value), Boolean.FALSE);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** 客户端 tick 调用：把输入法提交的文本一次性交给编辑器 */
    public static void drain(LoreEditor target) {
        if (target == null) {
            return;
        }
        String text;
        synchronized (PENDING) {
            if (!hasPending) {
                return;
            }
            text = PENDING.toString();
            PENDING.setLength(0);
            hasPending = false;
        }
        target.insertText(text);
    }

    /** 是否真的接上了 IngameIME（界面状态栏可以用来提示） */
    public static boolean isHooked() {
        return ensure();
    }

    // ------------------------------------------------------------------
    // 反射接入
    // ------------------------------------------------------------------

    private static synchronized boolean ensure() {
        if (resolved) {
            return !broken && onControlFocus != null && control != null;
        }
        resolved = true;
        try {
            if (!Loader.isModLoaded(MOD_ID)) {
                broken = true;
                return false;
            }
            ClassLoader loader = IngameImeSupport.class.getClassLoader();
            Class<?> controlInterface = Class.forName(CLASS_CONTROL, false, loader);
            Class<?> proxyClass = Class.forName(CLASS_PROXY, false, loader);

            Object instance = proxyClass.getField("INSTANCE").get(null);
            Method focus = proxyClass.getMethod(METHOD_ON_CONTROL_FOCUS,
                    controlInterface, boolean.class, boolean.class);
            Object proxy = Proxy.newProxyInstance(controlInterface.getClassLoader(),
                    new Class[] { controlInterface }, new ControlHandler());

            clientProxy = instance;
            onControlFocus = focus;
            control = proxy;
            System.out.println("[InscriptionTable] 已接入 IngameIME，输入法提交将整串写入编辑器");
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    private static void fail(Throwable t) {
        broken = true;
        onControlFocus = null;
        control = null;
        clientProxy = null;
        focused = false;
        System.err.println("[InscriptionTable] IngameIME 兼容层不可用，回退到原版输入路径: " + t);
    }

    private static void append(String text) {
        if (text == null || text.length() == 0) {
            return;
        }
        synchronized (PENDING) {
            PENDING.append(text);
            hasPending = true;
        }
    }

    private static void clearPending() {
        synchronized (PENDING) {
            PENDING.setLength(0);
            hasPending = false;
        }
    }

    /**
     * IngameIME 的 {@code IControl} 实现。
     * <p>
     * 只有 {@code writeText} 会真正改变编辑器内容，其余方法都是「告诉输入法画在哪」。
     */
    private static final class ControlHandler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("writeText".equals(name)) {
                if (args != null && args.length > 0 && args[0] != null) {
                    append(args[0].toString());
                }
                return null;
            }
            if ("isVisible".equals(name)) {
                return Boolean.TRUE;
            }
            if ("getCursorPos".equals(name)) {
                return new Point(caretX, caretY);
            }
            if ("getControlObject".equals(name)) {
                return editor != null ? (Object) editor : IngameImeSupport.class;
            }
            if ("hashCode".equals(name)) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("equals".equals(name)) {
                return Boolean.valueOf(args != null && args.length > 0 && proxy == args[0]);
            }
            if ("toString".equals(name)) {
                return "InscriptionTableLoreEditor";
            }
            return null;
        }
    }
}
