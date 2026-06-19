---
navigation:
  parent: chexsonsaeutils_intro/index.md
  title: ME 多槽发信器
  position: 10
  icon: chexsonsaeutils:multi_level_emitter
categories:
  - chexsonsaeutils devices
item_ids:
  - chexsonsaeutils:multi_level_emitter
---

# ME 多槽发信器

<ItemGrid>
  <ItemIcon id="chexsonsaeutils:multi_level_emitter" />
</ItemGrid>

<ItemLink id="chexsonsaeutils:multi_level_emitter" /> 保持 AE2 原生部件放置语义，
但把一个发信器扩展成可同时管理多个监控槽位的部件。

## 主要能力

- 单个部件最多提供 `64` 个监控槽位。
- 每个槽位独立保存阈值与比较方式。
- 支持带括号的 `AND / OR` 表达式，例如 `#1 OR (#2 AND #3)`。
- 安装模糊卡后，可为每个槽位切换模糊匹配模式。
- 安装合成卡后，可为每个槽位切换合成相关模式。

## 使用方式

和普通 AE2 部件一样放到可接收 part 的面上。
打开界面后配置可见槽位数量、每槽阈值与共享表达式。

`OFF` 只按库存数量判定。
`REQ` 在目标处于请求态时输出红石。
`SUP` 除了请求态红石，还会把目标暴露成可补货的合成需求。

## 合成

<RecipeFor id="chexsonsaeutils:multi_level_emitter" />
