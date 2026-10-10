package git.chexson.chexsonsaeutils.crafting.request;

import appeng.menu.me.crafting.CraftAmountMenu;
import java.math.BigInteger;
import java.util.Optional;

/** 可选下单模组的公开入口；数量始终以 AEKey 最小单位传递。 */
public interface CraftingRequestCompatibility {
    /** 已换算为净新增的请求显式携带语义，防止第三方默认配置再扣一次库存。 */
    default appeng.api.networking.security.IActionSource netNew(appeng.api.networking.security.IActionSource source) {
        return source;
    }
    /** 已安装的数量模式；空值表示当前菜单没有模式扩展。 */
    Optional<Boolean> finalTotal(CraftAmountMenu menu);
    /** 设置模式；没有模式扩展时不允许设置最终总数模式。 */
    void finalTotal(CraftAmountMenu menu, boolean value);
    /** 可选模组保存的最小单位初始数量。 */
    Optional<BigInteger> initialAmount(CraftAmountMenu menu);
    /** 尝试原模组提交入口；false 表示该菜单或数量不在其公开能力范围。 */
    boolean submit(CraftAmountMenu menu, BigInteger amount, boolean missing, boolean autoStart);
}
