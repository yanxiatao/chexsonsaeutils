package git.chexson.chexsonsaeutils.client.gui.matrix;

import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixClientView;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixEntry;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixRealStack;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixSearchTerms;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/** 将原版已同步的标记槽转换为矩阵视图，复用样板格子且无需第二套库存网络协议。 */
public final class MachineMarkerClientView {
    private final PatternMatrixClientView view = new PatternMatrixClientView();
    public PatternMatrixClientView update(List<ItemStack> stacks) {
        view.resize(stacks.size());
        var entries = new ArrayList<PatternMatrixEntry>();
        var real = new ArrayList<PatternMatrixRealStack>();
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (ItemStack.matches(view.realStackAt(slot), stack)) continue;
            var names = stack.isEmpty() ? List.<Component>of() : List.of(stack.getHoverName(),
                    Component.literal(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
            entries.add(new PatternMatrixEntry(slot, stack, 0, 0, new PatternMatrixSearchTerms(names, names)));
            if (!stack.isEmpty()) real.add(new PatternMatrixRealStack(slot, stack));
        }
        view.apply(entries);
        view.applyRealStacks(real);
        return view;
    }
}
