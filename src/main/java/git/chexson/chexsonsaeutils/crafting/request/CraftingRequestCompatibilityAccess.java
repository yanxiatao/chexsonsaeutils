package git.chexson.chexsonsaeutils.crafting.request;

import java.util.Objects;

/** 启动装配与菜单间的契约绑定，避免菜单反向依赖第三方模组。 */
public final class CraftingRequestCompatibilityAccess {
    private static CraftingRequestCompatibility compatibility;
    private CraftingRequestCompatibilityAccess() {}
    /** common setup 绑定公开 API 适配。 */
    public static void bind(CraftingRequestCompatibility value) {
        if (compatibility != null) throw new IllegalStateException("下单兼容重复装配");
        compatibility = Objects.requireNonNull(value);
    }
    /** 获取已装配入口；缺失装配立即报错。 */
    public static CraftingRequestCompatibility get() { return Objects.requireNonNull(compatibility, "下单兼容尚未装配"); }
}
