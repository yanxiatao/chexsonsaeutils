package git.chexson.chexsonsaeutils.mixin.neoecoae;

import appeng.api.config.Actionable;
import appeng.api.config.IncludeExclude;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.me.storage.DelegatingMEInventory;
import appeng.me.storage.MEInventoryHandler;
import appeng.util.prioritylist.IPartitionList;
import cn.dancingsnow.neoecoae.api.storage.ECOBigIntegerStorage;
import cn.dancingsnow.neoecoae.crafting.amount.ExactAmount;
import cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountSource;
import git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion;
import git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoExactStorageActivity;
import java.math.BigInteger;
import java.util.function.BiConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** 保留驱动器及存储总线包装层的插入过滤，向底层传递完整实物数量。 */
@Mixin(value = MEInventoryHandler.class, remap = false)
public abstract class NeoEcoInventoryHandlerMixin extends DelegatingMEInventory
        implements ECOBigIntegerStorage, ExactStorageInsertion, ExactAmountSource,
        git.chexson.chexsonsaeutils.api.storage.UnboundedStorageSupply,
        git.chexson.chexsonsaeutils.api.storage.NativeExactStorageInsertion {
    @Shadow private boolean allowInsertion;
    @Shadow private boolean voidOverflow;
    @Shadow private IPartitionList partitionList;
    @Shadow private IncludeExclude partitionListMode;

    protected NeoEcoInventoryHandlerMixin(MEStorage delegate) { super(delegate); }

    @Override public BigInteger insertNativeExact(AEKey what, BigInteger amount, Actionable mode, IActionSource source) {
        return ECOBigIntegerStorage.insert((MEStorage) (Object) this, what, amount, mode, source);
    }

    @Override public java.util.Set<AEKey> unboundedSupplyKeys() {
        var result = new java.util.HashSet<AEKey>();
        for (AEKey key : git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoUnboundedSupply.read(getDelegate())) {
            if (((MEStorage) (Object) this).extract(key, 1, Actionable.SIMULATE,
                    appeng.api.networking.security.IActionSource.empty()) == 1) result.add(key);
        }
        return java.util.Set.copyOf(result);
    }

    @Override
    public void neoecoae$visitExactAmounts(BiConsumer<AEKey, ExactAmount> visitor) {
        // 包装层已经应用提取权限和可见性过滤，只修正实际可见键的数量。
        var visible = ((MEStorage) (Object) this).getAvailableStacks();
        if (getDelegate() instanceof ExactAmountSource source) {
            source.neoecoae$visitExactAmounts((key, amount) -> {
                if (visible.get(key) > 0) visitor.accept(key, amount);
            });
        } else {
            for (var entry : visible) {
                visitor.accept(entry.getKey(), ExactAmount.finite(BigInteger.valueOf(entry.getLongValue())));
            }
        }
    }

    @Override
    public BigInteger insertBigInteger(AEKey key, BigInteger amount, Actionable mode, IActionSource source) {
        if (amount.signum() < 0) throw new IllegalArgumentException("存储插入量不得为负");
        if (!allowInsertion || !partitionList.matchesFilter(key, partitionListMode)) return BigInteger.ZERO;
        BigInteger accepted = ECOBigIntegerStorage.insert(getDelegate(), key, amount, mode, source);
        if (mode == Actionable.MODULATE && accepted.signum() > 0
                && (Object) this instanceof NeoEcoExactStorageActivity activity) {
            activity.exactStorageInserted();
        }
        return voidOverflow ? amount : accepted;
    }
}
