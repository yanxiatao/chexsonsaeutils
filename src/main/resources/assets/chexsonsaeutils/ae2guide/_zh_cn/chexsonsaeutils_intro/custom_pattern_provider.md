---
navigation:
  parent: chexsonsaeutils_intro/index.md
  title: 定制样板供应器
  position: 31
  icon: chexsonsaeutils:custom_pattern_provider
categories:
  - chexsonsaeutils devices
item_ids:
  - chexsonsaeutils:custom_pattern_provider
  - chexsonsaeutils:custom_pattern_provider_part
---

# 定制样板供应器

<ItemGrid>
  <ItemIcon id="chexsonsaeutils:custom_pattern_provider" />
  <ItemIcon id="chexsonsaeutils:custom_pattern_provider_part" />
</ItemGrid>

<ItemLink id="chexsonsaeutils:custom_pattern_provider" /> 以方块与面板两种形式提供
与普通 AE2 样板供应器一致的行为：把处理样板直接推送到相邻机器。方块形式直接放置，
推送方向可用扳手切换；面板形式附着于线缆，面板朝向的相邻机器即推送目标。

除 AE2 样板供应器的完整功能外，还支持定制样板（强制槽位写入/抽取）、分页管理、
扩容、输入过滤与 appflux 感应卡灌电。

## 主要能力

- 完整 AE2 样板供应器功能：每页 `36` 个样板槽、返回库存、`5` 个升级卡槽，需 `1` 频道。
- 定制样板：样板的每个格子材料按配置强制写入机器指定槽位，可突破单格堆叠上限；
  机器输出按配置槽位强制抽取。
- 分页管理样板：GUI 右上方翻页按钮，页数上限由配置 `maxCustomPatternPages` 控制（默认 `8`）。
- 扩容：消耗 ExtendedAE 扩展样板供应器物品增加页数，两种方式（见下）。
- 输入过滤：开启后返回库存注入网络只放行已配置样板的输出物品。
- 样板配置：为处理样板指定每个格子材料输入机器的指定槽位；高级样板（advancedae）
  直接打开高级样板编码器界面。
- 主动抽取：一键抽取机器输出到返回库存。
- 返回栏单格超堆叠：产物返回栏的单格可超过物品堆叠上限，机器整槽产出一次搬空
  （由配置 `customPatternProviderOverstackReturnEnabled` 控制，见下）。
- appflux 感应卡：安装后不对供应器其它面供电，只对相邻机器供电并强行灌满。

## 使用方式

方块形式：直接放置，空手右击打开 GUI；手持扳手类物品（wrench 标签）右击循环旋转
推送方向（全部方向或单方向）。

面板形式：和普通 AE2 部件一样放到可接收 part 的线缆面上，面板朝向的相邻机器即
推送、抽取与灌电目标；右击面板打开 GUI。

### 样板分页

样板槽按页显示，每页 `36` 个。GUI 右上方按钮翻页；
页数上限在 `config/chexsonsaeutils-common.toml` 的 `maxCustomPatternPages` 配置
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

### 主动抽取

GUI 左侧抽取按钮：点击后立即把机器输出抽取到返回库存。

### 返回栏超堆叠

产物返回栏（`9` 格）的单格允许超过物品堆叠上限，因此机器整槽产出可以被一次搬走：

- 未配置「抽取槽位」时，一轮主动抽取即可搬空机器全部输出（输入过滤开启时只搬样板输出，
  关闭时搬机器内全部物品）。
- 配置了「抽取槽位」时，指定槽位无条件优先抽取，其余槽位按上面的规则处理。
- 超过上限的格子在 GUI 中显示为真实物品与紧凑数量（如 `1.4K`），但不能拖拽——
  点击该格可每次取回一栈（不超过物品堆叠上限），连点可抽完。

注意：

- 面板形式的返回栏通过 AE2 通用库存能力对外可见，管道可以往单格里灌入超过上限的数量；
  方块形式对外透传的是相邻机器的物品能力，不含返回栏。
- 数量极大时拆方块掉落会被 AE2 按每栈上限拆分并设总量上限，超出部分会丢失；
  正常情况下返回栏每 tick 都会整批退回网络，驻留量极小。
- 关闭配置总闸 `customPatternProviderOverstackReturnEnabled` 后，新写入回退到原有单格上限；
  已经超出上限的存量仍会整批退回网络，不会被吞掉。

## 与直接放置机器的差异

- 不捕获机器：机器保持在原位置，供应器只做相邻推送、抽取与灌电，没有私有维度。
- 无隔离模式：不存在「只传电不传频道」的开关。

## 全局快捷键

背包中有网络工具时，任意 GUI 中按住 `Alt+I` 显示当前 GUI 物品槽位编号
（不含背包槽）。键位可在游戏设置中修改。

## 合成

<RecipeFor id="chexsonsaeutils:custom_pattern_provider" />
