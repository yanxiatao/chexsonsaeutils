package git.chexson.chexsonsaeutils.registration;

import appeng.capabilities.Capabilities;
import appeng.api.upgrades.Upgrades;
import appeng.blockentity.AEBaseBlockEntity;
import appeng.core.definitions.AEItems;
import com.mojang.logging.LogUtils;
import git.chexson.chexsonsaeutils.Chexsonsaeutils;
import git.chexson.chexsonsaeutils.block.crafting.AEDirectProcessingMachineBlock;
import git.chexson.chexsonsaeutils.block.crafting.AE2ParallelCpuToolBlock;
import git.chexson.chexsonsaeutils.block.crafting.HighCapacityCraftingMachineBlock;
import git.chexson.chexsonsaeutils.blockentity.crafting.AE2ParallelCpuToolBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.crafting.HighCapacityCraftingMachineBlockEntity;
import git.chexson.chexsonsaeutils.blockentity.directprocessing.AEDirectProcessingMachineBlockEntity;
import git.chexson.chexsonsaeutils.client.gui.implementations.AEDirectProcessingMachineScreen;
import git.chexson.chexsonsaeutils.client.gui.implementations.HighCapacityCraftingMachineScreen;
import git.chexson.chexsonsaeutils.client.gui.implementations.MultiLevelEmitterRuntimeScreen;
import git.chexson.chexsonsaeutils.client.gui.implementations.ParallelCraftingCPUScreen;
import git.chexson.chexsonsaeutils.config.ParallelCraftingCpuConfig;
import git.chexson.chexsonsaeutils.menu.implementations.AEDirectProcessingMachineMenu;
import git.chexson.chexsonsaeutils.menu.implementations.HighCapacityCraftingMachineMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterMenu;
import git.chexson.chexsonsaeutils.menu.implementations.MultiLevelEmitterScreen;
import git.chexson.chexsonsaeutils.menu.implementations.ParallelCraftingCPUMenu;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterItem;
import git.chexson.chexsonsaeutils.parts.automation.MultiLevelEmitterRuntimePart;
import appeng.client.gui.style.StyleManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

import net.minecraft.core.Direction;
import net.minecraftforge.common.capabilities.Capability;
import java.util.function.Supplier;

public final class ChexsonsaeutilsContent {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Chexsonsaeutils.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Chexsonsaeutils.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Chexsonsaeutils.MODID);
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, Chexsonsaeutils.MODID);
    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, Chexsonsaeutils.MODID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, Chexsonsaeutils.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Chexsonsaeutils.MODID);

    public static final RegisteredBlock<HighCapacityCraftingMachineBlock> HIGH_CAPACITY_CRAFTING_MACHINE =
            registerBlockWithItem("high_capacity_crafting_machine", HighCapacityCraftingMachineBlock::new);
    public static final RegisteredBlock<AE2ParallelCpuToolBlock> AE2_PARALLEL_CPU_TOOL =
            registerBlockWithItem("ae2_parallel_cpu_tool", AE2ParallelCpuToolBlock::new);
    public static final RegisteredBlock<AEDirectProcessingMachineBlock> AE_DIRECT_PROCESSING_MACHINE =
            registerBlockWithItem("ae_direct_processing_machine", AEDirectProcessingMachineBlock::new);
    public static final Supplier<HighCapacityCraftingMachineBlock> HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK =
            HIGH_CAPACITY_CRAFTING_MACHINE.block();
    public static final Supplier<Item> HIGH_CAPACITY_CRAFTING_MACHINE_ITEM =
            HIGH_CAPACITY_CRAFTING_MACHINE.item();
    public static final Supplier<AE2ParallelCpuToolBlock> AE2_PARALLEL_CPU_TOOL_BLOCK =
            AE2_PARALLEL_CPU_TOOL.block();
    public static final Supplier<Item> AE2_PARALLEL_CPU_TOOL_ITEM =
            AE2_PARALLEL_CPU_TOOL.item();
    public static final Supplier<AEDirectProcessingMachineBlock> AE_DIRECT_PROCESSING_MACHINE_BLOCK =
            AE_DIRECT_PROCESSING_MACHINE.block();
    public static final Supplier<Item> AE_DIRECT_PROCESSING_MACHINE_ITEM =
            AE_DIRECT_PROCESSING_MACHINE.item();
    public static final Supplier<Item> MULTI_LEVEL_EMITTER_ITEM =
            ITEMS.register(MultiLevelEmitterItem.id(), MultiLevelEmitterItem::createItem);

    public static final Supplier<BlockEntityType<HighCapacityCraftingMachineBlockEntity>>
            HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
                    "high_capacity_crafting_machine",
                    () -> BlockEntityType.Builder.of(
                            HighCapacityCraftingMachineBlockEntity::new,
                            HIGH_CAPACITY_CRAFTING_MACHINE_BLOCK.get()
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
    public static final Supplier<BlockEntityType<AEDirectProcessingMachineBlockEntity>>
            AE_DIRECT_PROCESSING_MACHINE_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
                    "ae_direct_processing_machine",
                    () -> BlockEntityType.Builder.of(
                            AEDirectProcessingMachineBlockEntity::new,
                            AE_DIRECT_PROCESSING_MACHINE_BLOCK.get()
                    ).build(null)
            );

public static final Supplier<MenuType<HighCapacityCraftingMachineMenu>> HIGH_CAPACITY_CRAFTING_MACHINE_MENU =
            () -> HighCapacityCraftingMachineMenu.TYPE; // ponytail: MenuTypeBuilder.build() 已自动注册, 不重复注册
    public static final Supplier<MenuType<ParallelCraftingCPUMenu>> AE2_PARALLEL_CPU_TOOL_CPU_MENU =
            () -> ParallelCraftingCPUMenu.TYPE; // ponytail: MenuTypeBuilder.build() 已自动注册, 不重复注册
    public static final Supplier<MenuType<MultiLevelEmitterMenu.RuntimeMenu>> MULTI_LEVEL_EMITTER_MENU =
            MENU_TYPES.register(
                    MultiLevelEmitterMenu.registrationKey(),
                    () -> IForgeMenuType.create(MultiLevelEmitterMenu.RuntimeMenu::fromNetwork)
            );
    public static final Supplier<MenuType<AEDirectProcessingMachineMenu>> AE_DIRECT_PROCESSING_MACHINE_MENU =
            () -> AEDirectProcessingMachineMenu.TYPE; // ponytail: MenuTypeBuilder.build() 已自动注册, 不重复注册

    public static final Supplier<CreativeModeTab> CHEXSONSAEUTILS_TAB =
            CREATIVE_MODE_TABS.register("chexsonsaeutils", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.chexsonsaeutils"))
                    .icon(() -> new ItemStack(HIGH_CAPACITY_CRAFTING_MACHINE_ITEM.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(MULTI_LEVEL_EMITTER_ITEM.get());
                        output.accept(HIGH_CAPACITY_CRAFTING_MACHINE_ITEM.get());
                        output.accept(AE2_PARALLEL_CPU_TOOL_ITEM.get());
                        output.accept(AE_DIRECT_PROCESSING_MACHINE_ITEM.get());
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
    }

    public static void registerCommonContent() {
        registerHighCapacityCraftingMachineBootstrap();
        registerAE2ParallelCpuToolBootstrap();
        registerMultiLevelEmitterBootstrap();
    }

    public static void onAttachCapabilities(AttachCapabilitiesEvent<BlockEntity> event) {
        BlockEntity be = event.getObject();
        if (be instanceof HighCapacityCraftingMachineBlockEntity hccm) {
            attachGridNodeHost(event, hccm);
            attachItemHandler(event, hccm.getAutomationItemHandler());
        } else if (be instanceof AE2ParallelCpuToolBlockEntity pct) {
            attachGridNodeHost(event, pct);
        } else if (be instanceof AEDirectProcessingMachineBlockEntity dp) {
            attachGridNodeHost(event, dp);
        }
    }

    private static void attachGridNodeHost(AttachCapabilitiesEvent<BlockEntity> event, Object host) {
        event.addCapability(
                modLoc("grid_node_host"),
                new ICapabilityProvider() {
                    @Override
                    public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
                        if (cap == Capabilities.IN_WORLD_GRID_NODE_HOST) {
                            return LazyOptional.of(() -> (T) host);
                        }
                        return LazyOptional.empty();
                    }
                }
        );
    }

    @SuppressWarnings("removal")
    private static ResourceLocation modLoc(String path) {
        return new ResourceLocation(Chexsonsaeutils.MODID, path);
    }

    private static void attachItemHandler(AttachCapabilitiesEvent<BlockEntity> event, Object handler) {
        event.addCapability(
                modLoc("item_handler"),
                new ICapabilityProvider() {
                    @Override
                    public <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side) {
                        if (cap == ForgeCapabilities.ITEM_HANDLER) {
                            return LazyOptional.of(() -> (T) handler);
                        }
                        return LazyOptional.empty();
                    }
                }
        );
    }

    public static void registerClientScreens() {
        registerMultiLevelEmitterClientBindings();
        MenuScreens.register(HIGH_CAPACITY_CRAFTING_MACHINE_MENU.get(), HighCapacityCraftingMachineScreen::new);
        MenuScreens.<ParallelCraftingCPUMenu, ParallelCraftingCPUScreen>register(
                AE2_PARALLEL_CPU_TOOL_CPU_MENU.get(),
                (menu, inv, title) -> new ParallelCraftingCPUScreen(
                        menu, inv, title,
                        StyleManager.loadStyleDoc("/screens/chexson_parallel_cpu.json")
                )
        );
        MenuScreens.register(MULTI_LEVEL_EMITTER_MENU.get(), MultiLevelEmitterRuntimeScreen::new);
        MenuScreens.register(AE_DIRECT_PROCESSING_MACHINE_MENU.get(), AEDirectProcessingMachineScreen::new);
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
        RegistryObject<T> block = BLOCKS.register(id, blockFactory);
        RegistryObject<Item> item = ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties()));
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
