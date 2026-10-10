package git.chexson.chexsonsaeutils.crafting.graphplan.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import git.chexson.chexsonsaeutils.crafting.recursive.RingId;
import net.minecraft.world.item.Items;

/**
 * 直接构造容量遍历边图，锁定 B1 分析的三条判定：宏边成环、无环快路径、
 * RETURNED 边不参与成环（与 CapacityUpperBound 的跳过口径一致）。
 */
final class ActiveRouteSelectionTest {

    private static final AEKey T = AEItemKey.of(Items.IRON_INGOT);
    private static final AEKey N = AEItemKey.of(Items.GOLD_INGOT);
    private static final AEKey S = AEItemKey.of(Items.REDSTONE);

    @Test
    void macroEdgeCycleIsDetectedWithParticipant() {
        // Q produces N and consumes T; the macro edge T→N closes the cycle.
        var consumer = new GraphPattern(null, N, 1, List.of(),
                List.of(normalInput(T, GraphInput.Mode.NORMAL, null)));
        var macro = new RingMacro(RingId.of(T, List.of()),
                counter(N, 1), new KeyCounter(), new KeyCounter(), Map.of(), true);
        var graph = new CraftGraph(T,
                Map.of(N, List.of(consumer)), Map.of(), List.of(), Map.of(T, List.of(macro)),
                Map.of(), Map.of(), Map.of(), Set.of(), Map.of(), false, null, 0, 0);

        var selection = ActiveRouteSelection.analyze(graph);

        assertFalse(selection.acyclic());
        assertEquals(Set.of(T, N), selection.cyclicKeys());
        assertEquals(Set.of(consumer), selection.cycleParticipants());
    }

    @Test
    void acyclicGraphHasNoCycles() {
        var producer = new GraphPattern(null, T, 1, List.of(),
                List.of(normalInput(S, GraphInput.Mode.NORMAL, null)));
        var graph = new CraftGraph(T,
                Map.of(T, List.of(producer)), Map.of(), List.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Set.of(), Map.of(), false, null, 0, 0);

        var selection = ActiveRouteSelection.analyze(graph);

        assertTrue(selection.acyclic());
        assertTrue(selection.cyclicKeys().isEmpty());
        assertTrue(selection.cycleParticipants().isEmpty());
    }

    @Test
    void returnedModeInputDoesNotCloseCycle() {
        // Same shape as the macro cycle, but the producer's input is a 1:1
        // container return — the capacity walk never requests it, so the only
        // remaining edge is the macro's T→N and the graph is acyclic.
        var containerReturn = new GraphPattern(null, N, 1, List.of(),
                List.of(normalInput(T, GraphInput.Mode.RETURNED, T)));
        var macro = new RingMacro(RingId.of(T, List.of()),
                counter(N, 1), new KeyCounter(), new KeyCounter(), Map.of(), true);
        var graph = new CraftGraph(T,
                Map.of(N, List.of(containerReturn)), Map.of(), List.of(), Map.of(T, List.of(macro)),
                Map.of(), Map.of(), Map.of(), Set.of(), Map.of(), false, null, 0, 0);

        var selection = ActiveRouteSelection.analyze(graph);

        assertTrue(selection.acyclic());
        assertTrue(selection.cyclicKeys().isEmpty());
        assertTrue(selection.cycleParticipants().isEmpty());
    }

    private static GraphInput normalInput(AEKey key, GraphInput.Mode mode, AEKey remainder) {
        return new GraphInput(key, 1, 1, mode, remainder, null, null, false);
    }

    private static KeyCounter counter(AEKey key, long amount) {
        var counter = new KeyCounter();
        counter.add(key, amount);
        return counter;
    }
}
