package git.chexson.chexsonsaeutils.registration;

import appeng.api.AECapabilities;
import appeng.api.parts.PartModels;
import appeng.api.parts.RegisterPartCapabilitiesEvent;
import appeng.api.upgrades.Upgrades;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.core.definitions.AEItems;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.cell.CellRegistration;
import git.chexson.chexsonsaeutils.cell.InfinityCellItem;
import git.chexson.chexsonsaeutils.block.crafting.AEDirectProcessingMachineBlock;
import git.chexson.chexsonsaeutils.block.crafting.AE2ParallelCpuToolBlock;
import git.chexson.chexsonsaeutils.block.crafting.HighCapacityCraftingMachineBlock;
import git.chexson.chexsonsaeutils.block.debug.AutoItemGenBlock;
import git.chexson.chexsonsaeutils.block.custompatternprovider.CustomPatternProviderBlock;
import git.chexson.chexsonsaeutils.block.framepatternprovider.FramePatternProviderBlock;
import git.chexson.chexsonsaeutils.blockentity.debug.AutoItemGenBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.custompatternprovider.CustomPatternProviderBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.directprocessing.AEDirectProcessingMachineBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.crafting.HighCapacityCraftingMachineBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.framepatternprovider.FramePatternProviderBlockEntity;
import git.chexson.chexsonsaeutils.item.custompatternprovider.CustomPatternProviderItem;
import git.chexson.chexsonsaeutils.item.framepatternprovider.FramePatternProviderItem;
import git.chexson.chexsonsaeutils.client.gui.custompatternprovider.CustomPatternProviderScreen;
import git.chexson.chexsonsaeutils.client.gui.implementations.AEDirectProcessingMachineScreen;
import git.chexson.chexsonsaeutils.client.gui.implementations.HighCapacityCraftingMachineScreen;
import git.chexson.chexsonsaeutils.client.gui.implementations.MultiLevelEmitterRuntimeScreen;
import git.chexson.chexsonsaeutils.client.gui.implementations.ParallelCraftingCPUScreen;
import git.chexson.chexsonsaeutils.config.ParallelCraftingCpuConfig;
import git.chexson.chexsonsaeutils.crafting.directprocessing.DirectProcessingMockRecipe;
import git.chexson.chexsonsaeutils.crafting.framepattern.EncodedFramePattern;
import git.chexson.chexsonsaeutils.crafting.framepattern.FramePatternItem;
import git.chexson.chexsonsaeutils.menu.custompatternprovider.CustomPatternProviderMenu;
import git.chexson.chexsonsaeutils.menu.implementations.AEDirectProcessingMachineMenu;
import git.chexson.chexsonsaeutils.menu.implementations.HighCapacityCraftingMachineMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterScreen;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu;
import git.chexson.chexsonsaeutils.menu.framepatternconfig.FramePatternConfigMenu;
import git.chexson.chexsonsaeutils.menu.framepatternprovider.FramePatternProviderMenu;
import git.chexson.chexsonsaeutils.menu.framepatternupgrade.FramePatternUpgradeMenu;
import git.chexson.chexsonsaeutils.client.gui.framepatternconfig.FramePatternConfigScreen;
import git.chexson.chexsonsaeutils.client.gui.framepatternprovider.FramePatternProviderScreen;
import git.chexson.chexsonsaeutils.client.gui.framepatternupgrade.FramePatternUpgradeScreen;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterItem;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import git.chexson.chexsonsaeutils.parts.custompatternprovider.CustomPatternProviderPart;
import git.chexson.chexsonsaeutils.parts.custompatternprovider.CustomPatternProviderPartItem;
import appeng.client.gui.style.StyleManager;
import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.util.function.Supplier;

public final class ChexsonsaeutilsContent {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Chexsonsaeutils.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Chexsonsaeutils.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Chexsonsaeutils.MODID);
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, Chexsonsaeutils.MODID);
    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, Chexsonsaeutils.MODID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, Chexsonsaeutils.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Chexsonsaeutils.MODID);
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, Chexsonsaeutils.MODID);

    public static final RegisteredBlock<HighCapacityCraftingMachineBlock> HIGH_CAPACITY_CRAFTING_MACHINE =
            registerBlockWithItem("high_capacity_crafting_machine", HighCapacityCraftingMachineBlock::new);
    public static final RegisteredBlock<AEDirectProcessingMachineBlock> AE_DIRECT_PROCESSING_MACHINE =
            registerBlockWithItem("ae_direct_processing_machine", AEDirectProcessingMachineBlock::new);
    public static final RegisteredBlock<AE2ParallelCpuToolBlock> AE2_PARALLEL_CPU_TOOL =
            registerBlockWithItem("ae2_parallel_cpu_tool", AE2ParallelCpuToolBlock::new);
    public static final Supplier<HighCapacityCraftingMachineBlock> HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK =
            HIGH_CAPACITY_CRAFTING_MACHINE.block();
    public static final Supplier<Item> HIGH_CAPACITY_CRAFTING_MACHINE_ITEM =
            HIGH_CAPACITY_CRAFTING_MACHINE.item();
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
    public static final Supplier<InfinityCellItem> INFINITY_CELL_ITEM =
            ITEMS.register(CellRegistration.CELL_ITEM_ID, InfinityCellItem::new);
    public static final RegisteredBlock<AutoItemGenBlock> AUTO_ITEM_GEN =
            registerBlockWithItem("auto_item_gen", AutoItemGenBlock::new);
    public static final Supplier<AutoItemGenBlock> AUTO_ITEM_GEN_BLOCK = AUTO_ITEM_GEN.block();
    public static final Supplier<Item> AUTO_ITEM_GEN_ITEM = AUTO_ITEM_GEN.item();
    public static final Supplier<FramePatternProviderBlock> FRAME_PATTERN_PROVIDER_BLOCK =
            BLOCKS.register("frame_pattern_provider", FramePatternProviderBlock::new);
    public static final Supplier<Item> FRAME_PATTERN_PROVIDER_ITEM =
            ITEMS.register("frame_pattern_provider",
                    () -> new FramePatternProviderItem(FRAME_PATTERN_PROVIDER_BLOCK.get(), new Item.Properties()));
    public static final Supplier<CustomPatternProviderBlock> CUSTOM_PATTERN_PROVIDER_BLOCK =
            BLOCKS.register("custom_pattern_provider", CustomPatternProviderBlock::new);
    public static final Supplier<Item> CUSTOM_PATTERN_PROVIDER_ITEM =
            ITEMS.register("custom_pattern_provider",
                    () -> new CustomPatternProviderItem(CUSTOM_PATTERN_PROVIDER_BLOCK.get(), new Item.Properties()));
    /** 定制样板供应器面板（阶段 3）：PartItem 工厂创建面板实例，模型经 PartModels 注册。 */
    public static final Supplier<Item> CUSTOM_PATTERN_PROVIDER_PART_ITEM =
            ITEMS.register("custom_pattern_provider_part",
                    () -> new CustomPatternProviderPartItem(new Item.Properties()));
    public static final Supplier<FramePatternItem> FRAME_PATTERN_ITEM =
            ITEMS.register("frame_pattern", FramePatternItem::createItem);
    public static final Supplier<DataComponentType<EncodedFramePattern>> ENCODED_FRAME_PATTERN =
            DATA_COMPONENT_TYPES.register("encoded_frame_pattern",
                    () -> DataComponentType.<EncodedFramePattern>builder()
                            .persistent(EncodedFramePattern.CODEC)
                            .networkSynchronized(EncodedFramePattern.STREAM_CODEC)
                            .build());
    /** 框架样板供应器的已扩展样板页数（拆除保留闭环：BE NBT → 掉落物品组件 → 放置/捕获读回）。 */
    public static final Supplier<DataComponentType<Integer>> FRAME_PATTERN_PAGES =
            DATA_COMPONENT_TYPES.register("frame_pattern_pages",
                    () -> DataComponentType.<Integer>builder()
                            .persistent(Codec.INT)
                            .networkSynchronized(ByteBufCodecs.VAR_INT)
                            .build());
    public static final Supplier<BlockEntityType<AutoItemGenBlockEntity>> AUTO_ITEM_GEN_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("auto_item_gen",
                    () -> {
                        var block = AUTO_ITEM_GEN_BLOCK.get();
                        return BlockEntityType.Builder.of(AutoItemGenBlockEntity::new, block).build(null);
                    });
    public static final Supplier<RecipeType<DirectProcessingMockRecipe>> DIRECT_PROCESSING_MOCK_RECIPE_TYPE =
            RECIPE_TYPES.register("direct_processing_mock", DirectProcessingMockRecipe::createType);
    public static final Supplier<RecipeSerializer<DirectProcessingMockRecipe>>
            DIRECT_PROCESSING_MOCK_RECIPE_SERIALIZER = RECIPE_SERIALIZERS.register(
                    "direct_processing_mock",
                    DirectProcessingMockRecipe::createSerializer
            );

    public static final Supplier<BlockEntityType<HighCapacityCraftingMachineBlockEntity>>
            HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
                    "high_capacity_crafting_machine",
                    () -> BlockEntityType.Builder.of(
                            HighCapacityCraftingMachineBlockEntity::new,
                            HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK.get()
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
    public static final Supplier<BlockEntityType<FramePatternProviderBlockEntity>>
            FRAME_PATTERN_PROVIDER_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
                    "frame_pattern_provider",
                    () -> BlockEntityType.Builder.of(
                            FramePatternProviderBlockEntity::new,
                            FRAME_PATTERN_PROVIDER_BLOCK.get()
                    ).build(null)
            );
    public static final Supplier<BlockEntityType<CustomPatternProviderBlockEntity>>
            CUSTOM_PATTERN_PROVIDER_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
                    "custom_pattern_provider",
                    () -> BlockEntityType.Builder.of(
                            CustomPatternProviderBlockEntity::new,
                            CUSTOM_PATTERN_PROVIDER_BLOCK.get()
                    ).build(null)
            );

    public static final Supplier<MenuType<HighCapacityCraftingMachineMenu>> HIGH_CAPACITY_CRAFTING_MACHINE_MENU =
            MENU_TYPES.register("high_capacity_crafting_machine", () -> HighCapacityCraftingMachineMenu.TYPE);
    public static final Supplier<MenuType<AEDirectProcessingMachineMenu>> AE_DIRECT_PROCESSING_MACHINE_MENU =
            MENU_TYPES.register("ae_direct_processing_machine", () -> AEDirectProcessingMachineMenu.TYPE);
    public static final Supplier<MenuType<ParallelCraftingCPUMenu>> AE2_PARALLEL_CPU_TOOL_CPU_MENU =
            MENU_TYPES.register("ae2_parallel_cpu_tool_cpu", () -> ParallelCraftingCPUMenu.TYPE);
    public static final Supplier<MenuType<FramePatternProviderMenu>> FRAME_PATTERN_PROVIDER_MENU =
            MENU_TYPES.register("frame_pattern_provider", () -> FramePatternProviderMenu.TYPE);
    public static final Supplier<MenuType<CustomPatternProviderMenu<?>>> CUSTOM_PATTERN_PROVIDER_MENU =
            MENU_TYPES.register("custom_pattern_provider", () -> CustomPatternProviderMenu.TYPE);
    public static final Supplier<MenuType<FramePatternConfigMenu>> FRAME_PATTERN_CONFIG_MENU =
            MENU_TYPES.register("frame_pattern_config", () -> FramePatternConfigMenu.TYPE);
    public static final Supplier<MenuType<FramePatternUpgradeMenu>> FRAME_PATTERN_UPGRADE_MENU =
            MENU_TYPES.register("frame_pattern_upgrade", () -> FramePatternUpgradeMenu.TYPE);
    public static final Supplier<MenuType<MultiLevelEmitterMenu.RuntimeMenu>> MULTI_LEVEL_EMITTER_MENU =
            MENU_TYPES.register(
                    MultiLevelEmitterMenu.registrationKey(),
                    () -> IMenuTypeExtension.create(MultiLevelEmitterMenu.RuntimeMenu::fromNetwork)
            );

    public static final Supplier<CreativeModeTab> CHEXSONSAEUTILS_TAB =
            CREATIVE_MODE_TABS.register("chexsonsaeutils", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.chexsonsaeutils"))
                    .icon(() -> new ItemStack(HIGH_CAPACITY_CRAFTING_MACHINE_ITEM.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(MULTI_LEVEL_EMITTER_ITEM.get());
                        output.accept(HIGH_CAPACITY_CRAFTING_MACHINE_ITEM.get());
                        output.accept(AE_DIRECT_PROCESSING_MACHINE_ITEM.get());
                        output.accept(AE2_PARALLEL_CPU_TOOL_ITEM.get());
                        output.accept(FRAME_PATTERN_PROVIDER_ITEM.get());
                        output.accept(FRAME_PATTERN_ITEM.get());
                        output.accept(CUSTOM_PATTERN_PROVIDER_ITEM.get());
                        output.accept(CUSTOM_PATTERN_PROVIDER_PART_ITEM.get());
                        output.accept(INFINITY_CELL_ITEM.get());
                        if (!FMLLoader.isProduction()) {
                            output.accept(AUTO_ITEM_GEN_ITEM.get());
                        }
                    })
                    .build());

    private ChexsonsaeutilsContent() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        MENU_TYPES.register(modEventBus);
        RECIPE_TYPES.register(modEventBus);
        RECIPE_SERIALIZERS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        DATA_COMPONENT_TYPES.register(modEventBus);
    }

    public static void registerCommonContent() {
        registerHighCapacityCraftingMachineBootstrap();
        registerAEDirectProcessingMachineBootstrap();
        registerAE2ParallelCpuToolBootstrap();
        registerAutoItemGenBootstrap();
        registerFramePatternProviderBootstrap();
        registerCustomPatternProviderBootstrap();
        registerMultiLevelEmitterBootstrap();
        CellRegistration.bootstrap(INFINITY_CELL_ITEM);
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
        event.registerBlockEntity(
                AECapabilities.IN_WORLD_GRID_NODE_HOST,
                HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK_ENTITY.get(),
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
                HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getAutomationItemHandler()
        );
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                AE_DIRECT_PROCESSING_MACHINE_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getAutomationItemHandler()
        );
        // 框架样板供应器：ITEM/ENERGY capability 跨维度透传到私有维度机器
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                FRAME_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getMachineItemHandler()
        );
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                FRAME_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getMachineEnergyHandler()
        );
        // 定制样板供应器：ITEM/ENERGY capability 透传到首个可用方向的相邻机器（无参版兜底）
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                CUSTOM_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getMachineItemHandler()
        );
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                CUSTOM_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                (blockEntity, side) -> blockEntity.getMachineEnergyHandler()
        );
        // B1 修复：AE2 InitCapabilityProviders 只自动注册 AE2 自己的 BE，本项目 BE 需手动注册
        // 网格节点能力——未注册则线缆无法发现 BE、节点永不入网（pushPattern 恒失败）
        event.registerBlockEntity(
                AECapabilities.IN_WORLD_GRID_NODE_HOST,
                FRAME_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                (blockEntity, context) -> blockEntity
        );
        event.registerBlockEntity(
                AECapabilities.IN_WORLD_GRID_NODE_HOST,
                CUSTOM_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                (blockEntity, context) -> blockEntity
        );
    }

    public static void registerClientScreens(RegisterMenuScreensEvent event) {
        registerMultiLevelEmitterClientBindings();
        event.register(HIGH_CAPACITY_CRAFTING_MACHINE_MENU.get(), HighCapacityCraftingMachineScreen::new);
        event.register(AE_DIRECT_PROCESSING_MACHINE_MENU.get(), AEDirectProcessingMachineScreen::new);
        event.<ParallelCraftingCPUMenu, ParallelCraftingCPUScreen>register(
                AE2_PARALLEL_CPU_TOOL_CPU_MENU.get(),
                (menu, playerInventory, title) ->
                new ParallelCraftingCPUScreen(
                        menu,
                        playerInventory,
                        title,
                        StyleManager.loadStyleDoc("/screens/chexson_parallel_cpu.json")
                )
        );
        event.register(MULTI_LEVEL_EMITTER_MENU.get(), MultiLevelEmitterRuntimeScreen::new);
        event.register(FRAME_PATTERN_PROVIDER_MENU.get(), FramePatternProviderScreen::new);
        event.register(FRAME_PATTERN_CONFIG_MENU.get(), FramePatternConfigScreen::new);
        event.register(FRAME_PATTERN_UPGRADE_MENU.get(), FramePatternUpgradeScreen::new);
        event.register(CUSTOM_PATTERN_PROVIDER_MENU.get(), CustomPatternProviderScreen::new);
    }

    public static String emitterRegistryPath() {
        return MultiLevelEmitterItem.id();
    }

    public static String menuBindingKey() {
        return MultiLevelEmitterMenu.registrationKey();
    }

    public static String screenBindingKey() {
        return MultiLevelEmitterScreen.registrationKey();
    }

    public static String runtimePartType() {
        return MultiLevelEmitterRuntimePart.class.getSimpleName();
    }

    private static <T extends Block> RegisteredBlock<T> registerBlockWithItem(String id, Supplier<T> blockFactory) {
        Supplier<T> block = BLOCKS.register(id, blockFactory);
        Supplier<Item> item = ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties()));
        return new RegisteredBlock<>(block, item);
    }

    private static void registerHighCapacityCraftingMachineBootstrap() {
        HighCapacityCraftingMachineBlock block = HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK.get();
        block.setBlockEntity(
                HighCapacityCraftingMachineBlockEntity.class,
                HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK_ENTITY.get(),
                null,
                HighCapacityCraftingMachineBlockEntity::serverTick
        );
        AEBaseBlockEntity.registerBlockEntityItem(
                HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK_ENTITY.get(),
                HIGH_CAPACITY_CRAFTING_MACHINE_ITEM.get()
        );
        Upgrades.add(AEItems.SPEED_CARD, HIGH_CAPACITY_CRAFTING_MACHINE_ITEM.get(), 5);
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
        Upgrades.add(AEItems.SPEED_CARD, AE_DIRECT_PROCESSING_MACHINE_ITEM.get(), 5);
    }

    private static void registerAE2ParallelCpuToolBootstrap() {
        ParallelCraftingCpuConfig.loadWithStartupWarnings();
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

    private static void registerFramePatternProviderBootstrap() {
        FramePatternProviderBlock block = FRAME_PATTERN_PROVIDER_BLOCK.get();
        block.setBlockEntity(
                FramePatternProviderBlockEntity.class,
                FRAME_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                null,
                null
        );
        AEBaseBlockEntity.registerBlockEntityItem(
                FRAME_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                FRAME_PATTERN_PROVIDER_ITEM.get()
        );
    }

    private static void registerCustomPatternProviderBootstrap() {
        CustomPatternProviderBlock block = CUSTOM_PATTERN_PROVIDER_BLOCK.get();
        // 无 ticker：定制供应器无 serverTick 需求（无私有维度/隔离）
        block.setBlockEntity(
                CustomPatternProviderBlockEntity.class,
                CUSTOM_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                null,
                null
        );
        AEBaseBlockEntity.registerBlockEntityItem(
                CUSTOM_PATTERN_PROVIDER_BLOCK_ENTITY.get(),
                CUSTOM_PATTERN_PROVIDER_ITEM.get()
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

    private static void registerMultiLevelEmitterClientBindings() {
        MultiLevelEmitterScreen.registerClientBindings(
                MULTI_LEVEL_EMITTER_MENU.get(),
                (menu, slotIndex, threshold, maxValue) -> menu.commitThreshold(slotIndex, threshold, maxValue)
        );
        LOGGER.info(
                "Registered MultiLevelEmitter client bindings: screenKey={}, clientBindingsReady={}",
                screenBindingKey(),
                MultiLevelEmitterScreen.hasClientBindingsRegistered()
        );
    }

    public record RegisteredBlock<T extends Block>(Supplier<T> block, Supplier<Item> item) {
    }
}
