# 第三方声明

本项目原创内容使用 MIT；第三方移植内容保留其上游版权和许可证。以下记录引用与移植范围，其他兼容接口参考项目见 [README](README.md)。

## Applied Energistics 2

- 来源：[Applied Energistics 2](https://github.com/AppliedEnergistics/Applied-Energistics-2)，参考版本 19.2.17。
- 本地参考：`reference-sources/ae2`。
- 许可证：[LGPL-3.0](LICENSES/LGPL-3.0.txt)。
- `appeng/helpers/patternprovider/PatternProviderLogic.java` 移植为本项目 `CustomPatternProviderLogic.java`，用于定制样板供应器的私有维度机器目标。移植文件保留原 LGPL 声明，包括适用后续版本的条款。

## ExtendedAE

- 来源：[ExtendedAE](https://github.com/GlodBlock/ExtendedAE)。
- 本地参考：`reference-sources/extendedae`。
- 许可证：[LGPL-3.0](LICENSES/LGPL-3.0.txt)。
- `client/gui/matrix` 中的装配矩阵界面、格子和行结构移植自 ExtendedAE；搜索行为与按钮沿用其实现。界面复用 ExtendedAE 提供的 `textures/guis/assembler_matrix.png` 与相关界面资源。
- 本声明不改变上述移植内容的原版权归属；具体文件中的上游声明继续有效。

## REFactoryTeam / AEA

- 来源：[AEA](https://github.com/REFactoryTeam/AEA)，参考分支 `1.20.1`。
- 参考提交：`65d403f15b6ff62ebc06ba3fe013c492a4a846bc`。
- 本地参考：`reference-sources/AEA`。
- 许可证：MIT。
- 本项目迁移了样板染色、增强合成状态、Building Gadgets 2 与 FTB Ultimine 集成；相关源代码、模型、贴图或其他移植内容保留以下声明。

```text
MIT License

Copyright (c) 2026 REFactoryTeam

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
