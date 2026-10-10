package git.chexson.chexsonsaeutils.menu.matrix;

import appeng.api.inventories.InternalInventory;
import appeng.helpers.InventoryAction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 装配矩阵格子的服务端点击处理。
 *
 * <p>界面里的格子是客户端虚拟槽，不参与原版槽位同步，因此移动/拆分/shift 放置
 * 必须由界面换算成 {@link InventoryAction} 回传到这里落实；口径与 ExtendedAE
 * 的装配矩阵一致：一格一份样板，手上可以多份。</p>
 */
public final class PatternMatrixInteraction {

    /** 矩阵点击包的 extraId：一个菜单只有一个样板库存，用它把矩阵点击与其他 doAction 分流。 */
    public static final long MATRIX_PATTERN_ID = 1L;

    private PatternMatrixInteraction() {
    }

    /** 点击语义换算，逐条对齐 ExtendedAE 的装配矩阵。 */
    public static InventoryAction clickAction(ClickType clickType, boolean rightButton, boolean creative) {
        return switch (clickType) {
            case PICKUP -> rightButton
                    ? InventoryAction.SPLIT_OR_PLACE_SINGLE
                    : InventoryAction.PICKUP_OR_SET_DOWN;
            case QUICK_MOVE -> rightButton ? InventoryAction.PICKUP_SINGLE : InventoryAction.SHIFT_CLICK;
            case CLONE -> creative ? InventoryAction.CREATIVE_DUPLICATE : null;
            default -> null;
        };
    }

    /**
     * 背包 shift 点击的服务端落点：按每格一份把样板写进第一个可用槽，返回放下的数量。
     *
     * <p>矩阵格子是客户端虚拟槽，服务端菜单里没有对应槽位，原版 QUICK_MOVE 找不到目的地，
     * 只能由菜单在 {@code transferStackToMenu} 里直接写全量库存。</p>
     */
    public static int shiftInsertIntoMatrix(PatternMatrixSource source, ItemStack input) {
        if (input.isEmpty() || !source.patternMatrixMayPlace(input)) {
            return 0;
        }
        ItemStack overflow = source.patternMatrixInventory().addItems(input.copy());
        return input.getCount() - overflow.getCount();
    }

    public static void doAction(
            PatternMatrixSource source,
            InventoryAction action,
            int slot,
            @Nullable ServerPlayer player,
            Supplier<ItemStack> carried,
            Consumer<ItemStack> carriedSetter
    ) {
        if (player == null || player.isRemoved()) {
            return;
        }
        InternalInventory inventory = source.patternMatrixInventory();
        if (slot < 0 || slot >= inventory.size()) {
            return;
        }
        InternalInventory cell = inventory.getSlotInv(slot);
        ItemStack stored = cell.getStackInSlot(0);
        ItemStack hand = carried.get();
        switch (action) {
            case PICKUP_OR_SET_DOWN -> pickupOrSetDown(source, cell, stored, hand, carriedSetter);
            case SPLIT_OR_PLACE_SINGLE -> splitOrPlaceSingle(source, cell, stored, hand, carriedSetter);
            case PICKUP_SINGLE -> pickupSingle(cell, hand, carriedSetter);
            case SHIFT_CLICK -> shiftClick(cell, stored, player);
            case CREATIVE_DUPLICATE -> creativeDuplicate(stored, hand, player, carriedSetter);
            default -> {
            }
        }
    }

    private static void pickupOrSetDown(
            PatternMatrixSource source,
            InternalInventory cell,
            ItemStack stored,
            ItemStack hand,
            Consumer<ItemStack> carriedSetter
    ) {
        if (hand.isEmpty()) {
            carriedSetter.accept(cell.extractItem(0, stored.isEmpty() ? 0 : stored.getCount(), false));
            return;
        }
        if (!source.patternMatrixMayPlace(hand)) {
            return;
        }
        if (stored.isEmpty()) {
            carriedSetter.accept(cell.addItems(hand.copy()));
            return;
        }
        // 换出槽内样板 → 放入手上样板 → 放不下的部分回到手上并退回原样板
        ItemStack displaced = cell.extractItem(0, 1, false);
        ItemStack remainder = cell.addItems(hand.copy());
        if (!remainder.isEmpty()) {
            cell.setItemDirect(0, displaced);
            carriedSetter.accept(remainder);
            return;
        }
        carriedSetter.accept(displaced);
    }

    private static void splitOrPlaceSingle(
            PatternMatrixSource source,
            InternalInventory cell,
            ItemStack stored,
            ItemStack hand,
            Consumer<ItemStack> carriedSetter
    ) {
        if (!hand.isEmpty()) {
            if (stored.isEmpty() && source.patternMatrixMayPlace(hand)) {
                ItemStack single = hand.copyWithCount(1);
                if (!cell.addItems(single).isEmpty()) {
                    return;
                }
                ItemStack remainder = hand.copy();
                remainder.shrink(1);
                carriedSetter.accept(remainder);
            }
            return;
        }
        if (!stored.isEmpty()) {
            carriedSetter.accept(cell.extractItem(0, (stored.getCount() + 1) / 2, false));
        }
    }

    private static void pickupSingle(
            InternalInventory cell,
            ItemStack hand,
            Consumer<ItemStack> carriedSetter
    ) {
        if (!hand.isEmpty()) {
            return;
        }
        carriedSetter.accept(cell.extractItem(0, 1, false));
    }

    private static void shiftClick(
            InternalInventory cell,
            ItemStack stored,
            ServerPlayer player
    ) {
        if (stored.isEmpty()) {
            return;
        }
        ItemStack moved = cell.extractItem(0, stored.getCount(), false);
        if (!moved.isEmpty() && !player.getInventory().add(moved)) {
            cell.addItems(moved);
        }
    }

    private static void creativeDuplicate(
            ItemStack stored,
            ItemStack hand,
            ServerPlayer player,
            Consumer<ItemStack> carriedSetter
    ) {
        if (!player.getAbilities().instabuild || !hand.isEmpty() || stored.isEmpty()) {
            return;
        }
        carriedSetter.accept(stored.copy());
    }
}
