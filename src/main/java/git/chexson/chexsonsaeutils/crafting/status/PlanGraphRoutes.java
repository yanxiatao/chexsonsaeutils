package git.chexson.chexsonsaeutils.crafting.status;

import java.util.*;

/** 明确的生产箭头路径；按节点端口分流，循环返还走节点外侧。 */
public final class PlanGraphRoutes {
    private PlanGraphRoutes() {}
    public record Point(int x, int y) {}
    public record Route(List<Point> points, int minX, int minY, int maxX, int maxY) {
        public Route(List<Point> points) { this(points,
                points.stream().mapToInt(Point::x).min().orElseThrow(), points.stream().mapToInt(Point::y).min().orElseThrow(),
                points.stream().mapToInt(Point::x).max().orElseThrow(), points.stream().mapToInt(Point::y).max().orElseThrow()); }
        public Route { points = List.copyOf(points); }
        /** 视野裁剪只影响绘制，不删去任何生产关系。 */
        public boolean visible(double left, double top, double right, double bottom) {
            return maxX >= left && minX <= right && maxY >= top && minY <= bottom;
        }
    }
    /** 概览合并同层间的走线通道，连接节点中心。 */
    public static List<Route> createOverview(PlanGraph graph, PlanGraphLayout layout) {
        var result = new ArrayList<Route>(); int half = PlanGraphLayout.NODE_SIZE / 2;
        for (var edge : graph.edges()) {
            var a = layout.positions().get(edge.from()); var b = layout.positions().get(edge.to());
            int x1 = a.x() + half, y1 = a.y() + half, x2 = b.x() + half, y2 = b.y() + half;
            int track = y1 == y2 ? y1 + PlanGraphLayout.NODE_SIZE : (y1 + y2) / 2;
            result.add(new Route(List.of(new Point(x1, y1), new Point(x1, track), new Point(x2, track), new Point(x2, y2))));
        }
        return List.copyOf(result);
    }
    public static List<Route> create(PlanGraph graph, PlanGraphLayout layout) {
        int n = graph.nodes().size(), size = PlanGraphLayout.NODE_SIZE;
        int[] outgoing = new int[n], incoming = new int[n], outPort = new int[n], inPort = new int[n];
        for (var e : graph.edges()) { outgoing[e.from()]++; incoming[e.to()]++; }
        var result = new ArrayList<Route>();
        for (int i = 0; i < graph.edges().size(); i++) {
            var e = graph.edges().get(i); var a = layout.positions().get(e.from()); var b = layout.positions().get(e.to());
            int x1 = a.x() + 4 + (++outPort[e.from()] * (size - 8)) / (outgoing[e.from()] + 1);
            int x2 = b.x() + 4 + (++inPort[e.to()] * (size - 8)) / (incoming[e.to()] + 1);
            int direction = b.y() >= a.y() ? 1 : -1;
            int y1 = direction > 0 ? a.y() + size : a.y(), y2 = direction > 0 ? b.y() : b.y() + size;
            if (a.y() == b.y()) {
                y2 = b.y() + size; int track = y1 + 20 + (i % 7) * 4;
                result.add(new Route(List.of(new Point(x1, y1), new Point(x1, track), new Point(x2, track), new Point(x2, y2))));
            } else if (Math.abs(b.y() - a.y()) > 110) {
                int top = y1 + direction * 20, bottom = y2 - direction * 12, trackX = a.x() + size + 12 + (i % 4) * 3;
                result.add(new Route(List.of(new Point(x1, y1), new Point(x1, top), new Point(trackX, top),
                        new Point(trackX, bottom), new Point(x2, bottom), new Point(x2, y2))));
            } else {
                int middle = (y1 + y2) / 2 + (i % 5 - 2) * 4;
                result.add(new Route(List.of(new Point(x1, y1), new Point(x1, middle), new Point(x2, middle), new Point(x2, y2))));
            }
        }
        return List.copyOf(result);
    }
}
