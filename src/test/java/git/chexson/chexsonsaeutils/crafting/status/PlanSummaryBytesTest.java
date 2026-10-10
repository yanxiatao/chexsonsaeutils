package git.chexson.chexsonsaeutils.crafting.status;

import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.api.stacks.*;
import appeng.crafting.CraftingPlan;
import git.chexson.chexsonsaeutils.bigint.*;
import git.chexson.chexsonsaeutils.crafting.recursive.RecursiveCraftingPlan;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 原始递归计划到摘要、传输与标题读数的行为验证，不依赖菜单旁路字段存在。 */
final class PlanSummaryBytesTest {
    @Test void recursivePlanBytesReachTheTitleInTheSameSummaryPacket() {
        var exact = BigInteger.TEN.pow(40).add(BigInteger.valueOf(123));
        var base = new CraftingPlan(new GenericStack(AEItemKey.of(Items.PAPER), 1), Long.MAX_VALUE,
                false, false, new KeyCounter(), new KeyCounter(), new KeyCounter(), Map.of());
        var plan = new RecursiveCraftingPlan(base, new KeyCounter(), new KeyCounter(), 1, Map.of(),
                Map.of(), WideLedgerSnapshot.empty(), WideCraftSnapshot.empty(), Set.of(), Map.of(), null, PlanAmount.of(exact));
        var server = new Summary();
        PlanSummaryBytes.attach(server, plan);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PlanSummaryBytes.write(buffer, server.chexsonsaeutils$exactBytes());
            var client = new Summary();
            client.chexsonsaeutils$setExactBytes(PlanSummaryBytes.read(buffer));
            assertEquals(exact, PlanSummaryBytes.resolve(client, ""), "独立菜单字段缺失时仍显示原计划真值");
            assertEquals(exact, PlanSummaryBytes.resolve(client, Long.toString(Long.MAX_VALUE)), "陈旧 long 投影不能覆盖当前摘要");
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }

    @Test void originalFiniteSummaryKeepsItsLongBytes() {
        var original = new CraftingPlanSummary(1234, false, List.of());
        assertEquals(BigInteger.valueOf(1234), PlanSummaryBytes.resolve(original, ""));
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            PlanSummaryBytes.write(buffer, null);
            assertNull(PlanSummaryBytes.read(buffer));
        } finally { buffer.release(); }
    }

    private static final class Summary extends CraftingPlanSummary implements EnhancedCraftingPlanSummary {
        private PlanGraph graph = PlanGraph.EMPTY;
        @Override public PlanGraph chexsonsaeutils$graph() { return graph; }
        @Override public void chexsonsaeutils$setGraph(PlanGraph value) { graph = value; }
        private BigInteger bytes;
        private long millis;
        private String solver = "graph";
        Summary() { super(Long.MAX_VALUE, false, List.of()); }
        @Override public BigInteger chexsonsaeutils$exactBytes() { return bytes; }
        @Override public void chexsonsaeutils$setExactBytes(BigInteger value) { bytes = value; }
        @Override public long chexsonsaeutils$planningMillis() { return millis; }
        @Override public String chexsonsaeutils$solver() { return solver; }
        @Override public void chexsonsaeutils$setProvenance(long millis, String solver) { this.millis = millis; this.solver = solver; }
    }
}
