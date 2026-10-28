package git.chexson.chexsonsaeutils.integration.neoecoae;

import java.math.BigInteger;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import git.chexson.chexsonsaeutils.api.storage.ExactStorageInsertion;
import cn.dancingsnow.neoecoae.api.storage.ECOBigIntegerStorage;

/** NeoEco 包装层识别本模组磁盘的精确能力，避免跨 API 后退成单个 long 窗口。 */
public final class NeoEcoExactStorageInsertion {
    private NeoEcoExactStorageInsertion() { }

    /** 包装层先检查过滤与权限，再按底层公开能力交付完整实物，返回实际接收量。 */
    public static BigInteger insert(MEStorage storage, AEKey key, BigInteger amount,
            Actionable mode, IActionSource source) {
        return storage instanceof ExactStorageInsertion
                ? ExactStorageInsertion.insert(storage, key, amount, mode, source)
                : ECOBigIntegerStorage.insert(storage, key, amount, mode, source);
    }
}
