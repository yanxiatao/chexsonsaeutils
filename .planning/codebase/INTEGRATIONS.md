# External Integrations

**Analysis Date:** 2026-03-30

## APIs & External Services

**Minecraft Mod Platform:**
- Minecraft Forge / JavaFML - 提供 mod lifecycle、registries、menus、network hooks、config 与 userdev runs
  - SDK/Client: `net.minecraftforge:forge:${minecraft_version}-${forge_version}` from `build.gradle`
  - Auth: Not applicable
- Forge event bus / registry integration - 实际入口与注册发生在 `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
  - SDK/Client: `net.minecraftforge` API through `DeferredRegister`, `RegistryObject`, `ModLoadingContext`, `FMLJavaModLoadingContext`
  - Auth: Not applicable

**AE2 Integration:**
- Applied Energistics 2 - 这是主宿主 API，也是被 mixin 的目标；关键接点位于 `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`, `src/main/java/git/chexson/chexsonsaeutils/parts/automation/`, `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/`, `src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/`, `src/main/java/git/chexson/chexsonsaeutils/crafting/`, `src/main/java/git/chexson/chexsonsaeutils/pattern/replacement/`, `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/`
  - SDK/Client: `appeng:appliedenergistics2-forge:${ae2_version}`
  - Auth: Not applicable
- AE2 GUI/style/resources - 屏幕布局和纹理挂接到 `src/main/resources/assets/ae2/screens/` 与 `src/main/resources/assets/ae2/textures/guis/`
  - SDK/Client: AE2 resource namespace + AE2 client GUI classes imported in `src/main/java/git/chexson/chexsonsaeutils/client/gui/implementations/`
  - Auth: Not applicable
- AE2 crafting continuation / pattern decoder hook - 通过 `PatternDetailsHelper` decoder 注入与多组 `Crafting*Mixin`
  - SDK/Client: `appeng.api.crafting.PatternDetailsHelper` + `org.spongepowered:mixin`
  - Auth: Not applicable

**Mixin and Bytecode Hooks:**
- Sponge Mixin - 对 AE2 菜单、GUI、合成计算与执行路径做条件化 patch
  - SDK/Client: `org.spongepowered:mixin:0.8.5`, `org.spongepowered:mixingradle:0.7-SNAPSHOT`
  - Auth: Not applicable
- Mixin plugin gating - `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java` 根据 `run/config/chexsonsaeutils-common.toml` 对 continuation 与 replacement 功能分流
  - SDK/Client: `src/main/resources/chexsonsaeutils.mixins.json`
  - Auth: Not applicable

**Auxiliary Mod Runtime:**
- GuideME - 与 AE2 配套的运行时依赖，在 `build.gradle` 中作为 `implementation`
  - SDK/Client: `org.appliedenergistics:guideme:${guideme_version}`
  - Auth: Not applicable
- JEI - 仅在开发/运行时以 `runtimeOnly` 加载，源码中未检测到直接 `mezz.jei` import
  - SDK/Client: `mezz.jei:jei-${minecraft_version}-forge:${jei_version}`
  - Auth: Not applicable

**External network services:**
- Not detected - 仓库内未发现 HTTP client、SaaS SDK、数据库驱动或第三方 Web API 调用；集成边界集中在 Minecraft/Forge/AE2 生态内

## Data Storage

**Databases:**
- None
  - Connection: Not applicable
  - Client: Not applicable

**File Storage:**
- Local filesystem only
  - Forge 公共配置写入/读取 `run/config/chexsonsaeutils-common.toml`，规范定义在 `src/main/java/git/chexson/chexsonsaeutils/config/ChexsonsaeutilsCompatibilityConfig.java`
  - 世界级持久化通过 `SavedData` 写入世界存档，实现在 `src/main/java/git/chexson/chexsonsaeutils/crafting/persistence/CraftingContinuationSavedData.java`
  - 资源与数据包源文件位于 `src/main/resources/assets/` 和 `src/main/resources/data/`
  - 数据生成目标目录为 `src/generated/resources/`，由 `build.gradle` 的 `data` run 指向，当前目录存在但为空

**Caching:**
- None as an application feature
  - Build caches 存在于 `.gradle/` 与 `.gradle-user/`
  - 本地运行缓存/日志位于 `run/`, `run/logs/`, `run/crash-reports/`

## Authentication & Identity

**Auth Provider:**
- None
  - Implementation: 仓库未接入 OAuth、账号系统或密钥认证；玩家/请求者身份仅通过 Minecraft/AE2 运行时对象传递，例如 `src/main/java/git/chexson/chexsonsaeutils/crafting/status/CraftingContinuationStatusService.java` 中使用的 AE2 player/source API

## Monitoring & Observability

**Error Tracking:**
- None

**Logs:**
- SLF4J/Forge console logging
  - 主 logger 在 `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
  - 开发运行日志目录为 `run/logs/`
  - JVM 崩溃痕迹已检测到 `run/hs_err_pid2156.log` 与 `run/replay_pid2156.log`

## CI/CD & Deployment

**Hosting:**
- Not applicable as a hosted service
  - 交付物是通过 `build.gradle` 的 `jar` + `reobfJar` 生成的 Forge mod JAR

**CI Pipeline:**
- None
  - 未检测到 `.github/workflows/`、Jenkinsfile 或其他 CI 配置文件

## Environment Configuration

**Required env vars:**
- None detected
- 构建参数来自 `gradle.properties`
- 可选系统属性 `chexsonsaeutils.configDir` 允许覆盖公共配置目录，见 `src/main/java/git/chexson/chexsonsaeutils/config/ContinuationFeatureGate.java` 与 `src/main/java/git/chexson/chexsonsaeutils/config/ProcessingPatternReplacementFeatureGate.java`

**Secrets location:**
- Not applicable
- 仓库中未检测到 `.env`、证书或凭据文件

## Webhooks & Callbacks

**Incoming:**
- None
- 内部运行时回调主要来自 Forge mod 事件总线与客户端/服务端 setup 生命周期，入口位于 `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`

**Outgoing:**
- None
- 内部网络同步使用 Forge 菜单打开与客户端 action 回传，不是 webhook；相关入口在 `src/main/java/git/chexson/chexsonsaeutils/menu/implementations/MultiLevelEmitterMenu.java`

## Mod Entry Points and Migration Touchpoints

**Primary entry points:**
- Mod bootstrap: `src/main/java/git/chexson/chexsonsaeutils/Chexsonsaeutils.java`
- Mixin config: `src/main/resources/chexsonsaeutils.mixins.json`
- Mixin gating plugin: `src/main/java/git/chexson/chexsonsaeutils/mixin/ae2/ChexsonsaeutilsMixinPlugin.java`
- Forge metadata: `src/main/resources/META-INF/mods.toml`

**Run targets:**
- `build.gradle` 定义 `client`, `server`, `gameTestServer`, `data` 四类运行入口
- `run/` 是默认工作目录；`data` run 会使用 `run-data/` 作为工作目录并把生成结果输出到 `src/generated/resources/`
- 测试入口是 `build.gradle` 中启用的 Gradle `test` 任务，测试源码位于 `src/test/java/git/chexson/chexsonsaeutils/`

**Resource and data files that bind to external APIs:**
- AE2 命名空间 GUI 布局：`src/main/resources/assets/ae2/screens/multi_level_emitter.json`, `src/main/resources/assets/ae2/screens/processing_pattern_replacement.json`
- AE2 命名空间 GUI 纹理：`src/main/resources/assets/ae2/textures/guis/multi_level_emitter.png`
- 自有命名空间模型/语言/贴图：`src/main/resources/assets/chexsonsaeutils/models/item/multi_level_emitter.json`, `src/main/resources/assets/chexsonsaeutils/lang/en_us.json`, `src/main/resources/assets/chexsonsaeutils/lang/zh_cn.json`, `src/main/resources/assets/chexsonsaeutils/guis/processing_pattern_replacement.png`
- 数据包配方与进度：`src/main/resources/data/chexsonsaeutils/recipes/network/parts/multi_level_emitter.json`, `src/main/resources/data/chexsonsaeutils/advancements/recipes/network/parts/multi_level_emitter.json`

---

*Integration audit: 2026-03-30*
