package git.chexson.chexsonsaeutils;

import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.crafting.PatternDetailsHelper;
import git.chexson.chexsonsaeutils.block.crafting.AEDirectProcessingMachineBlock;
import git.chexson.chexsonsaeutils.block.crafting.AE2ParallelCpuToolBlock;
import git.chexson.chexsonsaeutils.block.crafting.HighCapacityCraftingMachineBlock;
import git.chexson.chexsonsaeutils.blockentity.directprocessing.AEDirectProcessingMachineBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.crafting.HighCapacityCraftingMachineBlockEntity;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.config.FormalMachinePlanningAggregationFeatureGate;
import git.chexson.chexsonsaeutils.config.ProcessingPatternReplacementFeatureGate;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineAggregatedPatternDecoder;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigMappingRegistry;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeConfigMappingReloadListener;
import git.chexson.chexsonsaeutils.crafting.directprocessing.MachineRecipeUserConfigStore;
import git.chexson.chexsonsaeutils.client.ae2.PatternItemColorRegistration;
import git.chexson.chexsonsaeutils.client.ae2.DyeablePatternPackRegistration;
import git.chexson.chexsonsaeutils.menu.implementations.AEDirectProcessingMachineMenu;
import git.chexson.chexsonsaeutils.menu.implementations.HighCapacityCraftingMachineMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.PatternDetailsHelperAccessor;
import git.chexson.chexsonsaeutils.network.directprocessing.DirectProcessingJeiImportPayload;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingPatternReplacementDecoder;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
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

    public Chexsonsaeutils(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, ChexsonsaeutilsCompatibilityConfig.SPEC);
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (mc, parent) -> new ConfigurationScreen(modContainer, parent));
        ChexsonsaeutilsContent.register(modEventBus);

        modEventBus.addListener(this::onCommonSetup);
        modEventBus.addListener(this::onConfigLoad);
        modEventBus.addListener(this::onConfigReload);
        modEventBus.addListener(this::onRegisterCapabilities);
        modEventBus.addListener(this::onRegisterPayloadHandlers);
        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(ChexsonsaeutilsContent::registerCommonContent);
        event.enqueueWork(Chexsonsaeutils::applyDirectProcessingMachineRecipeMappings);
        if (ProcessingPatternReplacementFeatureGate.isEnabledAtStartup()) {
            event.enqueueWork(Chexsonsaeutils::registerProcessingPatternReplacementDecoder);
        }
        if (FormalMachinePlanningAggregationFeatureGate.isEnabledAtStartup()) {
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

    private void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(
                DirectProcessingJeiImportPayload.TYPE,
                DirectProcessingJeiImportPayload.STREAM_CODEC,
                DirectProcessingJeiImportPayload::handle
        );
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new MachineRecipeConfigMappingReloadListener());
    }

    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
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
