package git.chexson.chexsonsaeutils.crafting.graphplan;

import appeng.api.stacks.AEItemKey;
import git.chexson.chexsonsaeutils.crafting.graphplan.convert.Ae2PlanConverter;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.GraphPlanningState;
import git.chexson.chexsonsaeutils.crafting.graphplan.solve.SolveResult;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 实际规划出口必须携带精确字节，即使物品、发数和根订单都没有溢出。 */
final class PlanBytesExactTest {
    @Test void bytesAloneCanRequireAWidePlan() {
        var key = AEItemKey.of(Items.PAPER);
        var state = new GraphPlanningState(Map.of(), Map.of());
        state.byteAccount().add(key, Long.MAX_VALUE, 100);
        var graph = new CraftGraph(key, Map.of(), Map.of(), List.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Set.of(), Map.of(), false, null, 0, 0);
        var plan = Ae2PlanConverter.toPlan(SolveResult.success(state, 1, false), graph);
        var expected = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(800))
                .divide(BigInteger.valueOf(key.getAmountPerByte()));
        assertTrue(expected.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0);
        assertEquals(Long.MAX_VALUE, plan.bytes());
        assertEquals(expected, WideAmountPlanCarrier.resolveBytes(plan).exactValue());
        assertEquals(1, plan.finalOutput().amount());
    }
}
