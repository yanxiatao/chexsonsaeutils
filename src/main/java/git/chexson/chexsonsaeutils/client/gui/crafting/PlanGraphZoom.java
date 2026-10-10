package git.chexson.chexsonsaeutils.client.gui.crafting;

/** 缩放的连续细节权重，概览、边框、图标和数量分阶段渐变，不在单一阈值切换整幅图。 */
final class PlanGraphZoom {
    private PlanGraphZoom() {}
    static double fade(double zoom, double start, double end) {
        double t = Math.max(0, Math.min(1, (zoom - start) / (end - start)));
        return t * t * (3 - 2 * t);
    }
    static double routes(double zoom) { return fade(zoom, 0.35, 0.85); }
    static double icons(double zoom) { return fade(zoom, 0.7, 1.0); }
    static double amounts(double zoom) { return fade(zoom, 0.7, 1.05); }
    static int color(int color, double opacity) {
        return (Math.max(0, Math.min(255, (int) Math.round(255 * opacity))) << 24) | (color & 0xffffff);
    }
}
