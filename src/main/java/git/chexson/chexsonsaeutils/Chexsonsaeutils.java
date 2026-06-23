package git.chexson.chexsonsaeutils;

import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.crafting.PatternDetailsHelper;
import git.chexson.chexsonsaeutils.block.crafting.AEDirectProcessingMachineBlock;
import git.chexson.chexsonsaeutils.block.crafting.AE2ParallelCpuToolBlock;
import git.chexson.chexsonsaeutils.block.crafting.HighCapacityCraftingMachineBlock;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.crafting.HighCapacityCraftingMachineBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.directprocessing.AEDirectProcessingMachineBlockEntity;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.config.FeatureGates;
import git.chexson.chexsonsaeutils.client.ae2.DyeablePatternPackRegistration;
import git.chexson.chexsonsaeutils.client.ae2.PatternItemColorRegistration;
import git.chexson.chexsonsaeutils.menu.implementations.AEDirectProcessingMachineMenu;
import git.chexson.chexsonsaeutils.menu.implementations.HighCapacityCraftingMachineMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu;
import git.chexson.chexsonsaeutils.mixin.ae2.crafting.PatternDetailsHelperAccessor;
import git.chexson.chexsonsaeutils.pattern.replacement.ProcessingPatternReplacementDecoder;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.MinecraftForge;

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
    public static final Supplier<AE2ParallelCpuToolBlock> AE2_PARALLEL_CPU_TOOL_BLOCK =
            ChexsonsaeutilsContent.AE2_PARALLEL_CPU_TOOL_BLOCK;
    public static final Supplier<Item> AE2_PARALLEL_CPU_TOOL_ITEM =
            ChexsonsaeutilsContent.AE2_PARALLEL_CPU_TOOL_ITEM;
    public static final Supplier<BlockEntityType<AE2ParallelCpuToolBlockEntity>>
            AE2_PARALLEL_CPU_TOOL_BLOCK_ENTITY =
                    ChexsonsaeutilsContent.AE2_PARALLEL_CPU_TOOL_BLOCK_ENTITY;
    public static final Supplier<MenuType<ParallelCraftingCPUMenu>> AE2_PARALLEL_CPU_TOOL_CPU_MENU =
            ChexsonsaeutilsContent.AE2_PARALLEL_CPU_TOOL_CPU_MENU;
    public static final Supplier<AEDirectProcessingMachineBlock> AE_DIRECT_PROCESSING_MACHINE_BLOCK =
            ChexsonsaeutilsContent.AE_DIRECT_PROCESSING_MACHINE_BLOCK;
    public static final Supplier<Item> AE_DIRECT_PROCESSING_MACHINE_ITEM =
            ChexsonsaeutilsContent.AE_DIRECT_PROCESSING_MACHINE_ITEM;
    public static final Supplier<BlockEntityType<AEDirectProcessingMachineBlockEntity>>
            AE_DIRECT_PROCESSING_MACHINE_BLOCK_ENTITY =
                    ChexsonsaeutilsContent.AE_DIRECT_PROCESSING_MACHINE_BLOCK_ENTITY;
    public static final Supplier<MenuType<AEDirectProcessingMachineMenu>> AE_DIRECT_PROCESSING_MACHINE_MENU =
            ChexsonsaeutilsContent.AE_DIRECT_PROCESSING_MACHINE_MENU;

    @SuppressWarnings("removal")
    public Chexsonsaeutils() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ChexsonsaeutilsCompatibilityConfig.SPEC);
        ChexsonsaeutilsContent.register(modEventBus);

        modEventBus.addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.addGenericListener(BlockEntity.class, ChexsonsaeutilsContent::onAttachCapabilities);
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(ChexsonsaeutilsContent::registerCommonContent);
        if (FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.PROCESSING_PATTERN_REPLACEMENT_ENABLED, "processingPatternReplacementEnabled")) {
            event.enqueueWork(Chexsonsaeutils::registerProcessingPatternReplacementDecoder);
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> ChexsonsaeutilsContent.registerClientScreens());
        }

        @SubscribeEvent
        public static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
            if (FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.DYEABLE_PATTERNS_ENABLED, "dyeablePatternsEnabled")) {
                PatternItemColorRegistration.register(event);
            }
        }

        @SubscribeEvent
        public static void onAddPackFinders(AddPackFindersEvent event) {
            if (FeatureGates.isEnabled(ChexsonsaeutilsCompatibilityConfig.DYEABLE_PATTERNS_ENABLED, "dyeablePatternsEnabled")) {
                DyeablePatternPackRegistration.register(event);
            }
        }
    }

    private static void registerProcessingPatternReplacementDecoder() {
        IPatternDetailsDecoder decoder = new ProcessingPatternReplacementDecoder();
        List<IPatternDetailsDecoder> decoders = PatternDetailsHelperAccessor.chexsonsaeutils$getDecoders();
        decoders.removeIf(existingDecoder -> existingDecoder.getClass() == decoder.getClass());
        decoders.add(0, decoder);
    }

    // Backward-compatible delegates for legacy callers

    public static String emitterRegistryPath() {
        return ChexsonsaeutilsContent.emitterRegistryPath();
    }

    public static String menuBindingKey() {
        return ChexsonsaeutilsContent.menuBindingKey();
    }

    public static String screenBindingKey() {
        return ChexsonsaeutilsContent.screenBindingKey();
    }

    public static String runtimePartType() {
        return ChexsonsaeutilsContent.runtimePartType();
    }
}
