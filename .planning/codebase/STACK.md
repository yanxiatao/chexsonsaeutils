# Technology Stack

**Analysis Date:** 2026-03-30

## Languages

**Primary:**
- Java 17 - 模组主逻辑位于 `src/main/java/git/chexson/chexsonsaeutils/`，主入口是 `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`

**Secondary:**
- Groovy DSL - Gradle 构建逻辑位于 `build.gradle`
- Properties - 版本与构建参数集中在 `gradle.properties` 与 `gradle/wrapper/gradle-wrapper.properties`
- TOML - 模组元数据在 `src/main/resources/META-INF/mods.toml`，运行期公共配置文件实例在 `run/config/chexsonsaeutils-common.toml`
- JSON - 资源包、数据包、Mixin 和 GUI 布局定义位于 `src/main/resources/pack.mcmeta`, `src/main/resources/chexsonsaeutils.mixins.json`, `src/main/resources/assets/`, `src/main/resources/data/`

## Runtime

**Environment:**
- JVM + Minecraft Forge 用户开发环境 - 目标平台由 `gradle.properties` 固定为 Minecraft `1.20.1`、Forge `47.4.10`、Java `17`
- Mod loader: `javafml` - 由 `src/main/resources/META-INF/mods.toml` 声明，`loaderVersion` 使用 `loader_version_range=[47,)`

**Package Manager:**
- Gradle Wrapper `8.8` - 入口文件为 `gradlew`, `gradlew.bat`，发行版定义在 `gradle/wrapper/gradle-wrapper.properties`
- Lockfile: missing - 仓库中未检测到 Gradle dependency lockfile

## Frameworks

**Core:**
- Minecraft ForgeGradle plugin `[6.0.16,6.2)` - 在 `build.gradle` 中提供 Forge userdev、run 配置和 reobf 打包
- Minecraft Forge `1.20.1-47.4.10` - 主 Mod API 与生命周期宿主，入口注册写在 `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- Applied Energistics 2 Forge `15.4.5` - 核心集成对象；部件、菜单、GUI、合成与 Pattern API 广泛出现在 `src/main/java/git/chexson/chexsonsaeutils/parts/`, `src/main/java/git/chexson/chexsonsaeutils/menu/`, `src/main/java/git/chexson/chexsonsaeutils/client/gui/`, `src/main/java/git/chexson/chexsonsaeutils/crafting/`, `src/main/java/git/chexson/chexsonsaeutils/pattern/`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/`
- GuideME `20.1.7` - 在 `build.gradle` 中作为实现依赖声明，用于补齐 AE2 相关运行环境
- Mojang official mappings `1.20.1` - 映射配置在 `gradle.properties`

**Testing:**
- JUnit Jupiter `5.10.2` - 依赖与 `useJUnitPlatform()` 配置写在 `build.gradle`，测试源码位于 `src/test/java/git/chexson/chexsonsaeutils/`

**Build/Dev:**
- Gradle Wrapper `8.8` - 本地构建入口
- Sponge Mixingradle `0.7-SNAPSHOT` - 在 `build.gradle` 的 `buildscript` 中接入
- Sponge Mixin `0.8.5` - 注解处理器与运行时依赖；配置文件为 `src/main/resources/chexsonsaeutils.mixins.json`
- Foojay toolchain resolver `0.7.0` - 在 `settings.gradle` 中配置 Java toolchain 解析
- IntelliJ/Eclipse integration plugins - 在 `build.gradle` 的 `plugins` 块中启用 `idea` 与 `eclipse`

## Minecraft Mod Targets

**Loader/API baseline:**
- 单目标平台是 Forge 生态，不是多 Loader 工程；未检测到 `src/main/resources/fabric.mod.json`、`quilt.mod.json` 或 `src/main/resources/META-INF/neoforge.mods.toml`
- 运行依赖链是 `Minecraft 1.20.1` + `Forge 47.x` + `AE2 15.x`，其版本源分别位于 `gradle.properties` 和 `src/main/resources/META-INF/mods.toml`

**Mixin surface:**
- 主配置文件是 `src/main/resources/chexsonsaeutils.mixins.json`，声明 `required=true`、`compatibilityLevel=JAVA_17`、`refmap=chexsonsaeutils.refmap.json`
- 条件注入插件是 `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java`，启动期依据 `craftingContinuationEnabled` 与 `processingPatternReplacementEnabled` 控制 AE2 Mixin 是否启用
- 已检测到 16 个面向 AE2 的 mixin/accessor，分布在 `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/client/gui/`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/crafting/`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/menu/`

**Resource and data pack touchpoints:**
- 模组元数据与资源包根位于 `src/main/resources/META-INF/mods.toml` 和 `src/main/resources/pack.mcmeta`
- 自有命名空间资源位于 `src/main/resources/assets/chexsonsaeutils/`，包括 `lang/`, `models/item/`, `guis/`
- 直接覆盖/挂接 AE2 命名空间的 GUI 资源位于 `src/main/resources/assets/ae2/screens/` 与 `src/main/resources/assets/ae2/textures/guis/`
- 数据包内容位于 `src/main/resources/data/chexsonsaeutils/recipes/` 和 `src/main/resources/data/chexsonsaeutils/advancements/`
- 数据生成输出目录已在 `build.gradle` 配置为 `src/generated/resources/`，目录存在但当前为空

**Run and test entry points:**
- Mod 主入口：`src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- Client GUI 绑定：`MenuScreens.register(...)` 位于 `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- Menu 网络打开入口：`NetworkHooks.openScreen(...)` 位于 `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterMenu.java`
- Gradle run 配置位于 `build.gradle`：`client`, `server`, `gameTestServer`, `data`
- JUnit 测试入口是 Gradle `test` 任务；未在 `src/main/java/` 或 `src/test/java/` 中检测到实际 `@GameTest` 用例

## Key Dependencies

**Critical:**
- `net.minecraftforge:forge:${minecraft_version}-${forge_version}` - 由 `build.gradle` 声明，是整个 mod 的 loader、注册表、网络与配置宿主
- `appeng:appliedenergistics2-forge:${ae2_version}` - 由 `build.gradle` 声明，是全部功能的外部 API 与被扩展目标
- `org.spongepowered:mixin:0.8.5` - 由 `build.gradle` 声明，支持 `src/main/resources/chexsonsaeutils.mixins.json` 和 `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/`
- `org.appliedenergistics:guideme:${guideme_version}` - 由 `build.gradle` 声明，补充 AE2 运行时依赖

**Infrastructure:**
- `mezz.jei:jei-${minecraft_version}-forge:${jei_version}` - 在 `build.gradle` 中声明为 `runtimeOnly`，用于开发运行时的 JEI 联动
- `org.junit.jupiter:junit-jupiter-api:5.10.2` / `org.junit.jupiter:junit-jupiter-engine:5.10.2` - 在 `build.gradle` 中声明测试运行时
- Repo-local exploded references - `lib/appliedenergistics2-forge-15.4.5_mapped_official_1.20.1.jar/`, `lib/forge-1.20.1-47.4.10_mapped_official_1.20.1-recomp.jar/`, `lib/Applied-Energistics-2-forge-1.20.1/` 为迁移时可直接对照的本地参考材料

## Configuration

**Environment:**
- 构建期版本与坐标统一来自 `gradle.properties`；仓库中未检测到 `.env` 或其他环境变量文件
- Forge 公共配置规范定义在 `src/main/java/git/chexson/chexsonsaeutils/config/ChexsonsaeutilsCompatibilityConfig.java`
- 运行期公共配置文件实例位于 `run/config/chexsonsaeutils-common.toml`，当前包含 `craftingContinuationEnabled` 和 `processingPatternReplacementEnabled`
- 启动期 Feature Gate 会优先读取 `chexsonsaeutils.configDir` 系统属性指定的目录，否则回落到 `FMLPaths.CONFIGDIR`; 实现在 `src/main/java/git/chexson/chexsonsaeutils/config/ContinuationFeatureGate.java` 与 `src/main/java/git/chexson/chexsonsaeutils/config/ProcessingPatternReplacementFeatureGate.java`

**Build:**
- 主要构建文件是 `build.gradle`, `settings.gradle`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`
- `.editorconfig` 明确要求 `charset = utf-8` 与 `end_of_line = crlf`
- `build.gradle` 为 Mixin refmap 启用了 `mixin.env.remapRefMap=true` 和 `${projectDir}/build/createSrgToMcp/output.srg`
- `build.gradle` 配置了 `accessTransformer = file('src/main/resources/META-INF/accesstransformer.cfg')`，但该文件当前未检测到
- `processResources` 会对 `src/main/resources/META-INF/mods.toml` 与 `src/main/resources/pack.mcmeta` 做属性展开

## Platform Requirements

**Development:**
- 需要 JDK `17`；`build.gradle` 使用 Java toolchain，`settings.gradle` 使用 Foojay resolver
- Gradle 运行默认禁用 daemon，并在 `gradle.properties` 中要求 `org.gradle.jvmargs=-Xmx3G`
- 本地开发运行目录使用 `run/`; 当前已检测到 `run/config/`, `run/logs/`, `run/crash-reports/`, `run/saves/`
- 常用入口命令与 `README.md` 一致：`gradlew.bat build`, `gradlew.bat test`, `gradlew.bat runClient`, `gradlew.bat runServer`

**Production:**
- 产物是经 `reobfJar` 处理的 Forge mod JAR；打包流程定义在 `build.gradle`
- 生产侧需要 `Minecraft 1.20.1`, Forge/FML `47+`, AE2 `15+`; 依赖边界写在 `src/main/resources/META-INF/mods.toml`
- 资源包版本 `pack_format=15` 写在 `src/main/resources/pack.mcmeta`

---

*Stack analysis: 2026-03-30*
