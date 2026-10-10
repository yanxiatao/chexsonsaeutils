---
navigation:
  parent: chexsonsaeutils_intro/index.md
  title: 归墟
  position: 20
  icon: chexsonsaeutils:extreme_assembler
categories:
  - chexsonsaeutils devices
item_ids:
  - chexsonsaeutils:extreme_assembler
---

# 归墟

<BlockImage id="chexsonsaeutils:extreme_assembler" scale="4" />

<ItemLink id="chexsonsaeutils:extreme_assembler" /> 是一个面向
大量样板存储、滚动浏览与批量执行的 AE2 合成宿主。

## 主要能力

- 单机提供 `16,384` 个样板槽位。
- 内置界面用滚动条浏览样板列表，一行 `9` 个格子。
- 搜索框右侧使用 ExtendedAE 同款图标按钮，切换按输入、输出或输入输出搜索，命中整行保留并涂绿。
- 安装 JECH 后支持全拼、首字母及中文拼音混合搜索。
- 全部样板槽都能在 AE2 的样板管理终端里看到。
- 背包里按住 shift 点击样板，可直接放入第一个空样板槽。

## 使用方式

放入已编码的合成样板后，可以滚动浏览本地样板库。
搜索框根据已同步的全部样板输入输出摘要进行本地化搜索，命中行会留在视野里。

formal-machine 调度由公共配置中的开关控制；大请求使用批处理执行。

## 合成

<RecipeFor id="chexsonsaeutils:extreme_assembler" />
