package git.chexson.chexsonsaeutils.crafting.material;

import git.chexson.chexsonsaeutils.crafting.material.PlanGrossAudit;

import git.chexson.chexsonsaeutils.crafting.material.SlotAccount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakeInput;
import git.chexson.chexsonsaeutils.crafting.solve.FakePlanGrid.FakePattern;
import net.minecraft.world.item.Items;

/**
 * 槽口径唯一实现（{@link SlotAccount}）的锁桩：模具槽整批只占一发起步量，
 * 可退料的槽不占首位键的锁。这两条曾经分别在毛账探针与执行侧占料账本里各写一遍，
 * 同一张表报出过四个缺口数字。
 */
class SlotAccountTest {

    private static final AEKey GLASS = AEItemKey.of(Items.GLASS);
    private static final AEKey SAND = AEItemKey.of(Items.SAND);
    private static final AEKey PRODUCT = AEItemKey.of(Items.IRON_INGOT);

    /** 毛账探针：原样退回的模具槽按一发收费，不按发数放大。 */
    @Test
    void grossAuditChargesOneFiringForSelfRepayingSlot() {
        assertEquals(3L, demandOf(FakeInput.multiWithRemainders(
                3L, Map.of(GLASS, GLASS), GLASS, SAND)));
    }

    /** 普通消耗槽照旧按 倍率 × 发数 收费。 */
    @Test
    void grossAuditChargesEveryFiringForConsumedSlot() {
        assertEquals(300L, demandOf(FakeInput.multi(3L, GLASS, SAND)));
    }

    /** 有退路＝除被排除的那个键外还有任一候选真有量；只有它自己有料时不算退路。 */
    @Test
    void alternativeLooksOnlyAtTheOtherCandidates() {
        var slot = FakeInput.multi(1L, GLASS, SAND);
        assertTrue(SlotAccount.hasAlternative(slot, GLASS,
                key -> key.equals(SAND) ? 7L : 0L));
        assertFalse(SlotAccount.hasAlternative(slot, GLASS,
                key -> key.equals(GLASS) ? 7L : 0L));
    }

    /** 顺位排水：先榨干首位，剩下的才退下一个；缺口记在首位键上。 */
    @Test
    void drainSpillsInCandidateOrderAndBlamesTheFirstCandidate() {
        var pool = new java.util.HashMap<AEKey, Long>();
        pool.put(GLASS, 6L);
        pool.put(SAND, 1L);
        var drained = SlotAccount.drainSlot(
                pool, FakeInput.multi(1L, GLASS, SAND), 10L);
        assertEquals(3L, drained.unfundedFirings());
        assertEquals(GLASS, drained.reported().what());
        assertEquals(0L, pool.get(GLASS).longValue());
        assertEquals(0L, pool.get(SAND).longValue());
    }

    /** 两槽共用一份池子：前一槽许诺过的量不能再给后一槽。 */
    @Test
    void drainSharesOnePoolInsteadOfPromisingItTwice() {
        var pool = new java.util.HashMap<AEKey, Long>();
        pool.put(GLASS, 6L);
        var slot = FakeInput.multi(1L, GLASS);
        assertEquals(0L, SlotAccount.drainSlot(pool, slot, 5L).unfundedFirings());
        assertEquals(4L, SlotAccount.drainSlot(pool, slot, 5L).unfundedFirings());
    }

    /** 模具槽整批只从池子里取那份起步量，不按发数放大。 */
    @Test
    void drainChargesSelfRepayingSlotOneFiring() {
        var pool = new java.util.HashMap<AEKey, Long>();
        pool.put(GLASS, 2L);
        var slot = FakeInput.multiWithRemainders(
                2L, Map.of(GLASS, GLASS), GLASS, SAND);
        assertEquals(0L, SlotAccount.drainSlot(pool, slot, 100L).unfundedFirings());
        assertEquals(0L, pool.get(GLASS).longValue());
    }

    /** 退料吃到的键不能从账上隐身：首位候选被抽干后，剩下的需求要记到第二候选头上。 */
    @Test
    void grossAuditBillsTheCandidateActuallySpilledTo() {
        var glassMaker = FakePattern.of(GLASS, 5L);
        var sandMaker = FakePattern.of(SAND, 1L);
        var consumer = FakePattern.of(PRODUCT, 1L, FakeInput.multi(1L, GLASS, SAND));
        var balances = PlanGrossAudit.measure(Map.<IPatternDetails, Long>of(
                glassMaker, 100L, sandMaker, 100L, consumer, 600L), null);
        // 旧口径把 600 全记在首位候选上，退料吃到的键在账上是零需求、零缺口。
        assertEquals(500L, balances.byKey().get(GLASS)[0].longValue());
        assertEquals(100L, balances.byKey().get(SAND)[0].longValue());
    }

    /** 出口报缺料吃的是带量的缺口表；整族排空时 spare 全零，才是真缺而不是顺序吃空。 */
    @Test
    void grossAuditHolesCarryAmountsAndSayTheFamilyIsDrained() {
        var glassMaker = FakePattern.of(GLASS, 5L);
        var sandMaker = FakePattern.of(SAND, 1L);
        var consumer = FakePattern.of(PRODUCT, 1L, FakeInput.multi(1L, GLASS, SAND));
        // 供给 100 玻璃 + 20 沙子，需求 130 发：首位排干再退第二候选，仍差 10。
        var balances = PlanGrossAudit.measure(Map.<IPatternDetails, Long>of(
                glassMaker, 20L, sandMaker, 20L, consumer, 130L), null);
        assertEquals(java.util.List.of(GLASS), PlanGrossAudit.holeKeys(balances));
        assertEquals(10L, PlanGrossAudit.holes(balances).get(0).getValue().longValue());
        assertTrue(PlanGrossAudit.describe(balances, null, null, null).contains("spare=[all-empty]"),
                PlanGrossAudit.describe(balances, null, null, null));
    }

    private static long demandOf(FakeInput input) {
        var pattern = FakePattern.of(PRODUCT, 1, input);
        var balances = PlanGrossAudit.measure(
                Map.<IPatternDetails, Long>of(pattern, 100L), null);
        return balances.byKey().get(GLASS)[0].longValue();
    }
}
