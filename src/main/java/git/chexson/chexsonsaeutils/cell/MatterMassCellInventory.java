package git.chexson.chexsonsaeutils.cell;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import git.chexson.chexsonsaeutils.item.mattermass.MatterMassItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 物质团的只读存储单元实现（{@link StorageCell}）。
 * <p>
 * 语义：接受任意 AEKey、无限容量、只读——insert 恒拒绝，extract 委托
 * {@link MatterMassStore}；内容物按物质团 UUID 外部持久化，抽空后条目自动删除。
 * 放入 ME 驱动器即把内容物以只读方式暴露给网络；放入 IO 端口（EMPTY 模式）
 * 可把内容物抽入网络，配合 mixin 在抽空移槽时销毁物品本体。
 */
public final class MatterMassCellInventory implements StorageCell {

    private final ItemStack stack;
    @Nullable
    private final ISaveProvider saveProvider;
    @Nullable
    private final UUID id;

    public MatterMassCellInventory(ItemStack stack, @Nullable ISaveProvider saveProvider) {
        this.stack = stack;
        this.saveProvider = saveProvider;
        this.id = MatterMassItem.getUuid(stack);
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        return 0;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (id == null) {
            return 0;
        }
        var extracted = MatterMassStore.global().extract(id, what, amount, mode);
        if (extracted > 0 && mode == Actionable.MODULATE) {
            if (saveProvider != null) {
                saveProvider.saveChanges();
            }
            // 内容物抽空后物质团消失（与蹲下释放语义一致；栈为 stacksTo(1)）
            if (MatterMassStore.global().isEmpty(id)) {
                stack.shrink(1);
            }
        }
        return extracted;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        if (id != null) {
            MatterMassStore.global().getAvailableStacks(id, out);
        }
    }

    @Override
    public CellState getStatus() {
        return id == null || MatterMassStore.global().isEmpty(id) ? CellState.EMPTY : CellState.NOT_EMPTY;
    }

    @Override
    public double getIdleDrain() {
        return 0;
    }

    @Override
    public Component getDescription() {
        return stack.getHoverName();
    }

    @Override
    public void persist() {
        MatterMassStore.global().saveCurrentWorld();
    }
}
