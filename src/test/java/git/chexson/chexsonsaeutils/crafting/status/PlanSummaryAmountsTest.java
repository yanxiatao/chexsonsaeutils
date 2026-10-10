package git.chexson.chexsonsaeutils.crafting.status;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import appeng.crafting.CraftingPlan;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import git.chexson.chexsonsaeutils.bigint.*;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountCraftingPlan;
import net.minecraft.world.item.Items;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class PlanSummaryAmountsTest {
    private static final AEItemKey KEY = AEItemKey.of(Items.IRON_INGOT);
    private static final BigInteger TIMES = BigInteger.TEN.pow(20);
    private record Pattern() implements IPatternDetails {
        @Override public AEItemKey getDefinition() { return AEItemKey.of(Items.STICK); }
        @Override public IInput[] getInputs() { return new IInput[0]; }
        @Override public List<GenericStack> getOutputs() { return List.of(new GenericStack(KEY, 2)); }
    }
    private WideAmountCraftingPlan plan() {
        var pattern = new Pattern();
        var used = new KeyCounter(); used.add(KEY, Long.MAX_VALUE);
        var missing = new KeyCounter(); missing.add(KEY, 20);
        var emit = new KeyCounter(); emit.add(KEY, 1);
        var exactUsed = new WideKeyCounter(); exactUsed.add(KEY, BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.TEN));
        var crafts = new WideCraftCounter(); crafts.add(pattern, PlanAmount.of(TIMES));
        return new WideAmountCraftingPlan(new CraftingPlan(new GenericStack(KEY, Long.MAX_VALUE), 1,
                true, false, used, emit, missing, Map.of(pattern, Long.MAX_VALUE)),
                WideLedgerSnapshot.capture(null, exactUsed, null), WideCraftSnapshot.capture(crafts), Set.of(), PlanAmount.of(TIMES));
    }
    @Test void summaryAddsAllExactComponentsWithoutUsingProjectedCraftCounts() {
        var counts = PlanSummaryAmounts.fromPlan(plan());
        var stored = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.valueOf(11));
        var output = TIMES.multiply(BigInteger.TWO).add(BigInteger.ONE);
        assertEquals(stored, counts.stored().get(KEY));
        assertEquals(BigInteger.valueOf(20), counts.missing().get(KEY));
        assertEquals(output, counts.crafting().get(KEY));
        assertEquals(stored, counts.displaySnapshot().get(WideAmountKind.USED, KEY));
        assertEquals(output, counts.displaySnapshot().get(WideAmountKind.EMIT, KEY));
        assertEquals(Long.MAX_VALUE, counts.entries().getFirst().getCraftAmount());
        assertEquals(20, counts.entries().getFirst().getMissingAmount());
    }
    @Test void patternTimesUseWidePlanAndSurviveActualEntryProtocol() {
        var entry = new CraftingPlanSummaryEntry(KEY, 0, 0, Long.MAX_VALUE);
        var summary = new CraftingPlanSummary(1, false, List.of(entry));
        CraftingStatusEnhancer.attachPatternTimes(summary, plan());
        var enhanced = (EnhancedCraftingPlanSummaryEntry) (Object) entry;
        assertEquals(List.of(TIMES), enhanced.chexsonsaeutils$patternTimes());
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        try {
            entry.write(buf);
            var decoded = (EnhancedCraftingPlanSummaryEntry) (Object) CraftingPlanSummaryEntry.read(buf);
            assertEquals(List.of(TIMES), decoded.chexsonsaeutils$patternTimes());
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }
    @Test void normalizationSortsExactlyOnceAndFormattingRetainsCompleteDigits() {
        var sorted = CraftingStatusEnhancer.normalizePatternTimes(List.of(BigInteger.TEN, TIMES, BigInteger.ONE));
        assertEquals(List.of(TIMES, BigInteger.TEN), CraftingStatusEnhancer.displayedPatternTimes(sorted, 2));
        assertThrows(IllegalArgumentException.class, () -> CraftingStatusEnhancer.normalizePatternTimes(List.of(BigInteger.valueOf(-1))));
        var full = EnhancedCraftingStatusFormatting.formatAmount(TIMES, AmountFormat.FULL, AmountNumberStyle.SCIENTIFIC);
        assertEquals(TIMES.toString(), full.replaceAll("[^0-9]", ""));
        assertTrue(EnhancedCraftingStatusFormatting.formatAmount(TIMES, AmountFormat.SLOT, AmountNumberStyle.SCIENTIFIC)
                .toLowerCase(Locale.ROOT).contains("e20"));
        assertEquals("1T", EnhancedCraftingStatusFormatting.formatAmount(BigInteger.TEN.pow(12),
                AmountFormat.SLOT, AmountNumberStyle.ABBREVIATED));
        assertEquals("1Qi", EnhancedCraftingStatusFormatting.formatAmount(BigInteger.TEN.pow(18),
                AmountFormat.SLOT, AmountNumberStyle.ABBREVIATED));
    }

    @Test void exactCpuFrameProtocolKeepsAllComponentsAndRejectsNegativeAmounts() {
        var payload = new git.chexson.chexsonsaeutils.network.crafting.ExactCraftingCpuPayload(4, 7,
                new git.chexson.chexsonsaeutils.crafting.contract.CraftingCpuAmounts(
                        Map.of(KEY, TIMES), Map.of(KEY, TIMES.add(BigInteger.ONE)), Map.of(KEY, TIMES.multiply(BigInteger.TWO))));
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        try {
            var codec = git.chexson.chexsonsaeutils.network.crafting.ExactCraftingCpuPayload.STREAM_CODEC;
            codec.encode(buf, payload);
            assertEquals(payload, codec.decode(buf));
            assertEquals(0, buf.readableBytes());
            buf.clear(); buf.writeVarInt(4); buf.writeVarInt(7); buf.writeVarInt(1);
            AEKey.writeKey(buf, KEY); buf.writeByteArray(BigInteger.valueOf(-1).toByteArray());
            assertThrows(IllegalArgumentException.class, () -> codec.decode(buf));
        } finally { buf.release(); }
    }
}
