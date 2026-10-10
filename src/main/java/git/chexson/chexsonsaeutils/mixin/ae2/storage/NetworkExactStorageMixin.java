package git.chexson.chexsonsaeutils.mixin.ae2.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.me.storage.NetworkStorage;
import git.chexson.chexsonsaeutils.api.storage.ExactStorageExtraction;
import git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion;
import git.chexson.chexsonsaeutils.api.storage.MountedStorageExtraction;
import java.math.BigInteger;
import java.util.List;
import java.util.NavigableMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** 精确提取沿用网络的挂载锁、低优先级先取及延迟卸载规则。 */
@Mixin(value = NetworkStorage.class, remap = false)
public abstract class NetworkExactStorageMixin implements ExactStorageExtraction, ExactStorageInsertion {
    @Shadow private boolean mountsInUse;
    @Shadow @Final private NavigableMap<Integer, List<MEStorage>> priorityInventory;
    @Shadow private boolean isQueuedForRemoval(MEStorage storage) { throw new AssertionError(); }
    @Shadow private void flushQueuedOperations() { throw new AssertionError(); }

    /** 大数扣料在拒单或取消后也可整笔回网，无须按数量循环 long 窗口。 */
    @Override public BigInteger insertExact(AEKey what, BigInteger amount, Actionable mode, IActionSource source) {
        if (amount.signum() < 0) throw new IllegalArgumentException("存储插入量不得为负");
        if (amount.signum() == 0 || mountsInUse) return BigInteger.ZERO;
        if ((Object) this instanceof git.chexson.chexsonsaeutils.api.storage.NativeExactStorageInsertion nativeInsertion)
            return nativeInsertion.insertNativeExact(what, amount, mode, source);
        if (amount.bitLength() < 64) return BigInteger.valueOf(
                ((MEStorage) (Object) this).insert(what, amount.longValueExact(), mode, source));
        mountsInUse = true;
        try {
            return ExactStorageInsertion.insertIntoMounts(priorityInventory.values(), this::isQueuedForRemoval,
                    what, amount, mode, source);
        } finally {
            mountsInUse = false;
            flushQueuedOperations();
        }
    }

    @Override public BigInteger extractExact(AEKey what, BigInteger amount, Actionable mode, IActionSource source) {
        if (amount.signum() < 0) throw new IllegalArgumentException("存储提取量不得为负");
        if (amount.signum() == 0 || mountsInUse) return BigInteger.ZERO;
        if (amount.bitLength() < 64) return BigInteger.valueOf(
                ((MEStorage) (Object) this).extract(what, amount.longValueExact(), mode, source));
        mountsInUse = true;
        try {
            return ExactStorageExtraction.extractFromMounts(priorityInventory.descendingMap().values(),
                    this::isQueuedForRemoval,
                    (Object) this instanceof MountedStorageExtraction policy ? policy : null,
                    what, amount, mode, source);
        } finally {
            mountsInUse = false;
            flushQueuedOperations();
        }
    }
}
