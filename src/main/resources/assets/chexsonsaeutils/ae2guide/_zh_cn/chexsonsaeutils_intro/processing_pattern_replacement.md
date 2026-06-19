---
navigation:
  parent: chexsonsaeutils_intro/index.md
  title: 处理样板替换规则
  position: 50
categories:
  - chexsonsaeutils features
---

# 处理样板替换规则

这个功能扩展了 AE2 样板编码终端中的 processing pattern 流程。

## 打开规则界面

先把终端切到 processing 模式。
按住 `Ctrl` 后左键点击某个处理输入槽，就会打开该槽位的替换规则界面。

## 主要能力

- 为每个处理输入槽保存标签组与显式物品候选。
- 在支持的处理输入槽上显示规则状态徽标。
- 把规则写入编码后样板的 metadata。
- 当标签或候选物品后续发生变化时重新校验规则状态。

## 说明

规则界面只对 processing pattern 生效。
普通 crafting pattern 保持 AE2 原生行为。

该功能由公共配置中的 `processingPatternReplacementEnabled` 控制。
