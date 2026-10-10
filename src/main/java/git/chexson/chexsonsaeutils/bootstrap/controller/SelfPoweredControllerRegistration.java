package git.chexson.chexsonsaeutils.bootstrap.controller;

import appeng.block.AEBaseEntityBlock;
import appeng.blockentity.networking.ControllerBlockEntity;
import appeng.core.definitions.AEBlocks;
import git.chexson.chexsonsaeutils.block.controller.SelfPoweredControllerBlock;
import git.chexson.chexsonsaeutils.modid.ModIds;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/** 自供能方块装配；复用原实体类型以保持频道索引、能力和存档协议。 */
public final class SelfPoweredControllerRegistration {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ModIds.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ModIds.MODID);
    public static final Supplier<SelfPoweredControllerBlock> BLOCK =
            BLOCKS.register("self_powered_controller", SelfPoweredControllerBlock::new);
    public static final Supplier<Item> ITEM = ITEMS.register("self_powered_controller",
            () -> new BlockItem(BLOCK.get(), new Item.Properties()));

    private SelfPoweredControllerRegistration() {
    }

    public static void register(IEventBus bus) {
        if (ModList.get().isLoaded("ae2lt")) AE2LTSelfPoweredControllers.register();
        BLOCKS.register(bus);
        ITEMS.register(bus);
        bus.addListener(SelfPoweredControllerRegistration::addValidBlocks);
    }

    private static void addValidBlocks(BlockEntityTypeAddBlocksEvent event) {
        event.modify(AEBlocks.CONTROLLER.block().getBlockEntityType(), BLOCK.get());
        if (ModList.get().isLoaded("ae2lt")) AE2LTSelfPoweredControllers.addValidBlocks(event);
    }

    public static void bootstrap() {
        bind(BLOCK.get(), AEBlocks.CONTROLLER.block());
        if (ModList.get().isLoaded("ae2lt")) AE2LTSelfPoweredControllers.bootstrap();
    }

    public static void displayItems(CreativeModeTab.Output output) {
        output.accept(ITEM.get());
        if (ModList.get().isLoaded("ae2lt")) AE2LTSelfPoweredControllers.displayItems(output);
    }

    /**
     * 原类型工厂始终创建对应原实体，ticker 也直接委托原方块。
     * 控制器子类均满足此基类契约，无需改写第三方构造器或反射。
     */
    @SuppressWarnings("unchecked")
    static void bind(AEBaseEntityBlock<?> block, AEBaseEntityBlock<?> original) {
        var controllerBlock = (AEBaseEntityBlock<ControllerBlockEntity>) block;
        var type = (BlockEntityType<ControllerBlockEntity>) original.getBlockEntityType();
        controllerBlock.setBlockEntity(ControllerBlockEntity.class, type,
                (level, pos, state, entity) -> {
                    var ticker = original.getTicker(level, state, type);
                    if (ticker != null) ticker.tick(level, pos, state, entity);
                },
                (level, pos, state, entity) -> {
                    var ticker = original.getTicker(level, state, type);
                    if (ticker != null) ticker.tick(level, pos, state, entity);
                });
    }
}
