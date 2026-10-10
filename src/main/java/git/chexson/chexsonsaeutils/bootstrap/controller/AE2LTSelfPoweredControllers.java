package git.chexson.chexsonsaeutils.bootstrap.controller;

import com.moakiee.ae2lt.block.AdvancedWirelessOverloadedControllerBlock;
import com.moakiee.ae2lt.block.OverloadedControllerBlock;
import com.moakiee.ae2lt.block.WirelessOverloadedControllerBlock;
import com.moakiee.ae2lt.registry.ModBlocks;
import appeng.block.AEBaseEntityBlock;
import git.chexson.chexsonsaeutils.block.controller.SelfPoweredController;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 仅在 AE2LT 存在时加载，保留三个 ME 控制器的原始类身份和无线行为。 */
final class AE2LTSelfPoweredControllers {
    private record Variant(Supplier<? extends AEBaseEntityBlock<?>> block,
                           Supplier<? extends AEBaseEntityBlock<?>> original, Supplier<? extends Item> item) {
    }

    private static final List<Variant> VARIANTS = new ArrayList<>();

    private AE2LTSelfPoweredControllers() {
    }

    static void register() {
        register("overloaded_controller", Overloaded::new, ModBlocks.OVERLOADED_CONTROLLER);
        register("wireless_overloaded_controller", Wireless::new, ModBlocks.WIRELESS_OVERLOADED_CONTROLLER);
        register("advanced_wireless_overloaded_controller", AdvancedWireless::new,
                ModBlocks.ADVANCED_WIRELESS_OVERLOADED_CONTROLLER);
    }

    private static <T extends AEBaseEntityBlock<?>> void register(String originalId, Supplier<T> factory,
                                                                  Supplier<T> original) {
        String id = "self_powered_" + originalId;
        var block = SelfPoweredControllerRegistration.BLOCKS.register(id, factory);
        var item = SelfPoweredControllerRegistration.ITEMS.register(id,
                () -> new BlockItem(block.get(), new Item.Properties()));
        VARIANTS.add(new Variant(block, original, item));
    }

    static void addValidBlocks(BlockEntityTypeAddBlocksEvent event) {
        for (var variant : VARIANTS) {
            // 此事件先于 AE2LT common setup；按原注册 ID 扩展，不能读取尚未装配的方块类型。
            var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(variant.original().get());
            event.modify(ResourceKey.create(Registries.BLOCK_ENTITY_TYPE, id), variant.block().get());
        }
    }

    static void bootstrap() {
        for (var variant : VARIANTS) {
            SelfPoweredControllerRegistration.bind(variant.block().get(), variant.original().get());
        }
    }

    static void displayItems(CreativeModeTab.Output output) {
        for (var variant : VARIANTS) output.accept(variant.item().get());
    }

    private static final class Overloaded extends OverloadedControllerBlock implements SelfPoweredController {
    }

    private static final class Wireless extends WirelessOverloadedControllerBlock implements SelfPoweredController {
    }

    private static final class AdvancedWireless extends AdvancedWirelessOverloadedControllerBlock
            implements SelfPoweredController {
    }
}
