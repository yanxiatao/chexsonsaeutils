package git.chexson.chexsonsaeutils.parts.playersimulation;

import appeng.api.stacks.AEItemKey;
import net.minecraft.world.item.ItemStack;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/** 临时将缓存交给玩家，确保正常返回、拒绝交互或抛出异常均会结算全部物品。 */
public final class SimulationInteraction {
    private SimulationInteraction() {}
    /** 调用真实行为；副产物与不属于原标记的物品交给退回出口。 */
    public static String execute(SimulationActor actor, SimulationBuffer buffer, int selected, int size,
                                 IntFunction<AEItemKey> keys, Supplier<String> action, Consumer<ItemStack> returnItem) {
        var inventory = actor.inventory();
        int[] borrowed = new int[36];
        Arrays.fill(borrowed,-1);
        if (selected >= 0) borrowed[0] = selected;
        int auxiliary = 1;
        for (int i = 0; i < size && auxiliary < borrowed.length; i++)
            if (i != selected && !buffer.get(i).isEmpty()) borrowed[auxiliary++] = i;
        try {
            for (int i = 0; i < borrowed.length; i++) if (borrowed[i] >= 0)
                inventory.setItem(i,buffer.take(borrowed[i]));
            actor.hold(inventory.getItem(0));
            return action.get();
        } finally {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                var stack = inventory.removeItemNoUpdate(i);
                int origin = i < borrowed.length ? borrowed[i] : -1;
                var key = origin >= 0 ? keys.apply(origin) : null;
                if (!stack.isEmpty() && key != null && stack.is(key.getItem())) buffer.set(origin,key,stack);
                else if (!stack.isEmpty()) returnItem.accept(stack);
            }
            actor.suspendHand();
        }
    }
}
