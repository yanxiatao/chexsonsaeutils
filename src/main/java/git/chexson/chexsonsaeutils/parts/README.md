# Multi-Level Emitter

这是一个 Applied Energistics 2 的附属模组，添加了一个类似 Level Emitter 的物品，但可以监控多个物品并设置物品之间的逻辑关系（AND/OR）。

## 已完成的功能

1. ✅ 创建了 `MultiLevelEmitterItem` - 物品类
2. ✅ 创建了 `MultiLevelEmitterPart` - Part 实现类
3. ✅ 实现了数据存储（NBT）功能
4. ✅ 实现了逻辑模式枚举（AND/OR）
5. ✅ 实现了监控物品的数据类
6. ✅ 在主类中注册了物品
7. ✅ 更新了 mods.toml 添加 AE2 依赖

## 待完成的功能

1. ⚠️ **实现物品数量检查逻辑** - 需要根据实际的 AE2 API 来完成 `evaluateLogic()` 方法中的物品数量检查
2. ⚠️ **创建配置 GUI** - 需要创建 GUI 界面让玩家可以：
   - 添加/删除监控的物品
   - 设置每个物品的阈值
   - 切换逻辑模式（AND/OR）
   - 设置红石模式

3. ⚠️ **修复 API 调用** - 需要根据实际的 AE2 15.4.5 API 来修复：
   - `StorageChannels.items()` 的调用方式
   - `PartItem` 的构造函数
   - `IMEMonitor` 相关的 API

## 使用说明

1. 将 Multi-Level Emitter 放置在 ME 网络的任何位置（类似 Level Emitter）
2. 右键点击打开配置界面（待实现）
3. 添加要监控的物品并设置阈值
4. 选择逻辑模式：
   - **AND**: 所有物品都必须达到阈值才输出红石信号
   - **OR**: 至少一个物品达到阈值就输出红石信号

## 技术说明

### 目录结构
- `parts/` - 实现代码目录
  - `MultiLevelEmitterItem.java` - 物品类
  - `MultiLevelEmitterPart.java` - Part 实现类
- `lib/` - AE2 相关代码目录（已包含 AE2 JAR 文件）

### 需要参考的 AE2 API
由于无法直接查看 AE2 源代码，需要参考：
- `appeng.api.storage.StorageChannels` - 获取物品存储通道
- `appeng.api.storage.IMEMonitor` - 监控 ME 网络存储
- `appeng.api.storage.data.IAEItemStack` - AE2 物品栈
- `appeng.items.parts.PartItem` - Part 物品基类
- `appeng.parts.AEBasePart` - Part 基类

### 下一步工作
1. 查看 AE2 的 Level Emitter 实现（如果可能）
2. 根据实际的 API 修复代码中的 TODO 部分
3. 创建配置 GUI（可以参考 AE2 的其他 Part 的 GUI 实现）
4. 测试功能

