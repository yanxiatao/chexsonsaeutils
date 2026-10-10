package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.recursive.RingId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2d B2 —— undo 日志回滚恒等性：{@code undoTo(mark)} 之后每张账本
 * 必须与 mark 时刻逐位一致（absent 恢复为 absent、零标记恢复为零标记、
 * 宽值恢复为宽值、bytes 按位恢复），absent 误恢复为 0 标记会让
 * {@code cacheFuzzy} 短路、库存无法重拉——这是最容易写错的行为位。
 */
class PlanJournalTest {

    @Test
    void hundredDigitLedgerRestoresNestedMarksAndExactBytes() {
        var paper = key(Items.PAPER);
        var wide = git.chexson.chexsonsaeutils.bigint.PlanAmount.of(java.math.BigInteger.TEN.pow(100));
        var state = new GraphPlanningState(Map.of(paper, 17L), Map.of());
        int outer = state.journal();
        state.insert(paper, wide);
        state.emitItemsWide(paper, wide);
        state.addMissingWide(paper, wide);
        state.recordRingExtraction(paper, wide);
        state.addStackBytesWide(paper, 3L, wide);
        assertEquals(wide.multiply(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(24L))
                .ceilDiv(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(paper.getAmountPerByte())),
                state.byteAccount().exactValue());
        int inner = state.journal();
        var units = wide.divide(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(7L));
        assertEquals(units, state.extractTemplatesByIdWide(paper, state.idOf(paper), 7L, units));
        assertTrue(state.used().isEmpty(), "提取的是模拟产出，尚未触及原库存，不计 requiredExtract");
        state.reduceMissingWide(paper, wide);
        assertTrue(state.missing().isEmpty());
        state.undoTo(inner);
        assertEquals(wide.exactValue(), state.missing().getExact(paper));
        assertTrue(state.used().isEmpty());
        assertEquals(wide, state.extractByIdWide(paper, state.idOf(paper), wide));
        state.undoTo(outer);
        assertTrue(state.missing().isEmpty());
        assertTrue(state.emitted().isEmpty());
        assertTrue(state.ringExtractions().isEmpty());
        assertEquals(0.0, state.bytes());
        assertEquals(git.chexson.chexsonsaeutils.bigint.PlanAmount.ZERO, state.byteAccount().exactValue());
        assertEquals(17L, state.extract(paper, Long.MAX_VALUE, false));
    }

    @Test
    void negativeWideAndMinLongDeltasUndoExactly() {
        var paper = key(Items.PAPER);
        var state = new GraphPlanningState(Map.of(), Map.of());
        var amount = git.chexson.chexsonsaeutils.bigint.PlanAmount.of(java.math.BigInteger.TEN.pow(100));
        state.addMissingWide(paper, amount);
        int mark = state.journal();
        state.addMissing(paper, Long.MIN_VALUE);
        state.reduceMissingWide(paper, amount);
        assertEquals(java.math.BigInteger.valueOf(Long.MIN_VALUE), state.missing().getExact(paper));
        state.undoTo(mark);
        assertEquals(amount.exactValue(), state.missing().getExact(paper));
        var empty = new GraphPlanningState(Map.of(), Map.of());
        empty.insert(paper, amount.negate());
        empty.undoTo(0);
        assertEquals(0L, empty.extract(paper, Long.MAX_VALUE, true));
    }

    private static AEItemKey key(net.minecraft.world.item.Item item) {
        return AEItemKey.of(new ItemStack(item));
    }

    @Test
    void undoRestoresEveryLedger() {
        var paper = key(Items.PAPER);
        var state = new GraphPlanningState(Map.of(paper, 100L), Map.of());
        var ringId = RingId.of(paper, java.util.List.of());
        var pattern = new ThrowingPatternDetails();

        int mark = state.journal();
        state.addCrafting(pattern, 5);
        state.markRingMacroFailed(ringId);
        state.markRingSeedRepaid(ringId);
        state.recordRingExtraction(paper, 3);
        state.recordInternalItem(paper, 7);
        state.emitItems(paper, 2);
        state.addMissing(paper, 4);
        state.insert(paper, 10);
        state.extract(paper, 5, false);
        state.addBytes(16.0);
        state.addStackBytes(paper, 100, 1);
        assertTrue(state.hasRingSeedRepaid(ringId), "还种轮数已按 ringId 记上");

        state.undoTo(mark);

        assertTrue(state.crafts().isEmpty(), "crafts 回滚到空");
        assertFalse(state.hasRingMacroFailed(ringId));
        assertFalse(state.hasRingSeedRepaid(ringId), "环宏回滚后还种记账必须还原，否则重试不再加成");
        assertEquals(0, state.ringExtractions().getLong(paper));
        assertEquals(0, state.recursiveInternalItems().getLong(paper));
        assertTrue(state.emittedItems().isEmpty());
        assertTrue(state.missingItems().isEmpty());
        assertEquals(0, state.usedAmount(paper), "used 峰值回滚");
        assertEquals(0.0, state.bytes(), "double 读数按位回滚");
        assertEquals(100, state.extract(paper, 1000, true), "working 回滚到原库存");
    }

    @Test
    void undoRestoresAbsenceSoStockCanBePulledAgain() {
        var paper = key(Items.PAPER);
        var state = new GraphPlanningState(Map.of(paper, 100L), Map.of());

        int mark = state.journal();
        assertEquals(100, state.extract(paper, 100, false));
        state.undoTo(mark);

        assertEquals(100, state.extract(paper, 100, false), "undo 必须恢复 absent 而非 0 标记，否则 cacheFuzzy 短路后库存无法重拉");
    }

    @Test
    void undoRestoresWideValues() {
        var paper = key(Items.PAPER);
        var state = new GraphPlanningState(Map.of(paper, 10L), Map.of());

        int mark = state.journal();
        state.insert(paper, Long.MAX_VALUE - 5);
        assertEquals(Long.MAX_VALUE, state.extract(paper, Long.MAX_VALUE, true), "溢出键读窄视图哨兵");

        state.undoTo(mark);
        assertEquals(10, state.extract(paper, Long.MAX_VALUE, true), "宽值回滚后必须落回 long 域精确值");
    }

    @Test
    void nestedMarksRollBackInReverseOrder() {
        var paper = key(Items.PAPER);
        var state = new GraphPlanningState(Map.of(paper, 100L), Map.of());

        int outer = state.journal();
        state.insert(paper, 10);
        int inner = state.journal();
        state.insert(paper, 20);

        state.undoTo(inner);
        assertEquals(110, state.extract(paper, 1000, true), "内层回滚只撤内层增量");
        state.undoTo(outer);
        assertEquals(100, state.extract(paper, 1000, true), "外层回滚撤掉全部增量");
    }

    /** 只为 crafts 账本提供一个非空键的轻量样板替身。 */
    private static final class ThrowingPatternDetails implements appeng.api.crafting.IPatternDetails {
        @Override
        public appeng.api.crafting.IPatternDetails.IInput[] getInputs() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<GenericStack> getOutputs() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AEItemKey getDefinition() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean supportsPushInputsToExternalInventory() {
            return false;
        }
    }
}
