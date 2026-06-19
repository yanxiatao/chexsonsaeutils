---
navigation:
  parent: chexsonsaeutils_intro/index.md
  title: Crafting Continuation
  position: 60
categories:
  - chexsonsaeutils features
---

# 合成续跑 / Ignore Missing

这个补丁扩展了 AE2 的 Craft Confirm 提交流程，
为每个任务增加了模式切换。

## 模式

- `Default` 保持 AE2 原生提交行为。
- `Ignore Missing` 会先提交可运行的分支，只让缺料分支进入等待。

## 状态可见性

相关的 crafting CPU 与状态界面会持续显示等待数量，
以及等待分支相关信息。

该功能由公共配置中的 `craftingContinuationEnabled` 控制。
