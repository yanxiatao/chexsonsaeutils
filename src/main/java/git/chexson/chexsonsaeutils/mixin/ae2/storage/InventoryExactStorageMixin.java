package git.chexson.chexsonsaeutils.mixin.ae2.storage;

import appeng.api.config.Actionable;
import appeng.api.config.IncludeExclude;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.me.storage.DelegatingMEInventory;
import appeng.me.storage.MEInventoryHandler;
import git.chexson.chexsonsaeutils.api.storage.ExactStorageActivity;
import git.chexson.chexsonsaeutils.api.storage.ExactStorageExtraction;
import git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion;
import appeng.util.prioritylist.IPartitionList;
import java.math.BigInteger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** 精确提取保留 AE2 包装层的提取过滤，不绕过存储总线配置及底层来源权限。 */
@Mixin(value = MEInventoryHandler.class, remap = false)
public abstract class InventoryExactStorageMixin extends DelegatingMEInventory implements ExactStorageExtraction, ExactStorageInsertion {
    @Shadow private boolean filterOnExtraction;
    @Shadow protected abstract boolean canExtract(AEKey request);
    @Shadow private boolean allowInsertion;
    @Shadow private boolean voidOverflow;
    @Shadow private IPartitionList partitionList;
    @Shadow private IncludeExclude partitionListMode;
    protected InventoryExactStorageMixin(MEStorage delegate) { super(delegate); }

    /** 与 long 插入共用过滤及销毁卡规则，保留精确回料路径。 */
    @Override public BigInteger insertExact(AEKey what, BigInteger amount, Actionable mode, IActionSource source) {
        if (amount.signum() < 0) throw new IllegalArgumentException("存储插入量不得为负");
        if ((Object) this instanceof git.chexson.chexsonsaeutils.api.storage.NativeExactStorageInsertion nativeInsertion)
            return nativeInsertion.insertNativeExact(what, amount, mode, source);
        if (!allowInsertion || !partitionList.matchesFilter(what, partitionListMode)) return BigInteger.ZERO;
        BigInteger got = ExactStorageInsertion.insert(getDelegate(), what, amount, mode, source);
        if (mode == Actionable.MODULATE && got.signum() > 0
                && (Object) this instanceof ExactStorageActivity activity) activity.exactStorageChanged();
        return voidOverflow ? amount : got;
    }

    @Override public BigInteger extractExact(AEKey what, BigInteger amount, Actionable mode, IActionSource source) {
        if (amount.signum() < 0) throw new IllegalArgumentException("存储提取量不得为负");
        if (filterOnExtraction && !canExtract(what)) return BigInteger.ZERO;
        BigInteger got = ExactStorageExtraction.extract(getDelegate(), what, amount, mode, source);
        if (mode == Actionable.MODULATE && got.signum() > 0
                && (Object) this instanceof ExactStorageActivity activity) activity.exactStorageChanged();
        return got;
    }
}
