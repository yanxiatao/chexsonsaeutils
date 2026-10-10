# 直连处理机 KubeJS 魔改

- 环境：Minecraft 1.21.1、NeoForge、KubeJS 2101.7.2 起。
- KubeJS 为可选依赖；未安装时保留原有机器识别、实体样板和 JEI 导入行为。
- 开发环境可用 `gradlew runClient -Pkubejs` 加载 KubeJS 和 Rhino。
- 标记规则放在 `kubejs/server_scripts`，物品注册放在 `kubejs/startup_scripts`。
- 服务器拥有最终权限；客户端无需执行服务器规则脚本，打开直连菜单时接收规则快照。

## 自定义标记与权限

先创建普通标记物品：`kubejs/startup_scripts/direct_processing_items.js`。

```js
StartupEvents.registry('item', event => {
  event.create('processing_card').displayName('直连加工卡')
})
```

在 `kubejs/server_scripts/direct_processing_rules.js` 声明标记和限制。
以下配置展示一个只使用脚本直连配方的标记物品，以及按类型和机器限制 JEI 导入。

```js
ChexsonsaeutilsEvents.directProcessing(event => {
  event.marker('kubejs:processing_card')
  event.replaceMachineTypes('kubejs:processing_card', [])

  // 只拒绝新导入，不禁用已有配方或原生自动适配。
  event.disableImportType('minecraft:smelting')
  event.disableImportMachine('minecraft:furnace')

  // 需要彻底禁用执行时，使用以下接口。
  event.disableType('minecraft:smoking')
  event.disableMachine('minecraft:smoker')
})
```

| 接口 | 行为 |
| --- | --- |
| `marker(itemId)` | 允许普通物品作为标记，可绑定原生脚本直连配方 |
| `marker(itemId, typeIds)` | 声明普通标记，同时追加其支持的配方类型 |
| `addMachineTypes(machineId, typeIds)` | 为机器或标记追加类型，与专用适配器和已有映射合并 |
| `replaceMachineTypes(machineId, typeIds)` | 替换已有类型范围，同时关闭该机器自动发现；空数组仅允许脚本直连配方 |
| `disableImportType(typeId)` | 拒绝该类型的新 JEI 导入，保留旧数据的执行权限 |
| `disableImportMachine(machineId)` | 拒绝指定机器或标记的新 JEI 导入 |
| `disableType(typeId)` | 全局禁用该类型的执行及导入，覆盖旧导入、映射和专用适配器 |
| `disableMachine(machineId)` | 禁用该标记的放入、识别、导入和执行，库存内原物品仍可取回 |
| `jeiImport(false)` | 全局关闭新 JEI 导入 |
| `importTypeWhitelist(typeIds)` | 仅允许这些类型导入；空数组禁止全部类型导入 |
| `automaticDiscovery(false)` | 全局关闭注册名推断与通用自动发现，保留显式映射及专用适配器 |
| `disableAutomaticDiscovery(machineId)` | 为单个机器关闭自动发现及专用适配失败后的名称推断 |

- `machineId` 对应已注册物品 ID；方块机器的物品和方块 ID 都参与规则匹配。
- 配方类型 ID 可包含仅由 JEI 提供的类型，不要求全部存在于原生配方类型注册表。
- 禁用规则优先于追加、替换和白名单；多台机器的支持范围仍合并，禁用一台不会禁用其他机器。
- `replaceMachineTypes` 的白名单约束原有配置和旧导入；`chexsonsaeutils:direct_processing` 单独保留。
- 如需禁止脚本直连配方执行，调用 `disableType('chexsonsaeutils:direct_processing')`。
- 每次配方重载创建全新规则快照；删除一条规则并重载后，该限制随之解除。
- 事件参数验证失败或回调报错会拒绝发布部分规则，并由 KubeJS 报告错误。
- 禁用规则不删除实体样板、标记或导入文件，解除限制后可重新使用。

## 自定义直连配方

`kubejs/server_scripts/direct_processing_recipes.js`：

```js
ServerEvents.recipes(event => {
  // 参数顺序：物品输出、带数量的物品输入、机器或标记 ID 列表。
  event.recipes.chexsonsaeutils.direct_processing(
    ['2x minecraft:iron_ingot', 'minecraft:stone'],
    ['3x minecraft:raw_iron'],
    ['kubejs:processing_card']
  ).id('kubejs:direct/raw_iron')

  // 数量流体、标签输入及副产物；流体数量单位为 mB。
  event.recipes.chexsonsaeutils.direct_processing(
    ['minecraft:iron_ingot'],
    ['2x #c:ingots/gold'],
    ['kubejs:processing_card']
  ).fluidInputs([Fluid.of('minecraft:water', 500)])
    .fluidOutputs([Fluid.of('minecraft:lava', 250)])
    .id('kubejs:direct/mixed')

  // 只有流体时使用单参数构造，无需虚构物品输入或输出。
  event.recipes.chexsonsaeutils.direct_processing(['kubejs:processing_card'])
    .fluidInputs([Fluid.of('minecraft:water', 1000)])
    .fluidOutputs([Fluid.of('minecraft:lava', 500)])
    .id('kubejs:direct/fluid_conversion')
})
```

- 可用 `.itemInputs(...)`、`.itemOutputs(...)`、`.fluidInputs(...)`、`.fluidOutputs(...)`、`.machines(...)` 设置字段。
- 原生配方必须指定至少一个机器或标记、至少一个输入、至少一个输出，数量均为正数。
- 允许物品和流体混合、多输入、多输出、输入标签及备选原料；输出为确定堆栈，不支持概率产物。
- 输入备选的笛卡尔积最多 256 组；空标签或超出上限会记录配方 ID 并跳过整条配方，保留其他有效配方。
- 目标为普通物品时须先用 `marker` 声明；方块物品可直接用作目标。
- 实体样板仍按原行为编码和存取；输入、输出及数量必须与自定义配方匹配。
- 加工速度沿用直连处理机的固定满速行为。
- 脚本配方不会被 JEI 导入复制到磁盘签名库，删除或修改配方后不留下可执行旧副本。

原生配方参加 KubeJS 的配方筛选和替换。
以下操作放在 `ServerEvents.recipes` 回调内，筛选顺序遵循 KubeJS 本身。

```js
event.remove({ id: 'kubejs:direct/raw_iron' })
event.replaceInput(
  { type: 'chexsonsaeutils:direct_processing' },
  'minecraft:raw_iron', 'minecraft:raw_gold'
)
event.replaceOutput(
  { type: 'chexsonsaeutils:direct_processing' },
  'minecraft:iron_ingot', 'minecraft:gold_ingot'
)
```

也可直接用 `event.custom` 或数据包定义相同的原生配方：

```js
event.custom({
  type: 'chexsonsaeutils:direct_processing',
  machines: ['kubejs:processing_card'],
  item_inputs: [{ ingredient: { item: 'minecraft:raw_iron' }, count: 3 }],
  item_outputs: [{ id: 'minecraft:iron_ingot', count: 2 }]
}).id('kubejs:direct/json_iron')
```

- JSON 字段为 `machines`、`item_inputs`、`fluid_inputs`、`item_outputs`、`fluid_outputs`。
- 使用 NeoForge 的嵌套带数量 Ingredient 编码；物品与流体输出使用各自原生 Stack 编码。
- `/reload` 更新服务器配方、规则及机器索引；新增 startup 物品需要重启游戏。
- 重载会重新判断已有样板支持状态；菜单打开期间的权限变更同步给客户端。

## 验证范围

- 行为测试覆盖真实 Rhino 事件调用、规则快照、旧导入执行过滤、原生配方和网络编解码。
- KubeJS schema 测试执行实际注册，并用各字段 codec 生成可被原生 serializer 读取的配方 JSON。
- 测试不代替装有 KubeJS 的实际客户端、整合包或专用服务器验收。
- 插件接口参考 [KubeJS 2101 官方源码](https://github.com/kube-mods/kubejs/tree/2101/src/main/java/dev/latvian/mods/kubejs)。
