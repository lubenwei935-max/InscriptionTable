# 铭刻台 Inscription Table（Minecraft 1.7.10 + Forge）

给任意物品写一段「铭文」，支持 **RGB 颜色代码**，内容保存在物品 NBT 里，会显示在物品的 tooltip 中。

- 方块：`inscriptiontable:inscription_table`（自行建模的斜面书写台，TESR 渲染）
- 界面：**原版 `GuiScreen` 手绘**（唯一依赖是 Minecraft + Forge），不依赖任何第三方 UI 库
- 编辑器：多行文本、软换行、选区、撤销/重做、颜色（16 色 + 任意 RGB）、加粗/斜体/下划线
- 中文输入：可直接用输入法，并与「游戏内输入法 IngameIME」互通（多字候选整串上屏）
- 持久化：`ItemStack` 根标签 `InscriptionLore`

---

## 1. 构建与运行环境

1.7.10 只能配合 **ForgeGradle 1.2**，它要求：

| 项目 | 版本 |
| --- | --- |
| JDK | 8 |
| Gradle | 2.0 ~ 2.14（**不能用 Gradle 4+ / 7+**） |
| Forge | 10.13.4.1614 |

```bash
gradle build                  # 产物：build/libs/InscriptionTable-1.7.10-1.2.2.jar
gradle runClient              # 或 runServer 调试
```

已经实测：Gradle 2.0（`~/.gradle/wrapper/dists/gradle-2.0-bin`）+ Temurin JDK 8u492，`clean build` 成功。

两个关键说明：

1. **下载地址补丁**：ForgeGradle 1.2 里写死了 `s3.amazonaws.com/Minecraft.Download` 的下载地址，
   该地址早已 404。`build.gradle` 的 `afterEvaluate` 块会把 `downloadClient / downloadServer /
   getVersionJson / getAssetsIndex` 的 URL 换成 Mojang 现有 CDN（同一份 1.7.10 文件），否则首次构建必然失败。
2. **编码**：`compileJava` 强制 `-encoding UTF-8`；`resources` 不做 `expand`/`filter`（纯拷贝），
   因为 Gradle 2.x 的模板替换会用平台默认编码读文件，在中文 Windows 上会把 `mcmod.info`/`lang` 里的中文弄成乱码。
   升版本时记得同时改 `InscriptionTableMod.VERSION`、`build.gradle` 的 `version` 和 `mcmod.info`。

---

## 2. 目录结构

```
src/main/java/com/example/inscriptiontable/
├── InscriptionTableMod.java              模组入口（注册方块 / TE / 配方）
├── InscriptionConfig.java                配置：最大长度、是否启用 RGB tooltip
├── block/BlockInscriptionTable.java      方块（BlockContainer，getRenderType() = -1）
├── tile/TileEntityInscriptionTable.java  TileEntity：动画时钟 + 单槽物品 + NBT
├── gui/
│   ├── GuiInscriptionTable.java          ★ 原版 GuiScreen：布局 / 物品槽 / 工具栏 / 按钮
│   └── LoreEditor.java                   ★ 自绘文本编辑器（多行 / 选区 / 颜色 / 撤销）
├── network/
│   ├── ModNetwork.java                   通道 + 服务端任务队列
│   ├── PacketAction.java                 客户端 → 服务端（同步 / 点槽 / 保存）
│   ├── PacketUpdate.java                 服务端 → 客户端（槽内物品 + 结果）
│   └── ServerActionHandler.java          服务端逻辑（距离校验、物品归属、写 NBT）
├── util/
│   ├── LoreData.java                     NBT 读写 + 文本清洗 + 读取原版 Lore
│   ├── LoreParser.java                   颜色代码解析（& / #RRGGBB / 换行）
│   └── LoreLine.java / LoreSegment.java  解析结果（行 / 同色片段）
├── proxy/CommonProxy.java                网络初始化 + 服务端 tick 调度
├── proxy/ClientProxy.java                界面打开 + 服务端回包转交 + TESR/tooltip 注册
└── client/
    ├── ime/IngameImeSupport.java         IngameIME 软兼容层（反射注册 IControl）
    ├── render/ModelInscriptionTable.java 自定义方块模型（立方体 + 倾斜台面）
    ├── render/TileEntityInscriptionTableRenderer.java  TESR
    ├── render/ItemRenderInscriptionTable.java          背包/掉落/手持的 3D 模型（IItemRenderer）
    └── tooltip/
        ├── LoreTooltipHandler.java       tooltip 注入 + RGB 覆盖绘制
        └── LoreRenderUtil.java           按片段上色绘制文字

src/main/resources/assets/inscriptiontable/
├── lang/{zh_CN,en_US}.lang
└── textures/
    ├── blocks/inscription_table.png       32x32，2x2 图集（石基/台面/木/铭文）
    └── blocks/inscription_table_icon.png  16x16，物品与破坏粒子图标
```

---

## 3. 方块与模型

1.7.10 **没有 JSON 方块模型**，因此：

- `BlockInscriptionTable` 返回 `getRenderType() = -1`，原版渲染器不会画它；
- 模型在 `ModelInscriptionTable` 里用代码描述（石基 / 束腰 / 中柱 / 背板 / **倾斜 -22° 的书写台面** / 台面上的发光铭文纸），
  每个面用 `Tessellator.addVertexWithUV` 贴 32x32 图集里的一个 16x16 小图，并按面给不同亮度做假阴影；
- `TileEntityInscriptionTableRenderer`（TESR）负责摆位、按 meta 旋转、绑定光照贴图后调用模型；
- **物品形态也是同一套模型**：`ItemRenderInscriptionTable`（`IItemRenderer`）接管
  `INVENTORY / ENTITY / EQUIPPED / EQUIPPED_FIRST_PERSON` 四种形态，背包、快捷栏、创造模式物品栏、
  掉落物、第一人称与第三人称手持全部是 3D 的；四种形态的相机由 Forge / 原版调用方提供，
  渲染器只做「摆正」：
  - `INVENTORY`：`INVENTORY_BLOCK` 助手给出 210° / 45° / 10 倍缩放的中心原点空间，
    模型再绕 Y 转 180° 让铭文板朝向玩家；
  - `ENTITY`：`ENTITY_ROTATION` + `ENTITY_BOBBING` 拿到原版掉落方块的自转与浮动，
    水平居中、底边贴地，Forge 再整体缩放 0.5；
  - `EQUIPPED` / `EQUIPPED_FIRST_PERSON`：`BLOCK_3D` 让 `RenderBiped` 走手持方块分支
    （`translate(0,0.1875,-0.3125) · rotate(20,X) · rotate(45,Y) · scale(-0.375,-0.375,0.375)`），
    `EQUIPPED_BLOCK` 让 Forge 在调用渲染器前补一句 `translate(-0.5,-0.5,-0.5)`；
    但这句 **不含** 原版 `renderBlockAsItem` 自带的 `rotate(90,Y) · translate(-0.5)`，
    所以渲染器自己补回那个 90° 与「以方块中心为原点」的平移（`YAW_HELD`）：
    手持姿态与落点才和手里拿一个原版方块完全一致，铭文面板 / 倾斜书写台朝向玩家（第一人称）
    与正前方（第三人称）；第一人称的外层参数是
    `translate(0.56,-0.52,-0.72) · rotate(45,Y) · scale(0.4)`；
    最后再沿手持局部 +Y 抬 `HELD_RAISE`（0.3，第一人称约 0.12 格），
    免得整格高的台子被屏幕下沿切掉一半；
- 图集四个小图在代码里是 `TILE_STONE / TILE_POLISHED / TILE_WOOD / TILE_RUNE`，想换风格只改 PNG 即可。

贴图可以用脚本重新生成（零依赖，Node）：

```bash
node tools/generate_textures.js

# 封面渲染图：读取同一张图集，按上面的模型几何做软件光栅化 → docs/cover.png
node tools/render_cover.js                    # 1600x1200，yaw 155° / pitch 25°
node tools/render_cover.js 1200 1200          # 自定义尺寸
node tools/render_cover.js 1600 1200 200 10    # 尺寸 + 机位（角度制）
```

---

## 4. 界面（`gui/GuiInscriptionTable`）

右击方块打开。界面只有四类交互元素：**物品槽 / 文本框 / 保存 / 清空**，
颜色与格式工具栏属于文本框自身的能力。

文本框有明确的**编辑状态**：默认只读，点击编辑区才开始接收按键（边框高亮、光标出现），
点击编辑区以外的任何地方退回只读。这样「背包键」（默认 E）才能用来关闭界面，
而不会把 e 打进正文里。

```
┌──────────────────────────────────────────────────────────┐
│ 铭刻台                                        v1.2.2     │
│ 所见即所得 · 支持 &6 与 &#RRGGBB                          │
├──────────────────────────────────────────────────────────┤
│ ┌──┐ 物品名                                              │
│ │槽│ 手持物品点击槽位放入，再点取回                        │
│ └──┘                                                      │
│ ■■■■■■■■  [RRGGBB    ] [应用]                            │
│ ■■■■■■■■  [B] [I] [U] [R]                                 │
│ ┌──────────────────────────────────────────────────────┐  │
│ │ 深色编辑区（多行 / 软换行 / 选区 / 撤销 / 颜色实时）  │  │
│ └──────────────────────────────────────────────────────┘  │
│ 状态提示                              [ 保存 ] [ 清空 ]  │
└──────────────────────────────────────────────────────────┘
```

### 4.1 交互

| 操作 | 行为 |
| --- | --- |
| 手持物品点击物品槽 | 从手持堆叠里取 **1 个** 放入（单槽） |
| 槽内有物品时点击 | 放回背包；背包满则掉在脚下 |
| 槽为空 | 文本框禁用，显示「请先放入物品」 |
| 放入物品 | 自动读取物品介绍文本：优先本模组铭文，其次原版 `display.Lore` |
| 保存 | `Ctrl+S` 或按钮；空文本 = 清除铭文 |
| 清空 | 只清空编辑区，需再点保存才写入物品（状态栏有提示） |
| 悬停物品槽 | 显示完整物品 tooltip（含 RGB 铭文） |
| 点击编辑区 | 进入编辑状态（边框高亮、光标闪烁），按键全部交给文本框 |
| 点击编辑区以外 | 退出编辑状态（只读），状态栏提示「点击文本区开始编辑 · E 关闭界面」 |
| E（背包键） | 编辑区 / RGB 框都未聚焦时关闭界面；编辑区聚焦时 e 照常输入 |
| ESC | 关闭界面；RGB 框聚焦时先取消它的焦点，再按一次才关闭 |

物品槽没有玩家背包格子，避免界面被 27 个格子占满；物品归属、距离校验（8 格内）全部由服务端判定。

### 4.2 编辑器能力（`gui/LoreEditor`）

| 功能 | 说明 |
| --- | --- |
| 多行 / 软换行 | 长行按像素宽度自动折行，回车写入真换行 |
| 光标 / 选区 | 点击定位、拖选、Shift + 方向键扩选、Ctrl+A 全选 |
| 剪贴板 | Ctrl+C / X / V（可直接粘贴中文） |
| 撤销 / 重做 | Ctrl+Z / Ctrl+Y（200 步） |
| 颜色 | 16 个原版色板；或输入 6 位 RRGGBB 后点「应用」 |
| 格式 | B 加粗、I 斜体、U 下划线、R 复位 |
| 所见即所得 | 颜色（含任意 RGB）、加粗、下划线即时按效果绘制；代码本身显示为灰色 |
| 计数 | 编辑区右下角 `长度/上限`，满格变红 |

关于斜体：编辑区内保留 `&o` 代码、不强行做倾斜变换（1.7.10 的原版字体倾斜是绘制期变换），
最终 tooltip 会按斜体显示。

### 4.3 中文输入

编辑器不是原版 `GuiTextField`，所以中文这条链路要单独处理，一共有四层：

1. **字符优先于按键**：`LoreEditor.keyTyped` 先判断「是否可打印字符」（`>= 0x20 && != 0x7F`），
   再走方向键/退格/回车/快捷键分支。输入法提交时 `keyCode` 可能是 `0`、`VK_PACKET(231)`，
   也可能沿用「确认候选词」的那个物理键（回车/退格/空格）——先走按键分支的话，
   整串字符里的第一个会被变成换行或被删掉，看起来就是「只能输入一个字」。
2. **补齐被原版丢掉的事件**：1.7.10 的 `GuiScreen.handleKeyboardInput` 只在
   `Keyboard.getEventKeyState() == true` 时把字符转给 `keyTyped`。输入法提交的后续字符
   常常挂在没有物理按键的抬起事件上（`getEventKey() == KEY_NONE`），原版直接丢弃。
   `GuiInscriptionTable` 重写了 `handleKeyboardInput`，把这些落单的非 ASCII 可打印字符补回来。
3. **与「游戏内输入法」（IngameIME）互通**（`client/ime/IngameImeSupport`）：
   IngameIME 只认识原版 `GuiTextField`，它靠 mixin 监听 `setFocused` 来认领输入框，
   自绘编辑器永远不会被它认领，于是装了它反而**打不出中文**。
   本模组用反射（不引入编译期依赖）动态实现它的 `IControl`，
   并通过它自己的 `ClientProxy#onControlFocus(IControl, boolean, boolean)` 注册进去：
   - 提交文本是**整串**送进 `writeText(String)`，一次插入，不存在逐字截断；
   - `getCursorPos()` 返回编辑器光标的界面坐标，预编辑与候选窗跟着光标走；
   - 编辑器失去输入权（未放入物品 / RGB 输入框被聚焦）时自动交还控制权；
   - RGB 输入框本身就是原版 `GuiTextField`，IngameIME 原生支持，无需额外处理；
   - 没装该模组时整条链路不发生任何反射调用，行为与第 1、2 层一致。
4. **字形**：打开界面时临时启用 `FontRenderer` 的 Unicode 字形（关闭时还原），
   否则 1.7.10 默认字体没有中文宽度，会表现为「输入了但看不见」。
   候选窗的绘制由输入法模组负责（1.7.10 + LWJGL2 没有游戏内内联候选，与原版聊天框一致）。

> 不建议同时装 InputFix 这类改 LWJGL `Keyboard` 的补丁：它们与本模组的字符兜底逻辑会重复投递。
> 只装 IngameIME 即可获得完整的中文（含多字候选）输入体验。

---

## 5. 铭文数据与颜色代码

### 存哪里

```java
stack.getTagCompound().setString("InscriptionLore", text);
```

放在 `ItemStack` 根标签，所以合成、改名、装箱都不会丢；也不会污染原版 `display.Lore`。
读取时会回退到原版 `display.Lore`（如果物品只有原版介绍文本）；保存时写本模组标签并移除原版 Lore，
避免 tooltip 出现两份。

### 颜色代码语法（`LoreParser`）

| 写法 | 含义 |
| --- | --- |
| `&0` ~ `&f` | 原版 16 色 |
| `&#RRGGBB` / `#RRGGBB` | 任意 RGB 颜色 |
| `&l` / `&o` / `&n` | 粗体 / 斜体 / 下划线 |
| `&r` | 复位颜色与格式 |
| 换行 | 推荐直接回车（存 `\n`）；老数据里的「反斜杠 + n」仍然兼容 |
| `&&`、`\\` | 转义 `&`、`\` |

示例：

```
&6铭刻于 &f2026&#FF3B30年
&#FF9500春 &7—— &o风起
```

---

## 6. tooltip 里的 RGB 颜色是怎么做到的

1.7.10 的原版 tooltip 是 `List<String>`，最终由 `GuiScreen.drawHoveringText` 交给 `FontRenderer` 绘制，
**只认 `§0`~`§f` 这 16 种颜色**，而且 1.7.10 没有 `RenderTooltipEvent` 可以接管绘制。

本模组的做法（`LoreTooltipHandler`）：

1. 在 `ItemTooltipEvent` 里，把每行铭文替换成**等像素宽度的空格占位行**，
   这样原版依然会为它留出正确的行高/文本框宽度，但看不到任何字符；
2. 记下占位行在 tooltip 中的起始下标与整体宽度；
3. 在 `TickEvent.RenderTickEvent.Phase.END`（FML 总线，此时整帧的 GUI 与 tooltip 都已绘制完毕）里，
   用与原版 `drawHoveringText` 完全一致的定位算法，在占位行位置上用真实 RGB 逐段补画文字。

如果和别的改 tooltip 的模组冲突（例如它自己重排了文字），可以在配置里关掉：

```cfg
general {
    B:rgbTooltip=true      # 改成 false 后，tooltip 使用「最接近的原版颜色」绘制
    I:maxLoreLength=512    # 铭文最大字符数（服务端也会按此截断）
    I:maxLoreLines=12      # 铭文最大行数（tooltip 没有滚动条，行数过多会占满屏幕）
}
```

---

## 7. 网络（`network` 包）

只有两个包，都是原版 Forge 的 `SimpleNetworkWrapper`：

| 方向 | 包 | 内容 |
| --- | --- | --- |
| C → S | `PacketAction` | `OPEN`（请求同步）/ `SLOT_CLICK`（放入或取回）/ `SAVE`（文本） |
| S → C | `PacketUpdate` | 结果码 + 当前槽内 `ItemStack` |

- 用方块坐标定位，服务端校验「方块存在 + 距离 ≤ 8 格」；
- netty 回调不在主线程，收到的请求先入队，在 `ServerTickEvent.END` 由 `ModNetwork.drainServerTasks()` 执行；
- 保存时服务端重新 `LoreData.sanitize`（去控制字符、`§`→`&`、按 `maxLoreLength` 截断），
  再写 NBT；空文本等于删除铭文；
- 客户端回包同样在 netty 线程，先缓存在 `GuiInscriptionTable` 的静态字段里，由界面自己的 tick 取走。

---

## 8. 使用

1. 合成（木板 x5 + 书 x1 + 石头 x3，见 `InscriptionTableMod#preInit`）或从创造模式物品栏「装饰性方块」拿到铭刻台并放置；
2. 右击打开界面，手持要写字的物品点击左侧槽位（取 1 个放入），编辑区会自动载入物品当前介绍文本；
3. 直接输入内容（可用输入法打中文），选中文字后点色板 / 输入 RGB / 点 B、I、U；
4. 点「保存」（或 Ctrl+S），取出物品，鼠标悬停即可看到彩色铭文。

物品会留在铭刻台的槽里，拆掉方块时会掉在脚下；铭文数据始终在物品上，箱子/合成/改名都不会丢。
