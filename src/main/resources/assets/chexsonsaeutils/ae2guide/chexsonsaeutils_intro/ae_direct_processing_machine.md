---
navigation:
  parent: chexsonsaeutils_intro/index.md
  title: Straightaway
  position: 30
  icon: chexsonsaeutils:ae_direct_processing_machine
categories:
  - chexsonsaeutils devices
item_ids:
  - chexsonsaeutils:ae_direct_processing_machine
---

# 径行

<BlockImage id="chexsonsaeutils:ae_direct_processing_machine" scale="4" />

<ItemLink id="chexsonsaeutils:ae_direct_processing_machine" /> 用来标记多种
机器方块、识别兼容的处理配方，并把它们暴露成 AE2 可直接调度的处理工作。

## 主要能力

- 样板和机器标记分为两页，通过左侧按钮切换，背景保持原样。
- 机器页提供 `36` 个标记槽，合并所有已标记机器的配方支持范围。
- `16,384` 个样板槽位，界面按行滚动浏览，一行 `9` 个格子。
- 背包里按住 shift 点击样板，可直接放入第一个空样板槽。
- 全量统计“已支持 / 不支持 / 需配置 / 不安全”四类样板状态。
- 全部样板槽都能在 AE2 的样板管理终端里看到。
- 搜索框右侧切换按输入、输出或输入输出搜索；安装 JECH 后支持全拼、首字母及混合拼音。
- 机器页使用同款滚动矩阵和搜索框，可按标记名称、注册 ID 或拼音搜索，点击与 shift 存取沿用样板页行为。
- 搜索范围按钮采用 ExtendedAE 扩展样板管理终端的 12×12 图标，点击循环切换，悬停显示当前范围。
- JEI 按配方类型去重、分包增量导入，保留已有配方；重复导入不重写未变化的分片。
- 加工速度固定为满速，不需要升级卡。

## 使用方式

先切到机器页放入目标机器的方块物品，再切回样板页放入实体处理样板。
机器页也支持从背包 shift 点击放入机器标记，样板仍按原有行为存取。

移除机器标记后，保留样板并重新判断支持状态。
自动发现可复用专用适配器、配置映射及物品和方块注册名，无需每种机器都先导入 JEI。

如果某个配方类型不能被安全识别，需要在
`config/chexsonsaeutils/direct_processing_machines.json`
里添加或导入显式映射。
字段说明见
`config/chexsonsaeutils/direct_processing_machines.guide.md`。

依赖动态状态、概率结果或非确定性输出的配方，
即使完成识别后也仍然可能显示为不安全或不支持。

## 整合包脚本

安装 KubeJS 后，可通过 `ChexsonsaeutilsEvents.directProcessing` 限制类型／机器导入，
声明普通物品标记，追加或替换机器配方类型，以及关闭自动发现。
用 `ServerEvents.recipes` 的 `event.recipes.chexsonsaeutils.direct_processing` 添加物品／流体直连配方。

原生脚本配方支持按 ID 删除、输入输出替换及 `/reload`，不需要先从 JEI 导入。
实体样板的使用行为保持不变。

## 合成

<RecipeFor id="chexsonsaeutils:ae_direct_processing_machine" />
