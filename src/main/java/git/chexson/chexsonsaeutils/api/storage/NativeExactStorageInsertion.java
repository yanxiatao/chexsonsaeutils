package git.chexson.chexsonsaeutils.api.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import java.math.BigInteger;

/** 可选存储框架已有大数插入时，公共入口沿用其原生路由及能力识别。 */
public interface NativeExactStorageInsertion {
    /** 调用原生大数入口，避免把外部框架的精确能力降级为 long。 */
    BigInteger insertNativeExact(AEKey what, BigInteger amount, Actionable mode, IActionSource source);
}
