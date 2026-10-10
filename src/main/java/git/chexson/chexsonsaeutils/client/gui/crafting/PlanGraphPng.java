package git.chexson.chexsonsaeutils.client.gui.crafting;

import git.chexson.chexsonsaeutils.crafting.status.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import javax.imageio.ImageIO;

/** 固定三倍分辨率的完整无损 PNG；不附说明、分块、目录或文本清单。 */
final class PlanGraphPng {
    static final int SCALE = 3;
    private PlanGraphPng() {}
    /** 已保存图片与同一份清晰像素；剪贴板写入完成后释放图像引用。 */
    record Export(Path path, BufferedImage image) implements AutoCloseable {
        @Override public void close() { image.flush(); }
    }
    static Export export(PlanGraph graph, PlanGraphLayout layout, Path game,
            Map<appeng.api.stacks.AEKey, BufferedImage> icons) throws IOException {
        long width = (long) layout.width() * SCALE, height = (long) layout.height() * SCALE;
        long pixels = width * height;
        var runtime = Runtime.getRuntime(); long available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
        if (width > Integer.MAX_VALUE || height > Integer.MAX_VALUE || pixels > Integer.MAX_VALUE - 8L
                || pixels * 4 > available * 0.8)
            throw new IOException("当前 JVM 内存不足以完整高清导出 " + width + " × " + height + " 图片；请增加客户端内存");
        var image = new BufferedImage((int) width, (int) height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try {
            g.setColor(new Color(0x111820)); g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.scale(SCALE, SCALE);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            var routes = PlanGraphRoutes.create(graph, layout);
            for (int i = 0; i < graph.edges().size(); i++) {
                var e = graph.edges().get(i); var points = routes.get(i).points();
                g.setColor(new Color(e.remainder() ? 0x69bca2 : e.alternative() ? 0x9875bd : 0x526b82));
                for (int j = 1; j < points.size(); j++) { var a = points.get(j - 1); var b = points.get(j); g.drawLine(a.x(), a.y(), b.x(), b.y()); }
                var end = points.getLast(); var before = points.get(points.size() - 2); int back = end.y() + (end.y() >= before.y() ? -4 : 4);
                g.fillPolygon(new int[]{end.x(), end.x() - 3, end.x() + 3}, new int[]{end.y(), back, back}, 3);
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            for (int i = 0; i < graph.nodes().size(); i++) {
                var p = layout.positions().get(i); var n = graph.nodes().get(i);
                g.setColor(new Color(n.missing().signum() > 0 ? 0xed6565 : layout.positions().get(i).cycle() ? 0xbd8cfa : 0x687d96));
                g.fillRect(p.x(), p.y(), 30, 30); g.setColor(new Color(0x243040)); g.fillRect(p.x() + 1, p.y() + 1, 28, 28);
                var icon = icons.get(n.key()); if (icon == null) throw new IOException("合成图节点图标缺失：" + n.key());
                g.drawImage(icon, p.x() + 7, p.y() + 7, 16, 16, null);
                var quantity = n.amount().signum() > 0 ? n.amount() : n.stored().add(n.missing());
                String number = quantity.toString(); if (number.length() > 6) number = number.charAt(0) + "." + number.substring(1, 3) + "e" + (number.length() - 1);
                int x = p.x() + (30 - g.getFontMetrics().stringWidth(number)) / 2;
                g.setColor(new Color(0x111820)); g.fillRect(x - 2, p.y() + 31, g.getFontMetrics().stringWidth(number) + 4, 13);
                g.setColor(Color.WHITE); g.drawString(number, x, p.y() + 41);
            }
        } catch (IOException | RuntimeException error) { image.flush(); throw error; }
        finally { g.dispose(); }
        Path screenshots = game.resolve("screenshots"); Files.createDirectories(screenshots);
        Path path = Files.createTempFile(screenshots, "crafting-graph-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-", ".png");
        try {
            if (!ImageIO.write(image, "png", path.toFile())) throw new IOException("PNG 编码器不可用");
        } catch (IOException | RuntimeException error) { image.flush(); Files.deleteIfExists(path); throw error; }
        return new Export(path, image);
    }
}
