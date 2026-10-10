package git.chexson.chexsonsaeutils.crafting.submit;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 提交腿短交读数一行（T2 第一格）：两条 {@code continue} 分支旧形状要么一个数都不印，
 * 要么只印 {@code gap} 的截断读数——"配额齐、池子空"从账面无从对起。
 *
 * <p>断的是<b>整串</b>：摘掉 {@code 已抽=} 或 {@code 短交=} 任何一格即红。
 */
final class PartialSubmitShortfallReadoutTest {

    private static final AEKey LAVA = AEItemKey.of(Items.LAVA_BUCKET);

    @Test
    void bothShortfallRowsCarryExtractedAndWideGapVerbatim() {
        assertEquals("[chexsonsaeutils-submit] beyond-long remainder not awaited"
                        + "（提交这一步不搬的那一截交给执行侧按批续取）:"
                        + " key=" + LAVA + " 已抽=0 短交=14000000000000000000",
                CraftingContinuationPartialSubmit.describeShortfallRow(
                        "beyond-long remainder not awaited（提交这一步不搬的那一截交给执行侧按批续取）",
                        LAVA, 0L, new BigInteger("14000000000000000000")));

        // declared shortfall 那一支：到账 120、真需 42662400000 ⇒ 短交 = 42662399880，宽域原文直出。
        assertEquals("[chexsonsaeutils-submit] declared shortfall not awaited"
                        + "（计划的缺口账不是在途产出）:"
                        + " key=" + LAVA + " 已抽=120 短交=42662399880",
                CraftingContinuationPartialSubmit.describeShortfallRow(
                        "declared shortfall not awaited（计划的缺口账不是在途产出）",
                        LAVA, 120L, BigInteger.valueOf(42_662_399_880L)));
    }
}
