---
navigation:
  parent: chexsonsaeutils_intro/index.md
  title: AE2 Parallel CPU Tool
  position: 40
  icon: chexsonsaeutils:ae2_parallel_cpu_tool
categories:
  - chexsonsaeutils devices
item_ids:
  - chexsonsaeutils:ae2_parallel_cpu_tool
---

# AE2 极大并行 CPU 工具

<BlockImage id="chexsonsaeutils:ae2_parallel_cpu_tool" scale="4" />

<ItemLink id="chexsonsaeutils:ae2_parallel_cpu_tool" /> 是本模组极大并行
AE2 CPU 系统的方块入口与可视化查看工具。

## 主要能力

- 提供该方块所属并行 CPU 集群的专用列表界面。
- 在允许时，除了活跃 CPU，还会显示一个“剩余容量”伪 CPU。
- 通过左侧工具栏保存当前方块的 CPU 选择模式。

## 使用方式

把方块接入正常工作的 AE2 网络后再打开界面。
只有节点就绪且已经接入真实 grid 时，这个界面才会打开。

并行 lane 数、对外宣告的存储容量，以及调度预算，
都由公共配置里的 `[parallelCraftingCpuTool]` 节控制。

## 合成

<RecipeFor id="chexsonsaeutils:ae2_parallel_cpu_tool" />
