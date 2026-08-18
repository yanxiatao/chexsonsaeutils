---
navigation:
  parent: chexsonsaeutils_intro/index.md
  title: 框架样板供应器
  position: 30
  icon: chexsonsaeutils:frame_pattern_provider
categories:
  - chexsonsaeutils devices
item_ids:
  - chexsonsaeutils:frame_pattern_provider
---

# 框架样板供应器

<BlockImage id="chexsonsaeutils:frame_pattern_provider" scale="4" />

<ItemLink id="chexsonsaeutils:frame_pattern_provider" /> 可以套在其它机器或容器外
作为框架：放置后显示为原方块加边框，捕获时机器会被搬到私有维度真实运行，
框架方块负责样板推送、抽取与供电。

## 主要能力

- 完整 AE2 样板供应器功能：每页 `36` 个样板槽、返回库存、`5` 个升级卡槽，需 `1` 频道。
- 分页管理样板：GUI 右上方翻页按钮，页数上限由配置 `MAX_FRAME_PATTERN_PAGES` 控制（默认 `8`）。
- 扩容：消耗 ExtendedAE 扩展样板供应器物品增加页数，两种方式（见下）。
- 输入过滤：开启后返回库存注入网络只放行已配置样板的输出物品。
- 样板配置：为处理样板指定每个格子材料输入机器的指定槽位，可突破单格堆叠上限。
- 隔离模式：只传电不传频道，机器不并入主网格。
- 主动抽取：一键抽取机器输出到返回库存。
- appflux 感应卡：安装后不对供应器其它面供电，只对框架内机器供电并强行灌满。

## 使用方式

用扳手类物品（wrench 标签）或瞄准边框右击打开 GUI；shift 右击拆除框架，
机器搬回原位。点击框架内部区域会透传原方块交互。

### 样板分页

样板槽按页显示，每页 `36` 个。GUI 右上方按钮翻页；
页数上限在 `config/chexsonsaeutils-common.toml` 的 `MAX_FRAME_PATTERN_PAGES` 配置
（范围 `1-8`，默认 `8`）。

### 扩容

两种方式任选：

- GUI 左侧扩展按钮打开扩容界面：放入扩展样板供应器物品
  （`extendedae:ex_pattern_provider`），确认后消耗 `1` 个、页数 `+1`；已达上限时不消耗。
- 把扩展样板供应器物品放入物质聚合器（Condenser）存储槽：存储槽容量等于当前页数，
  放入后同样消耗 `1` 个、页数 `+1`。

### 输入过滤

GUI 左侧过滤按钮（开启/关闭）。开启后，返回库存注入网络时只放行
已配置样板的输出物品，其余物品留在返回库存中。

### 样板配置

GUI 左侧配置按钮进入样板配置模式，点击样板槽打开配置界面：

- 处理样板：定制配置——为每个格子材料指定输入机器的指定槽位，可突破单格堆叠上限。
- 高级样板（advancedae）：直接打开高级样板编码器界面。

### 隔离模式

GUI 左侧隔离按钮。开启后框架只向机器传电、不传频道，
机器不并入主网格（石英纤维 overlay 语义）。

### 主动抽取

GUI 左侧抽取按钮：点击后立即把机器输出抽取到返回库存。

## 全局快捷键

背包中有网络工具时，任意 GUI 中按住 `Alt+I` 显示当前 GUI 物品槽位编号
（不含背包槽）。键位可在游戏设置中修改。

## 合成

<RecipeFor id="chexsonsaeutils:frame_pattern_provider" />