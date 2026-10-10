package git.chexson.chexsonsaeutils.api.storage;

import appeng.api.stacks.AEKey;
import java.util.Set;

/** 存储实现明确承诺的无限资源供给能力；无限容量不具备这项能力。 */
public interface UnboundedStorageSupply {
    /** 返回可持续生成的精确资源键；调用者仍需通过实际挂载验证可见性与提取权限。 */
    Set<AEKey> unboundedSupplyKeys();
}
