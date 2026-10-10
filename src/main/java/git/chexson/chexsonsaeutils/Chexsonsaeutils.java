package git.chexson.chexsonsaeutils;

import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.crafting.PatternDetailsHelper;
import git.chexson.chexsonsaeutils.cell.InfinityCellStore;
import git.chexson.chexsonsaeutils.mattermassstore.MatterMassStore;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.config.PlanningAlgorithmOrderService;
import git.chexson.chexsonsaeutils.config.PlanningServerConfig;
import git.chexson.chexsonsaeutils.command.ChexsonsaeutilsCommand;
import git.chexson.chexsonsaeutils.crafting.hostbatch.HostBatchPatternDecoder;
import git.chexson.chexsonsaeutils.crafting.graphplan.engine.GraphPlanCapture;
import git.chexson.chexsonsaeutils.crafting.custompattern.CustomPatternDecoder;
import git.chexson.chexsonsaeutils.crafting.mattermass.MatterMassPatternDecoder;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigMappingRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigMappingReloadListener;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeUserConfigStore;
import git.chexson.chexsonsaeutils.client.ae2.PatternItemColorRegistration;
import git.chexson.chexsonsaeutils.client.ae2.DyeablePatternPackRegistration;
import git.chexson.chexsonsaeutils.client.ChexsonsaeutilsClientScreens;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.PatternDetailsHelperAccessor;
import git.chexson.chexsonsaeutils.client.SlotNumberOverlay;
import git.chexson.chexsonsaeutils.integration.appflux.AppFluxCompat;
import git.chexson.chexsonsaeutils.integration.extendedae_plus.ExtendedAePlusCompat;
import git.chexson.chexsonsaeutils.integration.uselessmod.UselessBigIntegerCompat;
import git.chexson.chexsonsaeutils.menu.custompatternconfig.CustomPatternConfigLocator;
import git.chexson.chexsonsaeutils.menu.custompatternupgrade.CustomPatternUpgradeLocator;
import git.chexson.chexsonsaeutils.network.ChexsonsaeutilsNetwork;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingPatternReplacementDecoder;
import git.chexson.chexsonsaeutils.modid.ModIds;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;

@Mod(ModIds.MODID)
public class Chexsonsaeutils {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** 启动期打一次构建指纹用的 mod 版本串（构造期从 modContainer 抓，B-2）。 */
    private final String modVersion;

    public Chexsonsaeutils(IEventBus modEventBus, ModContainer modContainer) {
        this.modVersion = modContainer.getModInfo().getVersion().toString();
        modContainer.registerConfig(ModConfig.Type.COMMON, ChexsonsaeutilsCompatibilityConfig.SPEC);
        modContainer.registerConfig(ModConfig.Type.SERVER, PlanningServerConfig.SPEC);
        ChexsonsaeutilsContent.register(modEventBus);

        modEventBus.addListener(this::onCommonSetup);
        modEventBus.addListener(this::onConfigLoad);
        modEventBus.addListener(this::onConfigReload);
        modEventBus.addListener(this::onRegisterCapabilities);
        modEventBus.addListener(this::onRegisterPartCapabilities);
        modEventBus.addListener(this::onRegisterPayloadHandlers);
        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(this::onLevelSave);
        NeoForge.EVENT_BUS.addListener(this::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(git.chexson.chexsonsaeutils.events.MatterMassMergeHandler::onItemPickup);
        NeoForge.EVENT_BUS.addListener(git.chexson.chexsonsaeutils.events.MatterMassMergeHandler::onContainerClose);
        // 槽位编号叠加为纯客户端功能：仅客户端注册，避免专用服务端构造期经方法引用
        // 加载 SlotNumberOverlay（引用 Minecraft/LocalPlayer）触发 dist 校验失败。
        if (FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(SlotNumberOverlay::registerKeyMapping);
            NeoForge.EVENT_BUS.addListener(SlotNumberOverlay::onScreenRender);
            NeoForge.EVENT_BUS.addListener(SlotNumberOverlay::onScreenKeyPressed);
            NeoForge.EVENT_BUS.addListener(SlotNumberOverlay::onScreenClosing);
            NeoForge.EVENT_BUS.addListener(
                    git.chexson.chexsonsaeutils.client.gui.tooltip.ProcessingReplacementTooltips::onTooltip);
        }
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (net.neoforged.fml.ModList.get().isLoaded("neoecoae")) {
                git.chexson.chexsonsaeutils.integration.craftingdisplay.neoecoae.NeoEcoCraftingDisplay.register();
                if (FMLEnvironment.dist.isClient())
                    git.chexson.chexsonsaeutils.integration.craftingdisplay.neoecoae.NeoEcoCraftingDisplay.registerClient();
                git.chexson.chexsonsaeutils.bootstrap.terminal.NativeTerminalSubscriptions.register();
                if (FMLEnvironment.dist.isClient())
                    git.chexson.chexsonsaeutils.client.integration.neoecoae.NeoEcoTerminalClientCompat.register();
            }
        });
        event.enqueueWork(ChexsonsaeutilsContent::registerCommonContent);
        // 启动期一次自报构建指纹（B-2）：六轮日志读不出"这一轮装的是哪个构建"——jar 名与版本串
        // 逐字相同时，路径/字节数/lastModified/内容摘要这四格能当场把两次实机分诊成不同构建。
        event.enqueueWork(() -> logBuildFingerprint());
        event.enqueueWork(Chexsonsaeutils::applyDirectProcessingMachineRecipeMappings);
        event.enqueueWork(() -> PatternDetailsHelper.registerDecoder(CustomPatternDecoder.INSTANCE));
        event.enqueueWork(CustomPatternConfigLocator::register);
        event.enqueueWork(CustomPatternUpgradeLocator::register);
        // 升级卡注册：感应卡（appflux）/频道卡（ExtendedAE_Plus）——内部各自
        // ModLoaded 门控 + try-catch(Throwable)，未加载时无副作用
        event.enqueueWork(AppFluxCompat::registerUpgrade);
        event.enqueueWork(ExtendedAePlusCompat::registerUpgrade);
        // useless_mod BigInteger 批次通道的 CPU 回执适配器：内部 ModLoaded 门控 +
        // try-catch(Throwable)，没装或装的版本没这通道时一行都不碰它的类
        event.enqueueWork(UselessBigIntegerCompat::registerCpuAdapter);
        if (ChexsonsaeutilsCompatibilityConfig.featureEnabled(ChexsonsaeutilsCompatibilityConfig.PROCESSING_PATTERN_REPLACEMENT_ENABLED, ChexsonsaeutilsCompatibilityConfig.PROCESSING_PATTERN_REPLACEMENT_ENABLED_KEY)) {
            event.enqueueWork(Chexsonsaeutils::registerProcessingPatternReplacementDecoder);
        }
        if (ChexsonsaeutilsCompatibilityConfig.featureEnabled(ChexsonsaeutilsCompatibilityConfig.FORMAL_MACHINE_PLANNING_AGGREGATION_ENABLED, ChexsonsaeutilsCompatibilityConfig.FORMAL_MACHINE_PLANNING_AGGREGATION_ENABLED_KEY)) {
            event.enqueueWork(Chexsonsaeutils::registerHostBatchPatternDecoder);
        }
        if (ChexsonsaeutilsCompatibilityConfig.featureEnabled(ChexsonsaeutilsCompatibilityConfig.MATTER_MASS_PATTERN_PROVIDER_ENABLED, ChexsonsaeutilsCompatibilityConfig.MATTER_MASS_PATTERN_PROVIDER_ENABLED_KEY)) {
            event.enqueueWork(() -> PatternDetailsHelper.registerDecoder(MatterMassPatternDecoder.INSTANCE));
        }
    }

    /**
     * 启动期打一行构建指纹（B-2，纯读数）：mod 版本串 ＋ 承载本类的 jar 的路径/字节数/
     * lastModified/内容摘要（SHA-256 前 12 位十六进制，自己读 CodeSource 那个 jar 的字节算）。
     *
     * <p>为什么不是 git commit：拿 commit 需要往 {@code build.gradle} 注入资源生成，那文件归作者、
     * 本轮未授权改。这里<b>只</b>交 jar 自指纹——版本串逐字相同的两轮实机，靠这四格当场分得出
     * 装的是不是同一个构建。摘要只当指纹，不参与任何判据。读不到 CodeSource（IDE 里跑 class 目录）
     * 就如实报 {@code sha256=NA}，不编一个值冒充证据。
     */
    private void logBuildFingerprint() {
        logBuildFingerprint(LOGGER, ModIds.MODID, this.modVersion, codeSourceLocation());
    }

    /**
     * 静态出口：把指纹行打到给定 logger（{@link #logBuildFingerprint()} 实例版走这一条，
     * 用例也能对同一条打点口断言"打印者真会打印它"，不必整套 mod 生命周期）。
     */
    static void logBuildFingerprint(org.slf4j.Logger logger, String modId, String version, URL location) {
        logger.info("chexsonsaeutils build fingerprint: {}",
                buildFingerprintLine(modId, version, location));
    }

    /** 承载 {@link Chexsonsaeutils} 的 CodeSource 位置；拿不到返回 {@code null}。 */
    private static URL codeSourceLocation() {
        try {
            var source = Chexsonsaeutils.class.getProtectionDomain().getCodeSource();
            return source == null ? null : source.getLocation();
        } catch (SecurityException failure) {
            return null;
        }
    }

    /**
     * 组装指纹一行（一格一 token，值内无空白）：{@code mod=<> version=<> jar=<> bytes=<>
     * lastModified=<> sha256=<>}。给定位置是个可读文件就逐字节 SHA-256 取前 12 位十六进制；
     * 目录/不可读/为 {@code null} 一律如实 {@code sha256=NA}，绝不返回编造的摘要。
     *
     * <p><b>NeoForge 产线给的是 {@code union:} 嵌套位置</b>（台账 #225 读数 8，2026-10-02 19:55
     * 那一跑实测）：{@code union:/E:/.../chexsonsaeutils-1.21.1-0.3.0.jar%23504!/}。
     * 改前把这种 URL 当普通文件 URI 使，{@code new File(uri)} 直接抛 ⇒ 整行退成
     * {@code bytes=-1 lastModified=-1 sha256=NA}，产线上<b>永远</b>比不出装的是哪一个构建。
     * 现在多走一步：从 {@code union:}/{@code jar:} 那种形状里把<b>外层真实 jar 路径</b>还原出来
     * （切掉 {@code !/} 之后的内层条目、切掉 {@code %23}／{@code #} 之后的 union 挂载号、
     * 百分号解码、Windows 前导斜杠），还原得到就照旧读字节印真 {@code sha256=}，
     * 并在 {@code jarUrl=} 那一格留下原始位置自报口径；还原不到（或还原出来的不是文件）
     * <b>仍如实 {@code NA}</b>——绝不印一个固定值冒充摘要。
     */
    static String buildFingerprintLine(String modId, String version, URL codeSourceLocation) {
        var builder = new StringBuilder();
        builder.append("mod=").append(fold(modId))
                .append(" version=").append(fold(version));
        if (codeSourceLocation == null) {
            return builder.append(" jar=<none> bytes=-1 lastModified=-1 sha256=NA").toString();
        }
        File file;
        try {
            file = new File(codeSourceLocation.toURI());
        } catch (Exception malformed) {
            file = null;
        }
        var nested = file == null || !file.isFile();
        if (nested) {
            // 普通文件那条口拿不到（union:/jar: 嵌套、目录、网络）：只对嵌套 URL 试还原，
            // 不拿别的集合冒充——还原不成就照旧 NA。
            var recovered = recoverNestedJarFile(codeSourceLocation);
            if (recovered == null) {
                return builder.append(" jar=").append(fold(codeSourceLocation.toString()))
                        .append(" bytes=-1 lastModified=-1 sha256=NA").toString();
            }
            file = recovered;
        }
        builder.append(" jar=").append(fold(file.getAbsolutePath()));
        if (nested) {
            builder.append(" jarUrl=").append(fold(codeSourceLocation.toString()));
        }
        builder.append(" bytes=").append(file.length())
                .append(" lastModified=").append(file.lastModified());
        if (!file.isFile()) {
            return builder.append(" sha256=NA").toString();
        }
        builder.append(" sha256=").append(sha256First12(file));
        return builder.toString();
    }

    /**
     * 从 {@code union:}／{@code jar:} 那种嵌套位置里还原<b>外层真实 jar 文件</b>（纯读数口，
     * 不参与任何判据）；还原不到、或那个路径不是文件，交回 {@code null}（调用方照实 {@code NA}）。
     *
     * <p>形状依据（实测那条）：{@code union:/E:/.../chexsonsaeutils-1.21.1-0.3.0.jar%23504!/}
     * ＝「外层 jar 路径」＋「{@code %23}(即 {@code #}) + union 挂载号」＋「{@code !/} + 内层条目」。
     * 因此依次：① 只认 {@code union}/{@code jar} 两种 scheme（别的照旧走原路）；
     * ② 掐掉第一个 {@code !} 之后的一切；③ 掐掉 {@code #}（含 {@code %23} 写法）之后的一切；
     * ④ 百分号解码（{@code +} <b>不</b>当空格——那是 URLDecoder 的表单口径，路径里会读错）；
     * ⑤ Windows 的 {@code /E:/…} 去掉前导斜杠。
     */
    static @Nullable File recoverNestedJarFile(URL location) {
        var scheme = location.getProtocol();
        if (scheme == null
                || !("union".equalsIgnoreCase(scheme) || "jar".equalsIgnoreCase(scheme))) {
            return null;
        }
        var tail = location.getFile();
        if (tail == null || tail.isEmpty()) {
            var described = location.toString();
            var split = described.indexOf(':');
            tail = split < 0 ? described : described.substring(split + 1);
        }
        // jar: 的 getFile() 形如 "file:/E:/x.jar!/a/b.class"：先剥掉里面那一层 scheme 前缀
        while (true) {
            var colon = tail.indexOf(':');
            if (colon <= 0 || !isSchemePrefix(tail.substring(0, colon))) {
                break;
            }
            tail = tail.substring(colon + 1);
        }
        var bang = tail.indexOf('!');
        if (bang >= 0) {
            tail = tail.substring(0, bang);
        }
        // union 的挂载号分隔符：明文 '#' 或它的百分号写法 "%23"（实测那条就是 %23）
        var separator = tail.indexOf('#');
        if (separator < 0) {
            separator = tail.indexOf("%23");
        }
        if (separator >= 0) {
            tail = tail.substring(0, separator);
        }
        var path = percentDecode(tail);
        if (path.length() > 2 && path.charAt(0) == '/'
                && Character.isLetter(path.charAt(1)) && path.charAt(2) == ':') {
            path = path.substring(1);
        }
        if (path.isEmpty()) {
            return null;
        }
        var candidate = new File(path);
        return candidate.isFile() ? candidate : null;
    }

    /** 这一段是不是 scheme 前缀（只允许字母，长度 ≥2，避开 {@code C:} 这种盘符被当成 scheme）。 */
    private static boolean isSchemePrefix(String head) {
        if (head.length() < 2) {
            return false;
        }
        for (int i = 0; i < head.length(); i++) {
            if (!Character.isLetter(head.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 只解 {@code %XX}（{@code +} 原样保留）：路径里一个加号都不许被读成空格。 */
    private static String percentDecode(String raw) {
        var decoded = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            var ch = raw.charAt(i);
            if (ch == '%' && i + 2 < raw.length()) {
                try {
                    decoded.append((char) Integer.parseInt(raw.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException malformed) {
                    // 不是合法的转义：原样留着，交给 File 去判存在性（判不出就 NA，不猜）
                }
            }
            decoded.append(ch);
        }
        return decoded.toString();
    }

    /** 逐字节读文件算 SHA-256，取前 12 位十六进制；任何读失败如实交 {@code NA}。 */
    static String sha256First12(File file) {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            var hex = new StringBuilder(24);
            for (var b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
                if (hex.length() >= 12) {
                    break;
                }
            }
            return hex.substring(0, Math.min(12, hex.length()));
        } catch (Exception failure) {
            return "NA";
        }
    }

    /** 指纹格出口折点：路径里的空格折成 {@code _}，一格一 token。 */
    private static String fold(String raw) {
        return raw == null ? "<null>" : raw.replace(' ', '_');
    }


    private void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == ChexsonsaeutilsCompatibilityConfig.SPEC) {
            applyDirectProcessingMachineRecipeMappings();
        } else if (event.getConfig().getSpec() == PlanningServerConfig.SPEC) {
            PlanningAlgorithmOrderService.global().reload();
        }
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == ChexsonsaeutilsCompatibilityConfig.SPEC) {
            applyDirectProcessingMachineRecipeMappings();
        } else if (event.getConfig().getSpec() == PlanningServerConfig.SPEC) {
            PlanningAlgorithmOrderService.global().reload();
        }
    }

    private void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        ChexsonsaeutilsContent.registerCapabilities(event);
    }

    private void onRegisterPartCapabilities(appeng.api.parts.RegisterPartCapabilitiesEvent event) {
        ChexsonsaeutilsContent.registerPartCapabilities(event);
    }

    private void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        ChexsonsaeutilsNetwork.registerPayloadHandlers(event);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        ChexsonsaeutilsCommand.register(event.getDispatcher());
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new MachineRecipeConfigMappingReloadListener());
    }

    private void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                && level.dimension() == Level.OVERWORLD) {
            var server = level.getServer();
            InfinityCellStore.global().loadOnce(server.getWorldPath(LevelResource.ROOT), server.registryAccess());
            MatterMassStore.global().loadOnce(server.getWorldPath(LevelResource.ROOT), server.registryAccess());
        }
    }

    private void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                && level.dimension() == Level.OVERWORLD) {
            saveInfinityCellStore(level);
            saveMatterMassStore(level);
        }
    }

    private void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                && level.dimension() == Level.OVERWORLD) {
            saveInfinityCellStore(level);
            InfinityCellStore.awaitPendingWrites();
            saveMatterMassStore(level);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        InfinityCellStore.global().save(
                event.getServer().getWorldPath(LevelResource.ROOT),
                event.getServer().registryAccess()
        );
        InfinityCellStore.awaitPendingWrites();
        MatterMassStore.global().save(
                event.getServer().getWorldPath(LevelResource.ROOT),
                event.getServer().registryAccess()
        );
        GraphPlanCapture.awaitPendingWrites();
        git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingPackRulesRegistry.reset();
    }

    private static void saveInfinityCellStore(net.minecraft.server.level.ServerLevel level) {
        var server = level.getServer();
        InfinityCellStore.global().save(server.getWorldPath(LevelResource.ROOT), server.registryAccess());
    }

    private static void saveMatterMassStore(net.minecraft.server.level.ServerLevel level) {
        var server = level.getServer();
        MatterMassStore.global().save(server.getWorldPath(LevelResource.ROOT), server.registryAccess());
    }

    @EventBusSubscriber(modid = ModIds.MODID, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
            ChexsonsaeutilsClientScreens.register(event);
        }

        @SubscribeEvent
        public static void onClientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
            // M2：在 RegisterMenuScreensEvent 应用完后覆盖 AE2 确认屏绑定（enqueueWork 保证时点）。
            event.enqueueWork(git.chexson.chexsonsaeutils.client.gui.crafting.WideAmountCraftConfirmRegistration::register);
        }

        @SubscribeEvent
        public static void onRegisterItemColors(net.neoforged.neoforge.client.event.RegisterColorHandlersEvent.Item event) {
            PatternItemColorRegistration.register(event);
        }

        @SubscribeEvent
        public static void onAddPackFinders(net.neoforged.neoforge.event.AddPackFindersEvent event) {
            DyeablePatternPackRegistration.register(event);
        }

        @SubscribeEvent
        public static void onRegisterExtensions(net.neoforged.fml.event.lifecycle.FMLConstructModEvent event) {
            event.enqueueWork(() -> {
                net.neoforged.fml.ModContainer container = net.neoforged.fml.ModList.get()
                        .getModContainerById(ModIds.MODID)
                        .orElseThrow();
                container.registerExtensionPoint(IConfigScreenFactory.class,
                        (mc, parent) -> new git.chexson.chexsonsaeutils.client.gui.config.ChexsonsaeutilsConfigHomeScreen(
                                parent,
                                container,
                                git.chexson.chexsonsaeutils.client.gui.config
                                        .NetworkPlanningAlgorithmOrderClientBridge.instance()
                        ));
            });
        }
    }

    private static void registerProcessingPatternReplacementDecoder() {
        IPatternDetailsDecoder decoder = new ProcessingPatternReplacementDecoder();
        List<IPatternDetailsDecoder> decoders = PatternDetailsHelperAccessor.chexsonsaeutils$getDecoders();
        decoders.removeIf(existingDecoder -> existingDecoder.getClass() == decoder.getClass());
        decoders.add(0, decoder);
    }

    private static void registerHostBatchPatternDecoder() {
        IPatternDetailsDecoder decoder = new HostBatchPatternDecoder();
        List<IPatternDetailsDecoder> decoders = PatternDetailsHelperAccessor.chexsonsaeutils$getDecoders();
        decoders.removeIf(existingDecoder -> existingDecoder.getClass() == decoder.getClass());
        decoders.add(0, decoder);
    }

    private static void applyDirectProcessingMachineRecipeMappings() {
        MachineRecipeConfigMappingRegistry.instance().replaceUserConfigMappings(
                MachineRecipeUserConfigStore.instance().loadMappings()
        );
        MachineRecipeConfigMappingRegistry.instance().replaceMappings(
                List.copyOf(ChexsonsaeutilsCompatibilityConfig.AE_DIRECT_PROCESSING_MACHINE_RECIPE_MAPPINGS.get())
        );
    }
}
