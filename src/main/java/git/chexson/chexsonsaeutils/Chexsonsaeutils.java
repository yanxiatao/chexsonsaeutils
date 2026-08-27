package git.chexson.chexsonsaeutils;

import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.crafting.PatternDetailsHelper;
import git.chexson.chexsonsaeutils.block.crafting.AEDirectProcessingMachineBlock;
import git.chexson.chexsonsaeutils.block.crafting.AE2ParallelCpuToolBlock;
import git.chexson.chexsonsaeutils.block.crafting.HighCapacityCraftingMachineBlock;
import git.chexson.chexsonsaeutils.blockentity.directprocessing.AEDirectProcessingMachineBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.crafting.HighCapacityCraftingMachineBlockEntity;
import git.chexson.chexsonsaeutils.cell.InfinityCellStore;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.config.FeatureGates;
import git.chexson.chexsonsaeutils.cell.CellCommand;
import git.chexson.chexsonsaeutils.cell.CellRegistration;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineAggregatedPatternDecoder;
import git.chexson.chexsonsaeutils.crafting.custompattern.CustomPatternDecoder;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigMappingRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigMappingReloadListener;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeUserConfigStore;
import git.chexson.chexsonsaeutils.client.ae2.PatternItemColorRegistration;
import git.chexson.chexsonsaeutils.client.ae2.DyeablePatternPackRegistration;
import git.chexson.chexsonsaeutils.menu.custompatternprovider.CustomPatternProviderMenu;
import git.chexson.chexsonsaeutils.menu.implementations.AEDirectProcessingMachineMenu;
import git.chexson.chexsonsaeutils.menu.implementations.HighCapacityCraftingMachineMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.PatternDetailsHelperAccessor;
import git.chexson.chexsonsaeutils.client.SlotNumberOverlay;
import git.chexson.chexsonsaeutils.integration.appflux.AppFluxCompat;
import git.chexson.chexsonsaeutils.integration.extendedae_plus.ExtendedAePlusCompat;
import git.chexson.chexsonsaeutils.menu.custompatternconfig.CustomPatternConfigLocator;
import git.chexson.chexsonsaeutils.menu.custompatternupgrade.CustomPatternUpgradeLocator;
import git.chexson.chexsonsaeutils.network.directprocessing.DirectProcessingJeiImportPayload;
import git.chexson.chexsonsaeutils.network.custompatternencoder.CustomPatternEncoderUpdatePayload;
import git.chexson.chexsonsaeutils.network.custompatternencoder.CustomPatternSlotChangePacket;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingPatternReplacementDecoder;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.RegisterEvent;
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
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.List;
import java.util.function.Supplier;

@Mod(Chexsonsaeutils.MODID)
public class Chexsonsaeutils {

    public static final String MODID = "chexsonsaeutils";

    public static final Supplier<HighCapacityCraftingMachineBlock> HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK =
            ChexsonsaeutilsContent.HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK;
    public static final Supplier<Item> HIGH_CAPACITY_CRAFTING_MACHINE_ITEM =
            ChexsonsaeutilsContent.HIGH_CAPACITY_CRAFTING_MACHINE_ITEM;
    public static final Supplier<BlockEntityType<HighCapacityCraftingMachineBlockEntity>>
            HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK_ENTITY =
                    ChexsonsaeutilsContent.HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK_ENTITY;
    public static final Supplier<MenuType<HighCapacityCraftingMachineMenu>> HIGH_CAPACITY_CRAFTING_MACHINE_MENU =
            ChexsonsaeutilsContent.HIGH_CAPACITY_CRAFTING_MACHINE_MENU;
    public static final Supplier<Item> MULTI_LEVEL_EMITTER_ITEM =
            ChexsonsaeutilsContent.MULTI_LEVEL_EMITTER_ITEM;
    public static final Supplier<MenuType<MultiLevelEmitterMenu.RuntimeMenu>> MULTI_LEVEL_EMITTER_MENU =
            ChexsonsaeutilsContent.MULTI_LEVEL_EMITTER_MENU;
    public static final Supplier<AEDirectProcessingMachineBlock> AE_DIRECT_PROCESSING_MACHINE_BLOCK =
            ChexsonsaeutilsContent.AE_DIRECT_PROCESSING_MACHINE_BLOCK;
    public static final Supplier<Item> AE_DIRECT_PROCESSING_MACHINE_ITEM =
            ChexsonsaeutilsContent.AE_DIRECT_PROCESSING_MACHINE_ITEM;
    public static final Supplier<BlockEntityType<AEDirectProcessingMachineBlockEntity>>
            AE_DIRECT_PROCESSING_MACHINE_BLOCK_ENTITY =
                    ChexsonsaeutilsContent.AE_DIRECT_PROCESSING_MACHINE_BLOCK_ENTITY;
    public static final Supplier<MenuType<AEDirectProcessingMachineMenu>> AE_DIRECT_PROCESSING_MACHINE_MENU =
            ChexsonsaeutilsContent.AE_DIRECT_PROCESSING_MACHINE_MENU;
    public static final Supplier<AE2ParallelCpuToolBlock> AE2_PARALLEL_CPU_TOOL_BLOCK =
            ChexsonsaeutilsContent.AE2_PARALLEL_CPU_TOOL_BLOCK;
    public static final Supplier<Item> AE2_PARALLEL_CPU_TOOL_ITEM =
            ChexsonsaeutilsContent.AE2_PARALLEL_CPU_TOOL_ITEM;
    public static final Supplier<BlockEntityType<AE2ParallelCpuToolBlockEntity>>
            AE2_PARALLEL_CPU_TOOL_BLOCK_ENTITY =
                    ChexsonsaeutilsContent.AE2_PARALLEL_CPU_TOOL_BLOCK_ENTITY;
    public static final Supplier<MenuType<ParallelCraftingCPUMenu>> AE2_PARALLEL_CPU_TOOL_CPU_MENU =
            ChexsonsaeutilsContent.AE2_PARALLEL_CPU_TOOL_CPU_MENU;
    public static final Supplier<MenuType<CustomPatternProviderMenu<?>>> CUSTOM_PATTERN_PROVIDER_MENU =
            ChexsonsaeutilsContent.CUSTOM_PATTERN_PROVIDER_MENU;

    public Chexsonsaeutils(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, ChexsonsaeutilsCompatibilityConfig.SPEC);
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
        // 槽位编号叠加为纯客户端功能：仅客户端注册，避免专用服务端构造期经方法引用
        // 加载 SlotNumberOverlay（引用 Minecraft/LocalPlayer）触发 dist 校验失败。
        if (FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(SlotNumberOverlay::registerKeyMapping);
            NeoForge.EVENT_BUS.addListener(SlotNumberOverlay::onScreenRender);
            NeoForge.EVENT_BUS.addListener(SlotNumberOverlay::onScreenKeyPressed);
            NeoForge.EVENT_BUS.addListener(SlotNumberOverlay::onScreenClosing);
        }
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(ChexsonsaeutilsContent::registerCommonContent);
        event.enqueueWork(Chexsonsaeutils::applyDirectProcessingMachineRecipeMappings);
        event.enqueueWork(() -> PatternDetailsHelper.registerDecoder(CustomPatternDecoder.INSTANCE));
        event.enqueueWork(CustomPatternConfigLocator::register);
        event.enqueueWork(CustomPatternUpgradeLocator::register);
        // 升级卡注册：感应卡（appflux）/频道卡（ExtendedAE_Plus）——内部各自
        // ModLoaded 门控 + try-catch(Throwable)，未加载时无副作用
        event.enqueueWork(AppFluxCompat::registerUpgrade);
        event.enqueueWork(ExtendedAePlusCompat::registerUpgrade);
        if (FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.PROCESSING_PATTERN_REPLACEMENT_ENABLED, "processingPatternReplacementEnabled")) {
            event.enqueueWork(Chexsonsaeutils::registerProcessingPatternReplacementDecoder);
        }
        if (FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.FORMAL_MACHINE_PLANNING_AGGREGATION_ENABLED, "formalMachinePlanningAggregationEnabled")) {
            event.enqueueWork(Chexsonsaeutils::registerFormalMachineAggregatedPatternDecoder);
        }
    }

    private void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == ChexsonsaeutilsCompatibilityConfig.SPEC) {
            applyDirectProcessingMachineRecipeMappings();
        }
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == ChexsonsaeutilsCompatibilityConfig.SPEC) {
            applyDirectProcessingMachineRecipeMappings();
        }
    }

    private void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        ChexsonsaeutilsContent.registerCapabilities(event);
    }

    private void onRegisterPartCapabilities(appeng.api.parts.RegisterPartCapabilitiesEvent event) {
        ChexsonsaeutilsContent.registerPartCapabilities(event);
    }

    private void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(
                DirectProcessingJeiImportPayload.TYPE,
                DirectProcessingJeiImportPayload.STREAM_CODEC,
                DirectProcessingJeiImportPayload::handle
        );
        event.registrar("1").playToServer(
                CustomPatternSlotChangePacket.TYPE,
                CustomPatternSlotChangePacket.STREAM_CODEC,
                CustomPatternSlotChangePacket::handleOnServer
        );
        event.registrar("1").playToClient(
                CustomPatternEncoderUpdatePayload.TYPE,
                CustomPatternEncoderUpdatePayload.STREAM_CODEC,
                CustomPatternEncoderUpdatePayload::handle
        );
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CellCommand.register(event.getDispatcher());
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new MachineRecipeConfigMappingReloadListener());
    }

    private void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                && level.dimension() == Level.OVERWORLD) {
            var server = level.getServer();
            InfinityCellStore.global().loadOnce(server.getWorldPath(LevelResource.ROOT), server.registryAccess());
        }
    }

    private void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                && level.dimension() == Level.OVERWORLD) {
            saveInfinityCellStore(level);
        }
    }

    private void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                && level.dimension() == Level.OVERWORLD) {
            saveInfinityCellStore(level);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        InfinityCellStore.global().save(
                event.getServer().getWorldPath(LevelResource.ROOT),
                event.getServer().registryAccess()
        );
    }

    private static void saveInfinityCellStore(net.minecraft.server.level.ServerLevel level) {
        var server = level.getServer();
        InfinityCellStore.global().save(server.getWorldPath(LevelResource.ROOT), server.registryAccess());
    }

    @EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
            ChexsonsaeutilsContent.registerClientScreens(event);
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
                        .getModContainerById(MODID)
                        .orElseThrow();
                container.registerExtensionPoint(IConfigScreenFactory.class,
                        (mc, parent) -> new ConfigurationScreen(container, parent));
            });
        }
    }

    private static void registerProcessingPatternReplacementDecoder() {
        IPatternDetailsDecoder decoder = new ProcessingPatternReplacementDecoder();
        List<IPatternDetailsDecoder> decoders = PatternDetailsHelperAccessor.chexsonsaeutils$getDecoders();
        decoders.removeIf(existingDecoder -> existingDecoder.getClass() == decoder.getClass());
        decoders.add(0, decoder);
    }

    private static void registerFormalMachineAggregatedPatternDecoder() {
        IPatternDetailsDecoder decoder = new FormalMachineAggregatedPatternDecoder();
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
