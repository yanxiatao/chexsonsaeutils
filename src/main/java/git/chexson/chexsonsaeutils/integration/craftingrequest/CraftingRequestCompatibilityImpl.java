package git.chexson.chexsonsaeutils.integration.craftingrequest;

import appeng.menu.me.crafting.CraftAmountMenu;
import git.chexson.chexsonsaeutils.crafting.request.CraftingRequestCompatibility;
import java.math.BigInteger;
import java.util.Optional;
import net.neoforged.fml.ModList;

/** 可选下单 API 的启动装配；缺失模组时不加载对应 SDK 类。 */
public final class CraftingRequestCompatibilityImpl implements CraftingRequestCompatibility {
    private final boolean data = ModList.get().isLoaded("data_energistics");
    private final boolean ae2lt = ModList.get().isLoaded("ae2lt");
    @Override public appeng.api.networking.security.IActionSource netNew(appeng.api.networking.security.IActionSource source) {
        return data ? Data.netNew(source) : source;
    }
    @Override public Optional<Boolean> finalTotal(CraftAmountMenu menu) {
        return data ? Optional.of(Data.finalTotal(menu)) : Optional.empty();
    }
    @Override public void finalTotal(CraftAmountMenu menu, boolean value) {
        if (data) Data.finalTotal(menu, value);
        else if (value) throw new IllegalArgumentException("当前实例没有数量模式扩展");
    }
    @Override public Optional<BigInteger> initialAmount(CraftAmountMenu menu) {
        if (ae2lt) {
            var amount = Ae2lt.initial(menu);
            if (amount.isPresent()) return amount;
        }
        return data ? Data.initial(menu) : Optional.empty();
    }
    @Override public boolean submit(CraftAmountMenu menu, BigInteger amount, boolean missing, boolean autoStart) {
        // AE2LT 的服务只对特定终端可用；传递最小单位，不重新换算显示单位。
        if (ae2lt && Ae2lt.submit(menu, amount, missing || (data && Data.finalTotal(menu)), autoStart)) return true;
        if (data && amount.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0) {
            Data.submit(menu, amount.longValueExact(), missing, autoStart);
            return true;
        }
        return false;
    }
    private static final class Data {
        static appeng.api.networking.security.IActionSource netNew(appeng.api.networking.security.IActionSource source) {
            return com.fish_dan_.data_energistics.common.crafting.trinity.planning.request.TrinityCraftingRequestContext.attach(
                    source, com.fish_dan_.data_energistics.common.crafting.trinity.planning.CraftingQuantityMode.NET_NEW);
        }
        private static com.fish_dan_.data_energistics.menu.crafting.TrinityCraftAmountMenuState state(CraftAmountMenu menu) {
            return (com.fish_dan_.data_energistics.menu.crafting.TrinityCraftAmountMenuState) (Object) menu;
        }
        static boolean finalTotal(CraftAmountMenu menu) {
            return state(menu).data_energistics$quantityMode() ==
                    com.fish_dan_.data_energistics.common.crafting.trinity.planning.CraftingQuantityMode.FINAL_TOTAL;
        }
        static void finalTotal(CraftAmountMenu menu, boolean value) {
            state(menu).data_energistics$setQuantityMode(value
                    ? com.fish_dan_.data_energistics.common.crafting.trinity.planning.CraftingQuantityMode.FINAL_TOTAL
                    : com.fish_dan_.data_energistics.common.crafting.trinity.planning.CraftingQuantityMode.NET_NEW);
        }
        static Optional<BigInteger> initial(CraftAmountMenu menu) {
            long amount = state(menu).data_energistics$initialAmount();
            return amount > 0 ? Optional.of(BigInteger.valueOf(amount)) : Optional.empty();
        }
        static void submit(CraftAmountMenu menu, long amount, boolean missing, boolean autoStart) {
            state(menu).data_energistics$confirm(amount, missing, autoStart);
        }
    }
    private static final class Ae2lt {
        private static com.moakiee.ae2lt.crafting.big.BigAmountMenu state(CraftAmountMenu menu) {
            return (com.moakiee.ae2lt.crafting.big.BigAmountMenu) (Object) menu;
        }
        static Optional<BigInteger> initial(CraftAmountMenu menu) {
            var text = state(menu).ae2lt$initialAmount();
            return text.isEmpty() ? Optional.empty() : Optional.of(new BigInteger(text));
        }
        static boolean submit(CraftAmountMenu menu, BigInteger amount, boolean missing, boolean autoStart) {
            if (!state(menu).ae2lt$bigAvailable()) return false;
            state(menu).ae2lt$confirm(amount, missing, autoStart);
            return true;
        }
    }
}
