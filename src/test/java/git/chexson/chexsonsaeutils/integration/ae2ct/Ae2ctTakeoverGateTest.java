package git.chexson.chexsonsaeutils.integration.ae2ct;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan;
import net.minecraft.world.item.Items;

/**
 * ae2ct 兼容层必须接管非原生计划，防止其 CraftingPlan 强转失败并丢失预览。
 * 原生计划仍交由 AE2 与 ae2ct 的正常处理路径。
 */
final class Ae2ctTakeoverGateTest {

    private static final AEItemKey OUTPUT = AEItemKey.of(Items.IRON_INGOT);

    /** 原生计划仍归 AE2 与 ae2ct：一个字都不许抢。 */
    @Test
    void nativePlanIsLeftToAe2AndAe2ct() {
        assertFalse(Ae2ctJobBridge.takesOver(nativePlan()));
    }

    /** 我们的宽域载体不是 CraftingPlan 的子类：必须接管并填好 ae2ct 那份 helper。 */
    @Test
    void ourWideCarriersAreTakenOver() {
        assertTrue(Ae2ctJobBridge.takesOver(
                        new WideAmountCraftingPlan(nativePlan(), null, null, java.util.Set.of())),
                "残缺计划的回执就是这一类：漏了它就是把连接掐断");
    }

    /** 计划缺席时不接管，也不许抛。 */
    @Test
    void nullPlanIsNotTakenOver() {
        assertFalse(Ae2ctJobBridge.takesOver(null));
    }

    private static CraftingPlan nativePlan() {
        return new CraftingPlan(new GenericStack(OUTPUT, 1L), 0L, false, false,
                new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
    }
}
