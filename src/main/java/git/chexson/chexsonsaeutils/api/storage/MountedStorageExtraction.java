package git.chexson.chexsonsaeutils.api.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import java.math.BigInteger;

/** 可选模组的挂载来源提取规则；网络路由保留原模组的来源排除及实物检查。 */
public interface MountedStorageExtraction {
    /** 对单个真实挂载执行提取，返回通过来源规则后的实取量。 */
    BigInteger extractMountedExact(MEStorage storage, AEKey what, BigInteger amount,
            Actionable mode, IActionSource source);
}
