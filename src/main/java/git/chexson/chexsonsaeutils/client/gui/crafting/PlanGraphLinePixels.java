package git.chexson.chexsonsaeutils.client.gui.crafting;

import git.chexson.chexsonsaeutils.crafting.status.PlanGraph;
import git.chexson.chexsonsaeutils.crafting.status.PlanGraphRoutes;
import git.chexson.chexsonsaeutils.crafting.status.PlanGraphHighlight;
import java.util.ArrayList;
import java.util.List;

/** 先绘制普通关系再绘制高亮关系；线段在屏幕空间取整，缩小时仍至少占一个像素。 */
final class PlanGraphLinePixels {
    private PlanGraphLinePixels() {}
    record Rect(int left, int top, int right, int bottom) {}
    static List<Integer> order(PlanGraph graph, int selected) {
        return order(graph, PlanGraphHighlight.create(graph, selected, false));
    }
    static List<Integer> order(PlanGraph graph, PlanGraphHighlight selection) {
        var result = new ArrayList<Integer>(graph.edges().size());
        for (boolean highlight : new boolean[]{false, true}) for (int i = 0; i < graph.edges().size(); i++) {
            if (selection.edges().contains(i) == highlight) result.add(i);
        }
        return result;
    }
    static List<Rect> rasterize(PlanGraphRoutes.Route route, double x, double y, double zoom) {
        return rasterize(route, x, y, zoom, zoom >= 0.65);
    }
    static List<Rect> rasterize(PlanGraphRoutes.Route route, double x, double y, double zoom, boolean arrows) {
        if (!(zoom > 0)) throw new IllegalArgumentException("缩放比例必须为正数");
        var result = new ArrayList<Rect>();
        var points = route.points();
        for (int i = 1; i < points.size(); i++) {
            var a = points.get(i - 1); var b = points.get(i);
            int ax = (int) Math.round(x + a.x() * zoom), ay = (int) Math.round(y + a.y() * zoom);
            int bx = (int) Math.round(x + b.x() * zoom), by = (int) Math.round(y + b.y() * zoom);
            result.add(new Rect(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx) + 1, Math.max(ay, by) + 1));
        }
        // 概览只保留线段，避免最小尺寸箭头在密集端口处堆积成亮块。
        if (!arrows) return result;
        var end = points.getLast(); var before = points.get(points.size() - 2);
        int ex = (int) Math.round(x + end.x() * zoom), ey = (int) Math.round(y + end.y() * zoom);
        boolean vertical = end.x() == before.x();
        int direction = vertical ? (end.y() >= before.y() ? 1 : -1) : (end.x() >= before.x() ? 1 : -1);
        int size = Math.max(2, (int) Math.round(4 * zoom));
        for (int k = 0; k < size; k++) result.add(vertical
                ? new Rect(ex - k, ey - direction * k, ex + k + 1, ey - direction * k + 1)
                : new Rect(ex - direction * k, ey - k, ex - direction * k + 1, ey + k + 1));
        return result;
    }
}
