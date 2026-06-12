# AE 直连处理机用户映射指引

- 配置文件路径：`config/chexsonsaeutils/direct_processing_machines.json`。
- 每个对象代表一台可绑定机器的显式映射。
- 只有 `enabled=true` 的项会生效。

## 字段说明

- `machine_item`：机器方块物品 ID，例如 `minecraft:furnace`。
- `machine_block`：机器方块 ID。
- `recipe_types`：该机器允许的 `recipe type` 列表，例如 `minecraft:smelting`。
- `default_ticks`：默认处理时长，最小值为 `1`。
- `enabled`：是否启用该条映射。
- `io_mode`：当前保持 `generic`。
- `key_types`：`item`、`fluid` 或 `any`。

## 填写规则

- `modid:machine` 填机器方块物品或方块的注册名。
- `modid:recipe_type` 填服务端已注册的配方类型名。
- 同一台机器可填写多个 `recipe_types`。

## 示例

- 原版熔炉：`machine_item = minecraft:furnace`，`recipe_types = [minecraft:smelting]`。
- 第三方机器：`machine_item = examplemod:crusher`，`recipe_types = [examplemod:crushing]`。

## 排障

- 如果 GUI 已识别到 `recipe type`，但机器仍显示不支持，优先检查该配方是否为静态、确定性输出。
- 如果配方依赖概率、副产物、世界状态或运行时动态数据，需要手工映射也可能继续显示不安全。
- JEI 导入只会写入通过服务端静态验证的候选。
