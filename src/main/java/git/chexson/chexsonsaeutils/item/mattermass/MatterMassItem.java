package git.chexson.chexsonsaeutils.item.mattermass;

import appeng.api.implementations.menuobjects.IMenuItem;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.menu.MenuOpener;
import appeng.menu.locator.ItemMenuHostLocator;
import appeng.menu.locator.MenuLocators;
import git.chexson.chexsonsaeutils.cell.MatterMassStore;
import git.chexson.chexsonsaeutils.menu.mattermass.MatterMassViewMenuHost;
import git.chexson.chexsonsaeutils.registration.ChexsonsaeutilsContent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 物质团：一次性只读"容器"物品。
 * <p>
 * 组件仅 CUSTOM_NAME（= 样板第一个输出名）与物质团 UUID；内容物存
 * {@link MatterMassStore}（不进组件，保证样板上报 key 与产物 key 一致）。
 * 右键打开只读查看界面（空则直接消失）；蹲下右键释放内容物进背包
 * （流体消耗空桶按 1000mB 整桶转换，容量/空桶不足先警告一次，
 * 再次蹲下右键强制释放、溢出掉落）；内容清空后物品消失。
 */
public class MatterMassItem extends Item implements IMenuItem {

    /** 武装（警告后允许强制释放）的有效窗口（tick）。 */
    private static final long ARM_WINDOW_TICKS = 100;
    /** 服务端武装记录：物质团 UUID -> 警告时的游戏时间。不入组件，避免 AEItemKey 抖动。 */
    private static final Map<UUID, Long> ARMED = new HashMap<>();

    public MatterMassItem(Properties properties) {
        super(properties);
    }

    /** @return 物质团 UUID（无组件时 null）。 */
    @Nullable
    public static UUID getUuid(ItemStack stack) {
        return stack.get(ChexsonsaeutilsContent.MATTER_MASS_UUID.get());
    }

    /** 构造一个物质团栈（名字 + UUID；内容物在外部存储中按 UUID 维护）。 */
    public static ItemStack createStack(Component name, UUID uuid) {
        var stack = new ItemStack(ChexsonsaeutilsContent.MATTER_MASS_ITEM.get());
        stack.set(DataComponents.CUSTOM_NAME, name);
        stack.set(ChexsonsaeutilsContent.MATTER_MASS_UUID.get(), uuid);
        return stack;
    }

    public static boolean isMatterMass(ItemStack stack) {
        return stack.getItem() instanceof MatterMassItem;
    }

    /** 物品菜单宿主入口（AE2 MenuOpener 约定）：返回只读查看菜单宿主。 */
    @Override
    @org.jetbrains.annotations.Nullable
    public ItemMenuHost<?> getMenuHost(Player player, ItemMenuHostLocator locator,
            @org.jetbrains.annotations.Nullable net.minecraft.world.phys.BlockHitResult hitResult) {
        return new MatterMassViewMenuHost(player, locator);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        var serverPlayer = (ServerPlayer) player;
        var uuid = getUuid(stack);
        if (uuid == null || MatterMassStore.global().isEmpty(uuid)) {
            // 空物质团：直接消失
            clearArmed(uuid);
            stack.shrink(1);
            return InteractionResultHolder.consume(player.getItemInHand(hand));
        }
        if (player.isSecondaryUseActive()) {
            releaseContents(serverPlayer, stack, hand, uuid);
            return InteractionResultHolder.consume(player.getItemInHand(hand));
        }
        MenuOpener.open(ChexsonsaeutilsContent.MATTER_MASS_VIEW_MENU.get(), player,
                MenuLocators.forHand(player, hand));
        return InteractionResultHolder.success(stack);
    }

    /**
     * 蹲下右键释放内容物进背包。
     * <p>
     * 先做完整模拟（物品堆叠合并 + 空槽占用；流体消耗空桶后填充桶逐桶占独立槽位），
     * 模拟不通过且未武装 → 警告并武装；已武装 → 强制释放（装不下的掉落，
     * 无空桶可用的流体滞留）。释放后条目清空则消耗本品。
     */
    private void releaseContents(ServerPlayer player, ItemStack stack, InteractionHand hand, UUID uuid) {
        var contents = MatterMassStore.global().getContents(uuid);
        if (contents.isEmpty()) {
            clearArmed(uuid);
            stack.shrink(1);
            return;
        }

        var itemReleases = new ArrayList<ItemStack>();
        var fluidReleases = new ArrayList<FluidRelease>();
        buildReleasePlan(contents, itemReleases, fluidReleases);

        var inventory = player.getInventory();
        boolean feasible = simulateRelease(inventory, itemReleases, fluidReleases);
        if (!feasible && !isArmed(uuid, player.level().getGameTime())) {
            ARMED.put(uuid, player.level().getGameTime());
            player.displayClientMessage(
                    Component.translatable("gui.chexsonsaeutils.matter_mass.release_blocked")
                            .withStyle(ChatFormatting.RED), true);
            return;
        }

        for (var itemStack : itemReleases) {
            if (!inventory.add(itemStack)) {
                player.drop(itemStack, false);
            }
        }
        var store = MatterMassStore.global();
        for (var entry : contents) {
            if (entry.what() instanceof AEItemKey) {
                store.extract(uuid, entry.what(), entry.amount(), appeng.api.config.Actionable.MODULATE);
            }
        }

        int availableBuckets = inventory.countItem(Items.BUCKET);
        for (var fluid : fluidReleases) {
            int usableBuckets = Math.min(fluid.buckets(), availableBuckets);
            availableBuckets -= usableBuckets;
            if (usableBuckets <= 0) {
                continue;
            }
            takeBuckets(inventory, usableBuckets);
            var bucketStack = new ItemStack(fluid.key().getFluid().getBucket(), 1);
            for (int i = 0; i < usableBuckets; i++) {
                if (!inventory.add(bucketStack.copy())) {
                    player.drop(bucketStack.copy(), false);
                }
            }
            store.extract(uuid, fluid.key(), (long) usableBuckets * AEFluidKey.AMOUNT_BUCKET,
                    appeng.api.config.Actionable.MODULATE);
        }

        clearArmed(uuid);
        if (store.isEmpty(uuid)) {
            stack.shrink(1);
        }
    }

    /** 把内容物转成可释放形式：物品直接成栈（按堆叠上限拆分）；流体按整桶计。 */
    private static void buildReleasePlan(List<GenericStack> contents, List<ItemStack> itemReleases,
            List<FluidRelease> fluidReleases) {
        for (var entry : contents) {
            var key = entry.what();
            var amount = entry.amount();
            if (key instanceof AEItemKey itemKey) {
                int max = itemKey.getMaxStackSize();
                while (amount > 0) {
                    int count = (int) Math.min(amount, max);
                    itemReleases.add(itemKey.toStack(count));
                    amount -= count;
                }
            } else if (key instanceof AEFluidKey fluidKey) {
                int buckets = (int) (amount / AEFluidKey.AMOUNT_BUCKET);
                long remainder = amount % AEFluidKey.AMOUNT_BUCKET;
                if (buckets > 0) {
                    fluidReleases.add(new FluidRelease(fluidKey, amount, buckets, remainder));
                }
            }
            // 其余 AEKey 类型：无法转成物品形式，滞留在物质团中
        }
    }

    /** 从背包（主背包+副手）扣减指定数量的空桶。 */
    private static void takeBuckets(net.minecraft.world.entity.player.Inventory inventory, int count) {
        int remaining = count;
        for (int i = 0; i < inventory.items.size() && remaining > 0; i++) {
            var slot = inventory.items.get(i);
            if (slot.is(Items.BUCKET)) {
                int take = Math.min(remaining, slot.getCount());
                slot.shrink(take);
                remaining -= take;
            }
        }
        for (int i = 0; i < inventory.offhand.size() && remaining > 0; i++) {
            var slot = inventory.offhand.get(i);
            if (slot.is(Items.BUCKET)) {
                int take = Math.min(remaining, slot.getCount());
                slot.shrink(take);
                remaining -= take;
            }
        }
    }

    /** 在背包副本上推演释放：返回是否全部可容纳（含空桶充足）。 */
    private static boolean simulateRelease(net.minecraft.world.entity.player.Inventory inventory,
            List<ItemStack> itemReleases, List<FluidRelease> fluidReleases) {
        var slots = new ArrayList<ItemStack>();
        for (var stack : inventory.items) {
            slots.add(stack.copy());
        }
        for (var stack : inventory.offhand) {
            slots.add(stack.copy());
        }

        for (var release : itemReleases) {
            if (simulateInsert(slots, release) > 0) {
                return false;
            }
        }

        int bucketsNeeded = 0;
        for (var fluid : fluidReleases) {
            bucketsNeeded += fluid.buckets();
        }
        int bucketsAvailable = 0;
        for (var slot : slots) {
            if (slot.is(Items.BUCKET)) {
                bucketsAvailable += slot.getCount();
            }
        }
        if (bucketsAvailable < bucketsNeeded) {
            return false;
        }
        // 填充桶不可堆叠：原空桶堆未耗尽时每桶需额外空槽
        for (int i = 0; i < bucketsNeeded; i++) {
            if (simulateInsert(slots, new ItemStack(Items.WATER_BUCKET)) > 0) {
                return false;
            }
        }
        return true;
    }

    /** 模拟插入：先合并同类堆叠，再占空槽；返回剩余量。 */
    private static int simulateInsert(List<ItemStack> slots, ItemStack toInsert) {
        int remaining = toInsert.getCount();
        for (var slot : slots) {
            if (remaining <= 0) {
                break;
            }
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, toInsert)
                    && slot.getMaxStackSize() > slot.getCount()) {
                int moved = Math.min(remaining, slot.getMaxStackSize() - slot.getCount());
                slot.grow(moved);
                remaining -= moved;
            }
        }
        for (int i = 0; i < slots.size() && remaining > 0; i++) {
            var slot = slots.get(i);
            if (slot.isEmpty()) {
                int moved = Math.min(remaining, toInsert.getMaxStackSize());
                var copy = toInsert.copy();
                copy.setCount(moved);
                slots.set(i, copy);
                remaining -= moved;
            }
        }
        return remaining;
    }

    private static boolean isArmed(UUID uuid, long gameTime) {
        var armedAt = ARMED.get(uuid);
        return armedAt != null && gameTime - armedAt <= ARM_WINDOW_TICKS;
    }

    private static void clearArmed(@Nullable UUID uuid) {
        if (uuid != null) {
            ARMED.remove(uuid);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flags) {
        var uuid = getUuid(stack);
        if (uuid == null) {
            return;
        }
        // 内容物仅服务端/单机可见（专用服务器客户端无存储副本）
        var contents = MatterMassStore.global().getContents(uuid);
        if (contents.isEmpty()) {
            lines.add(Component.translatable("tooltip.chexsonsaeutils.matter_mass.empty")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }
        for (var entry : contents) {
            lines.add(Component.empty()
                    .append(entry.what().getDisplayName())
                    .append(Component.literal(" x" + entry.amount()).withStyle(ChatFormatting.GRAY)));
        }
    }

    private record FluidRelease(AEFluidKey key, long amount, int buckets, long remainder) {
    }
}
