package git.chexson.chexsonsaeutils.events;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.cell.MatterMassStore;
import git.chexson.chexsonsaeutils.item.mattermass.MatterMassItem;

/**
 * 物质团背包聚合（事件驱动，无轮询）。
 * <p>
 * 判等：显示名 + 内容物多重集完全相同的物质团视为"完全相同"。
 * 合并：保留首个（幸存者），其余团的内容物并入幸存者条目（总量相加），
 * 其余团物品与其内容物条目一并删除——无内容物丢失。
 * 仅在低频时刻触发：拾取物质团、关闭容器菜单（覆盖终端提取等来源）、
 * 机器交付后（由 MatterMassPatternProviderLogic 直接调用）。无物质团时零开销。
 */
public final class MatterMassMergeHandler {

    private MatterMassMergeHandler() {
    }

    /** 背包内一张物质团的定位快照（所在列表 + 序号 + 内容物副本）。 */
    private record MassSlot(NonNullList<ItemStack> slots, int index, UUID uuid, String name,
            List<GenericStack> contents) {
    }

    /** 服务端：合并玩家背包（含副手）中名称与内容物完全相同的物质团。 */
    public static void tryMerge(Player player) {
        if (player.level().isClientSide) {
            return;
        }
        var inventory = player.getInventory();
        var masses = new ArrayList<MassSlot>();
        collect(inventory.items, masses);
        collect(inventory.offhand, masses);
        if (masses.size() < 2) {
            return;
        }

        // 按（名称, 内容物多重集）聚簇
        var clusters = new ArrayList<List<MassSlot>>();
        for (var mass : masses) {
            List<MassSlot> target = null;
            for (var cluster : clusters) {
                var head = cluster.get(0);
                if (head.name().equals(mass.name()) && contentsEqual(head.contents(), mass.contents())) {
                    target = cluster;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                clusters.add(target);
            }
            target.add(mass);
        }

        var store = MatterMassStore.global();
        boolean merged = false;
        for (var cluster : clusters) {
            if (cluster.size() < 2) {
                continue;
            }
            var survivor = cluster.get(0);
            for (int i = 1; i < cluster.size(); i++) {
                var other = cluster.get(i);
                // 取走其余团内容物并删除其条目，再并入幸存者条目（总量相加）
                var otherContents = store.takeAll(other.uuid());
                store.append(survivor.uuid(), otherContents);
                other.slots().set(other.index(), ItemStack.EMPTY);
                merged = true;
            }
        }
        if (merged) {
            inventory.setChanged();
        }
    }

    private static void collect(NonNullList<ItemStack> slots, List<MassSlot> out) {
        var store = MatterMassStore.global();
        for (int i = 0; i < slots.size(); i++) {
            var stack = slots.get(i);
            if (!MatterMassItem.isMatterMass(stack)) {
                continue;
            }
            var uuid = MatterMassItem.getUuid(stack);
            if (uuid == null) {
                continue;
            }
            out.add(new MassSlot(slots, i, uuid, stack.getHoverName().getString(),
                    store.getContents(uuid)));
        }
    }

    /** 内容物多重集相等（忽略顺序，同 key 数量求和后比较）。 */
    private static boolean contentsEqual(List<GenericStack> a, List<GenericStack> b) {
        return toMultiset(a).equals(toMultiset(b));
    }

    private static Map<AEKey, Long> toMultiset(List<GenericStack> contents) {
        var map = new HashMap<AEKey, Long>();
        for (var stack : contents) {
            if (stack != null && stack.amount() > 0) {
                map.merge(stack.what(), stack.amount(), Long::sum);
            }
        }
        return map;
    }

    /** 拾取事件：拾取物质团时触发聚合检查（物品已入包后触发，仅服务端）。 */
    public static void onItemPickup(ItemEntityPickupEvent.Post event) {
        if (MatterMassItem.isMatterMass(event.getOriginalStack())) {
            tryMerge(event.getPlayer());
        }
    }

    /** 容器关闭事件：背包可能经终端提取等路径新增物质团，廉价计数后按需聚合。 */
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        var player = event.getEntity();
        if (player.level().isClientSide) {
            return;
        }
        if (countMasses(player) >= 2) {
            tryMerge(player);
        }
    }

    private static int countMasses(Player player) {
        int count = 0;
        var inventory = player.getInventory();
        for (var stack : inventory.items) {
            if (MatterMassItem.isMatterMass(stack)) {
                count++;
            }
        }
        for (var stack : inventory.offhand) {
            if (MatterMassItem.isMatterMass(stack)) {
                count++;
            }
        }
        return count;
    }
}
