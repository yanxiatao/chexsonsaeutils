package git.chexson.chexsonsaeutils.crafting.numeric;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import git.chexson.chexsonsaeutils.bigint.ByteAccount;

class ByteAccountTest {

    private static AEItemKey paper() {
        return AEItemKey.of(new ItemStack(Items.PAPER));
    }

    @Test
    void matchesAe2DoubleFormulaBitForBitInExactDomain() {
        var account = new ByteAccount();
        long[] amounts = {1L, 3L, 100L, 4096L};
        long[] multipliers = {1L, 2L, 7L, 13L};
        double expected = 0.0;
        for (long amount : amounts) {
            for (long multiplier : multipliers) {
                account.add(paper(), amount, multiplier);
                expected += (double) amount * multiplier / paper().getAmountPerByte() * 8;
            }
        }
        assertEquals((long) Math.ceil(expected), account.value().fastValue(), "精确域内与 AE2 double 读数逐位相等");
        assertEquals(expected, account.ae2Bytes(), "double 累加器位模式一致");
    }

    @Test
    void addBytesDirectMatchesAe2Path() {
        var account = new ByteAccount();
        account.addBytes(1234.5);
        account.addBytes(0.5);
        assertEquals(1235L, account.value().fastValue());
    }

    @Test
    void beyondDoubleExactnessUsesPreciseIntegerAccount() {
        var account = new ByteAccount();
        long amount = (1L << 60) + 1L;
        account.add(paper(), amount, 1L);

        // 物品 apb=8：精确值 = ceil(8·amount/8) = amount；double 侧 2^60+1 舍入为 2^60（间隔 256）
        var expected = BigInteger.valueOf(amount);
        assertEquals(expected, account.value().exactValue(), "精确值 = 2^60+1");

        double ae2Reading = Math.ceil(account.ae2Bytes());
        // long 先参与比较：double 混合比较会把 amount 舍入回 2^60
        assertTrue((long) ae2Reading < amount, "AE2 double 读数丢精度，精确值必须更大");
        assertEquals(BigInteger.TWO.pow(60), BigInteger.valueOf((long) ae2Reading), "double 读数落在 2^60");
    }

    @Test
    void valueFallsBackToPreciseWhenBeyondLongDomain() {
        var account = new ByteAccount();
        account.add(paper(), Long.MAX_VALUE, 16L);

        // 精确值 = ceil(8·MAX·16/8) = MAX·16，超出 long 域
        var expected = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(16L));
        assertEquals(expected, account.value().exactValue());
    }

    @Test
    void copyAndRestoreRoundTrip() {
        var account = new ByteAccount();
        account.add(paper(), 100L, 3L);
        var snapshot = account.copy();

        account.add(paper(), 55L, 1L);
        account.restore(snapshot);

        double expected = (double) 100L * 3L / paper().getAmountPerByte() * 8;
        assertEquals(expected, account.ae2Bytes());
        assertEquals((long) Math.ceil(expected), account.value().fastValue());
    }
    @Test
    void wideNodeFeesAndMaterialFeesAreBothCountedExactly() {
        var account = new ByteAccount();
        var nodes = BigInteger.TEN.pow(400).add(BigInteger.ONE);
        account.addBytes(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(nodes));
        account.add(paper(), Long.MAX_VALUE, 16);
        var expected = nodes.add(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(16)));
        assertEquals(expected, account.value().exactValue());
        var saved = account.copy();
        account.addBytes(1234.5);
        account.restore(saved);
        assertEquals(expected, account.value().exactValue());
    }

    @Test
    void journalUndoRestoresWideNodeFeesAndMixedMaterialFees() {
        var state = new git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState(java.util.Map.of(), java.util.Map.of());
        state.addBytes(5.5);
        int mark = state.journal();
        state.addBytes(git.chexson.chexsonsaeutils.bigint.PlanAmount.of(BigInteger.TEN.pow(40)));
        state.addStackBytesWide(paper(), 7, git.chexson.chexsonsaeutils.bigint.PlanAmount.of(BigInteger.TEN.pow(35)));
        assertEquals(BigInteger.TEN.pow(40).add(BigInteger.TEN.pow(35).multiply(BigInteger.valueOf(7))).add(BigInteger.valueOf(6)),
                state.byteAccount().value().exactValue());
        state.undoTo(mark);
        assertEquals(6L, state.byteAccount().value().fastValue());
        assertEquals(5.5, state.byteAccount().ae2Bytes());
    }
}
