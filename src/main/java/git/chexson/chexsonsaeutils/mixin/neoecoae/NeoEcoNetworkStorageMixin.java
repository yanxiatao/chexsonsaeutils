package git.chexson.chexsonsaeutils.mixin.neoecoae;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.me.storage.NetworkStorage;
import git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion;
import java.math.BigInteger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;

/** 在模组存在时桥接官方网络插入 API，保留真实挂载优先级及库存权限检查。 */
@Mixin(value = NetworkStorage.class, remap = false)
public abstract class NeoEcoNetworkStorageMixin implements ExactStorageInsertion, git.chexson.chexsonsaeutils.api.storage.ExactNetworkSupply,
        git.chexson.chexsonsaeutils.api.storage.MountedStorageExtraction,
        git.chexson.chexsonsaeutils.api.storage.NativeExactStorageInsertion {
    @Shadow @Final private java.util.NavigableMap<Integer, java.util.List<MEStorage>> priorityInventory;

    @Override public BigInteger insertNativeExact(AEKey what, BigInteger amount, Actionable mode, IActionSource source) {
        return cn.dancingsnow.neoecoae.api.storage.ECOBigIntegerStorage.insert((MEStorage) (Object) this,
                what, amount, mode, source);
    }

    /** 复用 NeoEco 的来源排除及创造提取上下文；long 回调只传成功标志，不承载数量。 */
    @Override public BigInteger extractMountedExact(MEStorage storage, AEKey what, BigInteger amount,
            Actionable mode, IActionSource source) {
        if (cn.dancingsnow.neoecoae.impl.storage.transfer.StorageExtractionExclusions.contains(storage))
            return BigInteger.ZERO;
        BigInteger[] actual = {BigInteger.ZERO};
        long permitted = cn.dancingsnow.neoecoae.impl.storage.transfer.ECOCreativeExtractionFilter.extractSource(
                storage, what, mode, () -> {
                    actual[0] = git.chexson.chexsonsaeutils.api.storage.ExactStorageExtraction.extract(
                            storage, what, amount, mode, source);
                    return actual[0].signum() > 0 ? 1L : 0L;
                });
        return permitted > 0 ? actual[0] : BigInteger.ZERO;
    }

    @Override public Snapshot readExactSupply() {
        var amounts = new git.chexson.chexsonsaeutils.integration.terminalstorage.neoecoae.NeoEcoNetworkExactInventoryImpl()
                .readFrom((MEStorage) (Object) this);
        var finite = new java.util.HashMap<AEKey, BigInteger>();
        var unbounded = new java.util.HashSet<AEKey>();
        amounts.forEach((key, amount) -> {
            if (amount.unbounded()) unbounded.add(key); else finite.put(key, amount.value());
        });
        for (var mounted : priorityInventory.values()) {
            for (MEStorage visible : mounted) {
                unbounded.addAll(git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoUnboundedSupply.read(visible));
            }
        }
        unbounded.forEach(finite::remove);
        return new Snapshot(finite, unbounded);
    }

}
