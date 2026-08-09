# 设置对话框与快捷键

集中说明应用的「设置」对话框（界面缩放 / 语言 / 键盘快捷键）、快捷键架构，以及**如何为一个功能定义可重绑定的快捷键**。本地存储约定见末尾。

## 入口与构成

工具栏右上「设置」齿轮（`Toolbar.kt` 的 `SettingsButton`）打开 `apps/desktop/.../ui/dialogs/SettingsDialog.kt`。`App` 持有 `showSettingsDialog` 与当前 `language` 状态，并把 `uiScale` 一并传入。对话框两个 Tab：

- **常规**：界面缩放步进（`AppSettings.uiScale`，A−/A+ 走 `stepDown`/`stepUp`）+ 语言选择（`Language.entries`）。原工具栏上的独立缩放/语言控件已移除并入此处。
- **快捷键**：按 `ShortcutCategory` 分组列出全部 `ShortcutAction`，每行显示当前键位 chip +「更改」+ 单项重置图标；顶部「全部恢复默认」。

语言改动经 `App` 的 `onLanguageChange`：写 `AppSettings.language` → `I18nRegistry.setLanguage` → 更新本地 `language` 状态 → `refreshKey++` 强制整树重组（对话框因读 `language` 也会重组，i18n 文案即时刷新）。

## 快捷键捕获交互

点某行「更改」→ `ShortcutsTab` 把 `capturing = action`，渲染 `KeyCaptureOverlay`（半透明遮罩）：

- 抢焦点（`FocusRequester` + `focusable`），用 `onPreviewKeyEvent` 截获**下一次** KeyDown。
- 忽略裸修饰键（`Key.isModifierKey()`），以便按住 Ctrl/Shift 再去够目标键；`Esc` 取消。
- 命中即 `KeyStroke.fromEvent(event)` → `KeybindingStore.setBinding(action, stroke)` → 关闭遮罩。

## 架构

`apps/desktop/.../input/` 四个文件，职责单一：

| 类型 | 角色 |
|------|------|
| `KeyStroke` | 一个完整和弦：`keyCode + ctrl/shift/alt`。**精确**匹配修饰键（故 `.` 与 `Shift+.` 是两个不同绑定），`serialize`/`deserialize` 可进 `Preferences`，`displayLabel()` 给人读，`of(Key, …)` 造默认值，`fromEvent` 从捕获事件造。 |
| `ShortcutAction` | 可重绑定动作枚举，每项带 `category` + i18n `labelKey` + 出厂默认 `KeyStroke`。`ShortcutCategory` 给对话框分组（时值 / 修饰 / 连音组 / 变音记号 / 声部 / 编辑历史 / 编辑——剪切 / 复制 / 粘贴 / 删除）。 |
| `KeybindingStore` | 单例，**当前绑定的唯一真相**。`mutableStateMapOf` 快照状态（Compose 读即订阅），写入即落盘 `Preferences("com/mecon/desktop/keybindings")`。 |
| `ShortcutDispatcher` | `handleEditingShortcut(event, session, noteTool, editor)`：根 `onKeyEvent` 调用，命中返回 true；`editor.active` 时快捷键镜像调板的选区编辑。 |

### `KeybindingStore` 行为

- `binding(action)` → 当前 `KeyStroke?`（`null` = 显式未绑定）。
- `setBinding(action, stroke)`：先把持有同一和弦的**其它**动作解绑（一个和弦至多映射一个动作），再写入并落盘。
- `clearBinding(action)`：解绑并持久化哨兵 `"none"`——区别于「恢复默认」，避免下次启动又回退到出厂键。
- `resetToDefault(action)` / `resetAll()`：删除存储项，回退到 `action.default`。
- `actionFor(event)`：返回和弦精确匹配的动作，供分发器查表。

### 分发

`App` 根 `Column.onKeyEvent { … }`：先拦截 `CUT` / `COPY` / `PASTE` / `DELETE`（都需要当前选区或 App 内部剪贴板，见 [score-editing.md](score-editing.md)），其余交 `handleEditingShortcut(event, session, noteTool, selectionEditor)`。这些快捷键按 `ShortcutAction` 的功能语义分发，不调用某个界面按钮；例如 `CUT` 只删除成功复制的音符内容，不继承“删除小节”按钮的结构删除行为。分发器只在 `KeyEventType.KeyDown` 上查 `KeybindingStore.actionFor(event)`，命中后执行 `ShortcutAction.apply(...)`：撤销/重做走 `ScoreSession`；无可编辑选区时其余动作 mutate `NoteToolState`（并切到音符笔 `EditTool.NOTE`，让左侧调板同步高亮；沿用 `toggleDots`/`toggleAccidental` 的再按清除语义）；有可编辑选区时路由到 `SelectionEditor` 修改选中音符属性。`CUT` / `COPY` / `PASTE` / `DELETE` 在 `apply` 里是 no-op（已被根拦截）。

> 焦点在文本框（检查器 / 对话框）时，字符键被文本框消费、不冒泡到根，故输入时不会误触发快捷键。

### 出厂默认键位

| 动作 | 键 | 动作 | 键 |
|------|----|------|----|
| 全 / 二分 / 四分音符 | `1` / `2` / `4` | 升 / 重升 | `s` / `Shift+s` |
| 八分 / 十六 / 卅二音符 | `8` / `6` / `3` | 降 / 重降 | `f` / `Shift+f` |
| 休止符 (toggle) | `0` | 还原 | `n` |
| 单 / 双附点 | `.` / `Shift+.` | 撤销 / 重做 | `Ctrl+Z` / `Ctrl+Y` |
| 连音线 (toggle) | `-` | 连音组 2..9 | `Alt+2..9` |
| 声部 1 / 2 | `Ctrl+1` / `Ctrl+2` | 声部 3 / 4 | `Ctrl+3` / `Ctrl+4` |
| 剪切 / 复制 | `Ctrl+X` / `Ctrl+C` | 粘贴 / 删除 | `Ctrl+V` / `Delete` |

## 如何为一个功能定义快捷键

以「给某功能加一个可在设置里重绑定的快捷键」为例，四步：

1. **加动作**——在 `ShortcutAction` 里新增一项，给定分类、i18n label key、出厂默认和弦：
   ```kotlin
   TOGGLE_FOO(ShortcutCategory.MODIFIER, "shortcut.toggleFoo", KeyStroke.of(Key.G)),
   ```
   （需要修饰键就 `KeyStroke.of(Key.G, ctrl = true)`；想出厂即未绑定，仍需给个占位默认，再让用户清除。）

2. **加翻译**——在 `BuiltinStrings` 的 `zh`/`en` 各加 `"shortcut.toggleFoo"` 文案。新建分类时另加该 `ShortcutCategory.labelKey`。

3. **接行为**——在 `ShortcutDispatcher.kt` 的 `ShortcutAction.apply()` when 分支里加一条，定义按下后做什么：
   ```kotlin
   ShortcutAction.TOGGLE_FOO -> { noteTool.activate(); noteTool.fooMode = !noteTool.fooMode }
   ```
   若行为需要 `session`/`noteTool` 之外的协作者，扩展 `handleEditingShortcut(...)` 的形参并在 `App.kt` 调用点补传。

4. **完成**——设置对话框按 `ShortcutCategory.entries` × `ShortcutAction.entries` 自动渲染该行，`KeybindingStore` 自动持久化、自动解决冲突，根 `onKeyEvent` 自动分发。无需改对话框或存储代码。

> 编译期保证：`apply()` 的 when 对 `ShortcutAction` 穷尽，漏接新动作会编译失败——不会出现「列在设置里却没反应」的绑定。

## 本地存储

均基于 `java.util.prefs.Preferences`（写入即落盘，无显式保存步骤）。

| 配置 | 节点 / 键 | 写入方 | 启动恢复 |
|------|-----------|--------|----------|
| 键位绑定 | `com/mecon/desktop/keybindings`，键 = `ShortcutAction.name`，值 = `KeyStroke.serialize()` 或 `"none"` | `KeybindingStore` | 单例 `init` 逐项读取 |
| 界面语言 | `com/mecon/desktop`，键 `language` = `Language.code` | `AppSettings.language` | `Main.kt` 启动时 `I18nRegistry.setLanguage(AppSettings.language)` |
| 界面缩放 | `com/mecon/desktop`，键 `uiScale` | `AppSettings.uiScale` | `App` 初始读 `AppSettings.uiScale` |
| 音色库 | `com/mecon/desktop/soundfonts`，键 `available` / `loaded` / `default`（换行分隔的绝对路径） | `JvmSoundFontManager.persistCatalogue()` | 构造时 `restoreCatalogue()` 重建目录；`initialize(synth)` 把上次已加载的 `.sf2` 重新载入合成器 |

> 音色库此前**完全无持久化**（全在内存 StateFlow，重启即丢）；现已补上路径 / 加载状态 / 默认音色的持久化与启动重载。详见 [audio/README.md](../audio/README.md)。

## 相关文件

| 关注点 | 文件 |
|--------|------|
| 对话框 UI | `ui/dialogs/SettingsDialog.kt` |
| 快捷键模型 / 分发 | `input/{KeyStroke,ShortcutAction,KeybindingStore,ShortcutDispatcher}.kt` |
| 工具栏入口 | `ui/components/topbar/Toolbar.kt`（`SettingsButton`） |
| 接线 / 语言重组 | `App.kt`、`Main.kt`、`AppSettings.kt` |
| i18n 文案 | `i18n/BuiltinStrings.kt`（`dialog.settings.*` / `shortcut.*`） |
| 音色库持久化 | `audio/.../JvmSoundFontManager.kt` |
