package git.chexson.chexsonsaeutils.registration;

import appeng.api.AECapabilities;
import appeng.api.parts.PartModels;
import appeng.api.parts.RegisterPartCapabilitiesEvent;
import appeng.api.upgrades.Upgrades;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.core.definitions.AEItems;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.modid.ModIds;
import git.chexson.chexsonsaeutils.cell.CellRegistration;
import git.chexson.chexsonsaeutils.cell.InfinityCellItem;
import git.chexson.chexsonsaeutils.block.crafting.AEDirectProcessingMachineBlock;
import git.chexson.chexsonsaeutils.block.crafting.AE2ParallelCpuToolBlock;
import git.chexson.chexsonsaeutils.block.crafting.ExtremeAssemblerBlock;
import git.chexson.chexsonsaeutils.block.debug.AutoItemGenBlock;
import git.chexson.chexsonsaeutils.block.custompatternprovider.CustomPatternProviderBlock;
import git.chexson.chexsonsaeutils.block.mattermassprovider.MatterMassPatternProviderBlock;
import git.chexson.chexsonsaeutils.bootstrap.mattermass.MatterMassPatternProviderMenuRegistration;
import git.chexson.chexsonsaeutils.blockentity.debug.AutoItemGenBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.custompatternprovider.CustomPatternProviderBlockEntity;
import git.chexson.chexsonsaeutils.bootstrap.patternprovider.CustomPatternProviderBlockEntityRegistration;
import git.chexson.chexsonsaeutils.blockentity.mattermassprovider.MatterMassPatternProviderBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.directprocessing.AEDirectProcessingMachineBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.crafting.ExtremeAssemblerBlockEntity;
import git.chexson.chexsonsaeutils.cell.MatterMassCellRegistration;
import git.chexson.chexsonsaeutils.crafting.mattermass.MatterMassPatternRegistration;
import git.chexson.chexsonsaeutils.item.mattermass.MatterMassRegistration;
import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingMockRecipe;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingRecipeRegistration;
import git.chexson.chexsonsaeutils.crafting.custompattern.CustomPatternRegistration;
import git.chexson.chexsonsaeutils.menu.custompatternprovider.CustomPatternProviderMenu;
import git.chexson.chexsonsaeutils.menu.implementations.AEDirectProcessingMachineMenu;
import git.chexson.chexsonsaeutils.menu.implementations.ExtremeAssemblerMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu;
import git.chexson.chexsonsaeutils.menu.custompatternencoder.CustomPatternEncoderMenu;
import git.chexson.chexsonsaeutils.menu.custompatternupgrade.CustomPatternUpgradeMenu;
import git.chexson.chexsonsaeutils.bootstrap.automation.MultiLevelEmitterItem;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import git.chexson.chexsonsaeutils.parts.custompatternprovider.CustomPatternProviderPart;
import git.chexson.chexsonsaeutils.bootstrap.patternprovider.CustomPatternProviderPartRegistration;
import git.chexson.chexsonsaeutils.bootstrap.patternprovider.CustomPatternProviderRegistration;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.util.function.Supplier;

public final class ChexsonsaeutilsContent {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ModIds.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ModIds.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ModIds.MODID);
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, ModIds.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ModIds.MODID);
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, ModIds.MODID);

    public static final RegisteredBlock<ExtremeAssemblerBlock> EXTREME_ASSEMBLER =
            registerBlockWithItem("extreme_assembler", ExtremeAssemblerBlock::new);
    public static final RegisteredBlock<AEDirectProcessingMachineBlock> AE_DIRECT_PROCESSING_MACHINE =
            registerBlockWithItem("ae_direct_processing_machine", AEDirectProcessingMachineBlock::new);
    public static final RegisteredBlock<AE2ParallelCpuToolBlock> AE2_PARALLEL_CPU_TOOL =
            registerBlockWithItem("ae2_parallel_cpu_tool", AE2ParallelCpuToolBlock::new);
    public static final Supplier<ExtremeAssemblerBlock> EXTREME_ASSEMBLER_BLOCK =
            EXTREME_ASSEMBLER.block();
    public static final Supplier<Item> EXTREME_ASSEMBLER_ITEM =
            EXTREME_ASSEMBLER.item();
    public static final Supplier<AEDirectProcessingMachineBlock> AE_DIRECT_PROCESSING_MACHINE_BLOCK =
            AE_DIRECT_PROCESSING_MACHINE.block();
    public static final Supplier<Item> AE_DIRECT_PROCESSING_MACHINE_ITEM =
            AE_DIRECT_PROCESSING_MACHINE.item();
    public static final Supplier<AE2ParallelCpuToolBlock> AE2_PARALLEL_CPU_TOOL_BLOCK =
            AE2_PARALLEL_CPU_TOOL.block();
    public static final Supplier<Item> AE2_PARALLEL_CPU_TOOL_ITEM =
            AE2_PARALLEL_CPU_TOOL.item();
    public static final Supplier<Item> MULTI_LEVEL_EMITTER_ITEM =
            ITEMS.register(MultiLevelEmitterItem.id(), MultiLevelEmitterItem::createItem);
    public static final Supplier<Item> PLAYER_SIMULATION_PLANE_ITEM =
            ITEMS.register("player_simulation_plane",git.chexson.chexsonsaeutils.bootstrap.playersimulation.PlayerSimulationRegistration::createItem);
    public static final Supplier<MenuType<git.chexson.chexsonsaeutils.menu.playersimulation.SimulationAmountMenu>> SIMULATION_AMOUNT_MENU =
            MENU_TYPES.register("simulation_amount",() -> git.chexson.chexsonsaeutils.menu.playersimulation.SimulationAmountMenu.TYPE);
    public static final Supplier<MenuType<git.chexson.chexsonsaeutils.menu.playersimulation.PlayerSimulationMenu>> PLAYER_SIMULATION_PLANE_MENU =
            MENU_TYPES.register("player_simulation_plane",() -> git.chexson.chexsonsaeutils.menu.playersimulation.PlayerSimulationMenu.TYPE);
    public static final Supplier<InfinityCellItem> INFINITY_CELL_ITEM =
            ITEMS.register(CellRegistration.CELL_ITEM_ID, InfinityCellItem::new);
    public static final RegisteredBlock<AutoItemGenBlock> AUTO_ITEM_GEN =
            registerBlockWithItem("auto_item_gen", AutoItemGenBlock::new);
    public static final Supplier<AutoItemGenBlock> AUTO_ITEM_GEN_BLOCK = AUTO_ITEM_GEN.block();
    public static final Supplier<Item> AUTO_ITEM_GEN_ITEM = AUTO_ITEM_GEN.item();
    public static final RegisteredBlock<MatterMassPatternProviderBlock> MATTER_MASS_PATTERN_PROVIDER =
            registerBlockWithItem("matter_mass_pattern_provider", MatterMassPatternProviderBlock::new);
    public static final Supplier<MatterMassPatternProviderBlock> MATTER_MASS_PATTERN_PROVIDER_BLOCK =
            MATTER_MASS_PATTERN_PROVIDER.block();
    public static final Supplier<Item> MATTER_MASS_PATTERN_PROVIDER_ITEM =
            MATTER_MASS_PATTERN_PROVIDER.item();
    public static final Supplier<BlockEntityType<AutoItemGenBlockEntity>> AUTO_ITEM_GEN_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("auto_item_gen",
                    () -> {
                        var block = AUTO_ITEM_GEN_BLOCK.get();
                        return BlockEntityType.Builder.of(AutoItemGenBlockEntity::new, block).build(null);
                    });
    public static final Supplier<BlockEntityType<ExtremeAssemblerBlockEntity>>
            EXTREME_ASSEMBLER_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
                    "extreme_assembler",
                    () -> BlockEntityType.Builder.of(
                            ExtremeAssemblerBlockEntity::new,
                            EXTREME_ASSEMBLER_BLOCK.get()
                    ).build(null)
            );
    public static final Supplier<BlockEntityType<AEDirectProcessingMachineBlockEntity>>
            AE_DIRECT_PROCESSING_MACHINE_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
                    "ae_direct_processing_machine",
                    () -> BlockEntityType.Builder.of(
                            AEDirectProcessingMachineBlockEntity::new,
                            AE_DIRECT_PROCESSING_MACHINE_BLOCK.get()
                    ).build(null)
            );
    public static final Supplier<BlockEntityType<AE2ParallelCpuToolBlockEntity>>
            AE2_PARALLEL_CPU_TOOL_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
                    "ae2_parallel_cpu_tool",
                    () -> BlockEntityType.Builder.of(
                            AE2ParallelCpuToolBlockEntity::new,
                            AE2_PARALLEL_CPU_TOOL_BLOCK.get()
                    ).build(null)
             );

    public static final Supplier<BlockEntityType<MatterMassPatternProviderBlockEntity>>
            MATTER_MASS_PATTERN_PROVIDER_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
                    "matter_mass_pattern_provider",
                    () -> BlockEntityType.Builder.of(
                            MatterMassPatternProviderBlockEntity::new,
                            MATTER_MASS_PATTERN_PROVIDER_BLOCK.get()
                    ).build(null)
            );

    public static final Supplier<MenuType<ExtremeAssemblerMenu>> EXTREME_ASSEMBLER_MENU =
            MENU_TYPES.register("extreme_assembler", () -> ExtremeAssemblerMenu.TYPE);
    public static final Supplier<MenuType<AEDirectProcessingMachineMenu>> AE_DIRECT_PROCESSING_MACHINE_MENU =
            MENU_TYPES.register("ae_direct_processing_machine", () -> AEDirectProcessingMachineMenu.TYPE);
    public static final Supplier<MenuType<ParallelCraftingCPUMenu>> AE2_PARALLEL_CPU_TOOL_CPU_MENU =
            MENU_TYPES.register("ae2_parallel_cpu_tool_cpu", () -> ParallelCraftingCPUMenu.TYPE);
    public static final Supplier<MenuType<CustomPatternProviderMenu<?>>> CUSTOM_PATTERN_PROVIDER_MENU =
            MENU_TYPES.register("custom_pattern_provider", () -> CustomPatternProviderMenu.TYPE);
    public static final Supplier<MenuType<CustomPatternEncoderMenu>> CUSTOM_PATTERN_ENCODER_MENU =
            MENU_TYPES.register("custom_pattern_encoder", () -> CustomPatternEncoderMenu.TYPE);
    public static final Supplier<MenuType<CustomPatternUpgradeMenu>> CUSTOM_PATTERN_UPGRADE_MENU =
            MENU_TYPES.register("custom_pattern_upgrade", () -> CustomPatternUpgradeMenu.TYPE);
    public static final Supplier<MenuType<MultiLevelEmitterMenu.RuntimeMenu>> MULTI_LEVEL_EMITTER_MENU =
            MENU_TYPES.register(
                    MultiLevelEmitterMenu.registrationKey(),
                    () -> IMenuTypeExtension.create(MultiLevelEmitterMenu.RuntimeMenu::fromNetwork)
            );

    public static final Supplier<CreativeModeTab> CHEXSONSAEUTILS_TAB =
            CREATIVE_MODE_TABS.register("chexsonsaeutils", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.chexsonsaeutils"))
                    .icon(() -> new ItemStack(EXTREME_ASSEMBLER_ITEM.get()))
                    .displayItems((parameters, output) -> {
                        git.chexson.chexsonsaeutils.bootstrap.controller.SelfPoweredControllerRegistration.displayItems(output);
                        output.accept(git.chexson.chexsonsaeutils.bootstrap.patterntransmitter.PatternTransmitterRegistration.ITEM.get());
                        output.accept(MULTI_LEVEL_EMITTER_ITEM.get());
                        output.accept(PLAYER_SIMULATION_PLANE_ITEM.get());
                        output.accept(EXTREME_ASSEMBLER_ITEM.get());
                        output.accept(AE_DIRECT_PROCESSING_MACHINE_ITEM.get());
                        output.accept(AE2_PARALLEL_CPU_TOOL_ITEM.get());
                        output.accept(CustomPatternRegistration.CUSTOM_PATTERN_ITEM.get());
                        output.accept(CustomPatternProviderRegistration.CUSTOM_PATTERN_PROVIDER_ITEM.get());
                        output.accept(CustomPatternProviderPartRegistration.CUSTOM_PATTERN_PROVIDER_PART_ITEM.get());
                        output.accept(git.chexson.chexsonsaeutils.bootstrap.terminal.BigIntTerminalRegistration.ITEM.get());
                        output.accept(INFINITY_CELL_ITEM.get());
                        output.accept(MATTER_MASS_PATTERN_PROVIDER_ITEM.get());
                        output.accept(MatterMassRegistration.MATTER_MASS_ITEM.get());
                        output.accept(MatterMassPatternRegistration.MATTER_MASS_PATTERN_ITEM.get());
                        if (!FMLLoader.isProduction()) {
                            output.accept(AUTO_ITEM_GEN_ITEM.get());
                        }
                    })
                    .build());

    private ChexsonsaeutilsContent() {
    }

    public static void register(IEventBus modEventBus) {
        git.chexson.chexsonsaeutils.bootstrap.controller.SelfPoweredControllerRegistration.register(modEventBus);
        git.chexson.chexsonsaeutils.bootstrap.patterntransmitter.PatternTransmitterRegistration.register(modEventBus);
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        MENU_TYPES.register(modEventBus);
        // mock 配方两枚把手自持在 crafting.directprocessing 宿主（D7-1），本处只 dispatch 一次
        DirectProcessingRecipeRegistration.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        DATA_COMPONENT_TYPES.register(modEventBus);
        // 定制样板三枚把手自持在 crafting.custompattern 宿主（D7-2），本处只 dispatch 一次
        CustomPatternRegistration.register(modEventBus);
        // 面板把手自持在 parts.custompatternprovider 宿主（D7-3），本处只 dispatch 一次
        CustomPatternProviderPartRegistration.register(modEventBus);
        // 定制样板供应器方块／物品把手自持在 parts.custompatternprovider 宿主（D7-4），本处只 dispatch 一次
        CustomPatternProviderRegistration.register(modEventBus);
        // 定制样板供应器方块实体类型把手自持在 blockentity.custompatternprovider 宿主（D7-6），本处只 dispatch 一次
        CustomPatternProviderBlockEntityRegistration.register(modEventBus);
        // 物质团样板物品／编码组件把手自持在 crafting.mattermass 宿主（M1-B），本处只 dispatch 一次
        MatterMassPatternRegistration.register(modEventBus);
        // 物质团供应器菜单把手自持在 block.mattermassprovider 宿主（M1-C），本处只 dispatch 一次
        MatterMassPatternProviderMenuRegistration.register(modEventBus);
        // 物质团物品／查看菜单／UUID 三枚把手自持在 item.mattermass 宿主（M2-A），本处只 dispatch 一次
        MatterMassRegistration.register(modEventBus);
        git.chexson.chexsonsaeutils.bootstrap.terminal.BigIntTerminalRegistration.register(modEventBus);
    }

    public static void registerCommonContent() {
        git.chexson.chexsonsaeutils.bootstrap.controller.SelfPoweredControllerRegistration.bootstrap();
        git.chexson.chexsonsaeutils.bootstrap.patterntransmitter.PatternTransmitterRegistration.bootstrap();
        git.chexson.chexsonsaeutils.bootstrap.terminal.BigIntTerminalRegistration.bootstrap();
        registerExtremeAssemblerBootstrap();
        registerAEDirectProcessingMachineBootstrap();
        registerAE2ParallelCpuToolBootstrap();
        registerAutoItemGenBootstrap();
        registerCustomPatternProviderBootstrap();
        registerMatterMassPatternProviderBootstrap();
        registerMultiLevelEmitterBootstrap();
        git.chexson.chexsonsaeutils.bootstrap.playersimulation.PlayerSimulationRegistration.bootstrap(PLAYER_SIMULATION_PLANE_ITEM.get());
        if (ChexsonsaeutilsCompatibilityConfig.boolValue(
                ChexsonsaeutilsCompatibilityConfig.INFINITY_CELL_ENABLED)) {
            CellRegistration.bootstrap(INFINITY_CELL_ITEM, net.neoforged.fml.ModList.get().isLoaded("neoecoae")
                    ? git.chexson.chexsonsaeutils.integration.terminalstorage.neoecoae.NeoEcoInfinityCellInventory::new
                    : git.chexson.chexsonsaeutils.cell.InfinityCellInventory::new);
        }
        if (ChexsonsaeutilsCompatibilityConfig.boolValue(
                ChexsonsaeutilsCompatibilityConfig.MATTER_MASS_PATTERN_PROVIDER_ENABLED)) {
            MatterMassCellRegistration.bootstrap(MatterMassRegistration.MATTER_MASS_ITEM);
        }
        // 面板模型注册（PartModels 冻结发生在客户端模型加载阶段，common setup 注册安全）
        PartModels.registerModels(CustomPatternProviderPart.MODELS);
    }

    /**
     * 面板能力注册（AE2 RegisterPartCapabilitiesEvent，mod 事件总线触发）。
     */
    public static void registerPartCapabilities(RegisterPartCapabilitiesEvent event) {
        CustomPatternProviderPart.registerCapability(event);
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        git.chexson.chexsonsaeutils.bootstrap.patterntransmitter.PatternTransmitterRegistration.capabilities(event);
        event.registerBlockEntity(
                AECapabilities.IN_WORLD_GRID_NODE_HOST,
                EXTREME_ASSEMBLER_BLOCK_ENTITY.get(),
                (blockEntity, context) -> blockEntity
        );
        event.registerBlockEntity(
                AECapabilities.IN_WORLD_GRID_NODE_HOST,
                AE_DIRECT_PROCESSING_MACHINE_BLOCK_ENTITY.get(),
                (blockEntity, context) -> blockEntity
        );
        event.registerBlockEntity(
                AECapabilities.IN_WORLD_GRID_NODE_HOST,
                AE2_PARALLEL_CPU_TOOL_BLOCK_ENTITY.get(),
                (blockEntity, context) -> blockEntity
        );
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                EXTREME_ASSEMBLER_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getAutomationItemHandler()
        );
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                AE_DIRECT_PROCESSING_MACHINE_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getAutomationItemHandler()
        );
        // 定制样板供应器：ITEM/ENERGY capability 透传到首个可用方向的相邻机器（无参版兜底）
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                CustomPatternProviderBlockEntityRegistration.CUSTOM_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getFilteredExternalItemHandler()
        );
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                CustomPatternProviderBlockEntityRegistration.CUSTOM_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getMachineEnergyHandler()
        );
        // B1 修复：AE2 InitCapabilityProviders 只自动注册 AE2 自己的 BE，本项目 BE 需手动注册
        // 网格节点能力——未注册则线缆无法发现 BE、节点永不入网（pushPattern 恒失败）
        event.registerBlockEntity(
                AECapabilities.IN_WORLD_GRID_NODE_HOST,
                CustomPatternProviderBlockEntityRegistration.CUSTOM_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                (blockEntity, context) -> blockEntity
        );
        // 物质团供应器：仅网格节点能力（无任何对外输入输出）
        event.registerBlockEntity(
                AECapabilities.IN_WORLD_GRID_NODE_HOST,
                MATTER_MASS_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                (blockEntity, context) -> blockEntity
        );
    }

    public static String emitterRegistryPath() {
        return MultiLevelEmitterItem.id();
    }

    public static String menuBindingKey() {
        return MultiLevelEmitterMenu.registrationKey();
    }

    public static String screenBindingKey() {
        return MultiLevelEmitterItem.SCREEN_BINDING_KEY;
    }

    public static String runtimePartType() {
        return MultiLevelEmitterRuntimePart.class.getSimpleName();
    }

    private static <T extends Block> RegisteredBlock<T> registerBlockWithItem(String id, Supplier<T> blockFactory) {
        Supplier<T> block = BLOCKS.register(id, blockFactory);
        Supplier<Item> item = ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties()));
        return new RegisteredBlock<>(block, item);
    }

    private static void registerExtremeAssemblerBootstrap() {
        ExtremeAssemblerBlock block = EXTREME_ASSEMBLER_BLOCK.get();
        block.setBlockEntity(
                ExtremeAssemblerBlockEntity.class,
                EXTREME_ASSEMBLER_BLOCK_ENTITY.get(),
                null,
                ExtremeAssemblerBlockEntity::serverTick
        );
        AEBaseBlockEntity.registerBlockEntityItem(
                EXTREME_ASSEMBLER_BLOCK_ENTITY.get(),
                EXTREME_ASSEMBLER_ITEM.get()
        );
    }

    private static void registerAEDirectProcessingMachineBootstrap() {
        AEDirectProcessingMachineBlock block = AE_DIRECT_PROCESSING_MACHINE_BLOCK.get();
        block.setBlockEntity(
                AEDirectProcessingMachineBlockEntity.class,
                AE_DIRECT_PROCESSING_MACHINE_BLOCK_ENTITY.get(),
                null,
                AEDirectProcessingMachineBlockEntity::serverTick
        );
        AEBaseBlockEntity.registerBlockEntityItem(
                AE_DIRECT_PROCESSING_MACHINE_BLOCK_ENTITY.get(),
                AE_DIRECT_PROCESSING_MACHINE_ITEM.get()
        );
    }

    private static void registerAE2ParallelCpuToolBootstrap() {
        ChexsonsaeutilsCompatibilityConfig.loadParallelCpuSettingsWithStartupWarnings();
        AE2ParallelCpuToolBlock block = AE2_PARALLEL_CPU_TOOL_BLOCK.get();
        block.setBlockEntity(
                AE2ParallelCpuToolBlockEntity.class,
                AE2_PARALLEL_CPU_TOOL_BLOCK_ENTITY.get(),
                null,
                null
        );
        AEBaseBlockEntity.registerBlockEntityItem(
                AE2_PARALLEL_CPU_TOOL_BLOCK_ENTITY.get(),
                AE2_PARALLEL_CPU_TOOL_ITEM.get()
        );
    }

    private static void registerAutoItemGenBootstrap() {
        AutoItemGenBlock block = AUTO_ITEM_GEN_BLOCK.get();
        block.setBlockEntity(
                AutoItemGenBlockEntity.class,
                AUTO_ITEM_GEN_BLOCK_ENTITY.get(),
                null,
                AutoItemGenBlockEntity::serverTick
        );
        AEBaseBlockEntity.registerBlockEntityItem(
                AUTO_ITEM_GEN_BLOCK_ENTITY.get(),
                AUTO_ITEM_GEN_ITEM.get()
        );
    }

    private static void registerCustomPatternProviderBootstrap() {
        CustomPatternProviderBlock block = CustomPatternProviderRegistration.CUSTOM_PATTERN_PROVIDER_BLOCK.get();
        // 无方块级 ticker：推送/抽取由网格 Ticker 驱动
        block.setBlockEntity(
                CustomPatternProviderBlockEntity.class,
                CustomPatternProviderBlockEntityRegistration.CUSTOM_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                null,
                null
        );
        AEBaseBlockEntity.registerBlockEntityItem(
                CustomPatternProviderBlockEntityRegistration.CUSTOM_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                CustomPatternProviderRegistration.CUSTOM_PATTERN_PROVIDER_ITEM.get()
        );
    }

    private static void registerMatterMassPatternProviderBootstrap() {
        MatterMassPatternProviderBlock block = MATTER_MASS_PATTERN_PROVIDER_BLOCK.get();
        // 无方块级 ticker：交付由网格 Ticker 驱动
        block.setBlockEntity(
                MatterMassPatternProviderBlockEntity.class,
                MATTER_MASS_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                null,
                null
        );
        AEBaseBlockEntity.registerBlockEntityItem(
                MATTER_MASS_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                MATTER_MASS_PATTERN_PROVIDER_ITEM.get()
        );
    }

    private static void registerMultiLevelEmitterBootstrap() {
        Upgrades.add(AEItems.FUZZY_CARD, MULTI_LEVEL_EMITTER_ITEM.get(), 1);
        Upgrades.add(AEItems.CRAFTING_CARD, MULTI_LEVEL_EMITTER_ITEM.get(), 1);
        MultiLevelEmitterMenu.registerMenuBindings(
                MULTI_LEVEL_EMITTER_MENU::get,
                (inventory, networkData) -> MultiLevelEmitterRuntimePart.consumePublishedMenuRuntime()
        );
        LOGGER.info(
                "Registered MultiLevelEmitter content: itemId={}, runtimePart={}, menuKey={}, screenKey={}, "
                        + "menuBindingsReady={}",
                emitterRegistryPath(),
                runtimePartType(),
                menuBindingKey(),
                screenBindingKey(),
                MultiLevelEmitterMenu.hasRegisteredMenuBindings()
        );
    }

    public record RegisteredBlock<T extends Block>(Supplier<T> block, Supplier<Item> item) {
    }
}
