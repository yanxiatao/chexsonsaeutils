---
navigation:
  parent: chexsonsaeutils_intro/index.md
  title: 直连处理机映射
  position: 80
categories:
  - chexsonsaeutils configuration
---

# 直连处理机映射

AE 直连处理机的用户映射文件路径是
`config/chexsonsaeutils/direct_processing_machines.json`。

## 字段说明

- `machine_item`：目标机器的方块物品 ID。
- `machine_block`：目标机器的方块 ID。
- `recipe_types`：允许绑定到这台机器的配方类型列表。
- `default_ticks`：默认处理时长，最小值是 `1`。
- `enabled`：该条映射是否启用。
- `io_mode`：当前保持 `generic`。
- `key_types`：可写 `item`、`fluid` 或 `any`。

## 何时需要手工映射

当 GUI 不能安全识别目标机器的 recipe type 时，
就需要手工填写或通过 JEI 导入显式映射。

如果配方依赖概率、副产物、世界状态或运行时动态数据，
就算做了显式映射，也仍然可能继续显示为不安全。
