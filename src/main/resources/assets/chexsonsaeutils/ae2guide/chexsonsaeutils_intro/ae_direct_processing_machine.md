---
navigation:
  parent: chexsonsaeutils_intro/index.md
  title: AE Direct Processing Machine
  position: 30
  icon: chexsonsaeutils:ae_direct_processing_machine
categories:
  - chexsonsaeutils devices
item_ids:
  - chexsonsaeutils:ae_direct_processing_machine
---

# AE 直连处理机

<BlockImage id="chexsonsaeutils:ae_direct_processing_machine" scale="4" />

<ItemLink id="chexsonsaeutils:ae_direct_processing_machine" /> 用来绑定一台
机器方块、识别兼容的处理配方，并把它们暴露成 AE2 可直接调度的处理工作。

## 主要能力

- 一个绑定槽位，用来放目标机器的方块物品。
- `16,384` 个样板槽位，每页显示 `27` 个。
- 每页统计“已支持 / 不支持 / 需配置 / 不安全”四类样板状态。
- 在 JEI 运行时可把候选配方类型导入用户映射。
- 支持安装 AE2 速度卡。

## 使用方式

先把目标机器的方块物品放进绑定槽，再放入处理样板。

如果某个配方类型不能被安全识别，需要在
`config/chexsonsaeutils/direct_processing_machines.json`
里添加或导入显式映射。
字段说明见
`config/chexsonsaeutils/direct_processing_machines.guide.md`。

依赖动态状态、概率结果或非确定性输出的配方，
即使完成识别后也仍然可能显示为不安全或不支持。

## 合成

<RecipeFor id="chexsonsaeutils:ae_direct_processing_machine" />
