package git.chexson.chexsonsaeutils.crafting.graphplan.solve;

import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.CraftGraph;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphPattern;
import git.chexson.chexsonsaeutils.crafting.graphplan.graph.RingMacro;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个键的 producer 路线排序。普通样板保持 crafting service 的注册顺序，
 * 环宏保持图编译顺序；颜色不参与路线选择或排序。
 */
public final class RouteOrdering {

    private RouteOrdering() {
    }

    public sealed interface Route {
        /**
         * {@code slotIndex} 是该样板路线在本键路线集合内的稠密下标，站点用它
         * 直接寻址懒建的输入槽数组（此前每次 {@code fire} 都要线性扫路线表
         * 反查下标，42 万站点规模下是纯浪费）。
         */
        record Pattern(GraphPattern pattern, int slotIndex) implements Route {
        }

        record Ring(RingMacro macro) implements Route {
        }
    }

    /**
     * 一个键的完整路线视图，可由所有同键站点复用。
     * {@code patternFirst} 用于非模拟子站点：先尝试普通样板，再尝试环宏。
     */
    public record RouteBundle(List<Route> routes, int patternRouteCount, List<Route> patternFirst) {

        static final RouteBundle EMPTY = new RouteBundle(List.of(), 0, List.of());

        private static RouteBundle of(List<Route> routes) {
            int patternRoutes = 0;
            for (var route : routes) {
                if (route instanceof Route.Pattern) {
                    patternRoutes++;
                }
            }
            return new RouteBundle(routes, patternRoutes,
                    routes.size() > 1 ? patternRoutesBeforeRings(routes) : routes);
        }

        private static List<Route> patternRoutesBeforeRings(List<Route> routes) {
            var ordered = new ArrayList<Route>(routes.size());
            for (var route : routes) {
                if (route instanceof Route.Pattern) {
                    ordered.add(route);
                }
            }
            for (var route : routes) {
                if (route instanceof Route.Ring) {
                    ordered.add(route);
                }
            }
            return List.copyOf(ordered);
        }
    }

    /**
     * 根专用宏始终最先尝试；其余宏与普通样板分别保持各自的输入顺序。
     */
    public static RouteBundle assembleRoutes(
            List<GraphPattern> patterns,
            List<RingMacro> macros,
            @Nullable RingMacro rootMacro) {
        if (patterns.isEmpty() && macros.isEmpty() && rootMacro == null) {
            return RouteBundle.EMPTY;
        }
        List<Route> routes = new ArrayList<>(patterns.size() + macros.size() + 1);
        if (rootMacro != null) {
            routes.add(new Route.Ring(rootMacro));
        }
        for (var macro : macros) {
            routes.add(new Route.Ring(macro));
        }
        int slotIndex = 0;
        for (var pattern : patterns) {
            routes.add(new Route.Pattern(pattern, slotIndex++));
        }
        return RouteBundle.of(List.copyOf(routes));
    }

    /**
     * 从图上取一个键的路线集合：{@code blocked} 直接返回空路线，
     * 根专用宏只在根站点参与，其余宏与普通样板各自保持注册顺序。
     */
    public static RouteBundle routesFor(CraftGraph graph, AEKey key, boolean root, boolean blocked) {
        if (blocked) {
            return assembleRoutes(List.of(), List.of(), null);
        }
        List<GraphPattern> patterns = graph.producersByOutput().getOrDefault(key, List.of());
        List<RingMacro> macros = new ArrayList<>();
        RingMacro rootMacro = null;
        for (RingMacro macro : graph.ringMacrosByEntry().getOrDefault(key, List.of())) {
            if (macro.rootOnly()) {
                if (root && rootMacro == null) {
                    rootMacro = macro;
                }
            } else {
                macros.add(macro);
            }
        }
        return assembleRoutes(patterns, macros, rootMacro);
    }
}
