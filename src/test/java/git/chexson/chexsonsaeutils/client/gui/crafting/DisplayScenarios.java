package git.chexson.chexsonsaeutils.client.gui.crafting;

import appeng.api.stacks.AEItemKey;
import com.sun.jna.Memory;
import git.chexson.chexsonsaeutils.crafting.status.*;
import git.chexson.chexsonsaeutils.crafting.status.PlanGraph;
import git.chexson.chexsonsaeutils.crafting.status.PlanGraphRoutes;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import net.minecraft.world.item.Items;
import static org.junit.jupiter.api.Assertions.*;

/** 合成预览与状态的行为场景；由类别测试统一调用。 */
public final class DisplayScenarios {
    private DisplayScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.client.gui.crafting",
                () -> PlanGraphClipboardCases.verify(temporaryRoot),
                () -> PlanGraphLinePixelsCases.verify(temporaryRoot),
                () -> PlanGraphPngCases.verify(temporaryRoot),
                () -> PlanGraphZoomCases.verify(temporaryRoot)
        );
    }

    /** PlanGraphClipboardCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanGraphClipboardCases {
        void writesExactImagePixelsAsBottomUpDibWithoutUsingDesktopClipboard() {
            var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, Color.RED.getRGB()); image.setRGB(1, 0, Color.GREEN.getRGB());
            image.setRGB(0, 1, Color.BLUE.getRGB()); image.setRGB(1, 1, Color.WHITE.getRGB());
            try (var memory = new Memory(40 + 16)) {
                PlanGraphClipboard.writeDib(image, memory);
                assertEquals(40, memory.getInt(0)); assertEquals(2, memory.getInt(4)); assertEquals(2, memory.getInt(8));
                assertEquals(1, memory.getShort(12)); assertEquals(32, memory.getShort(14)); assertEquals(0, memory.getInt(16));
                assertEquals(Color.BLUE.getRGB(), memory.getInt(40)); assertEquals(Color.WHITE.getRGB(), memory.getInt(44));
                assertEquals(Color.RED.getRGB(), memory.getInt(48)); assertEquals(Color.GREEN.getRGB(), memory.getInt(52));
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanGraphClipboardCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphClipboardCases.writesExactImagePixelsAsBottomUpDibWithoutUsingDesktopClipboard", () -> new PlanGraphClipboardCases().writesExactImagePixelsAsBottomUpDibWithoutUsingDesktopClipboard())
            );
        }
    }

    /** PlanGraphLinePixelsCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanGraphLinePixelsCases {
        private static PlanGraphRoutes.Route route(int x1, int y1, int x2, int y2) {
            return new PlanGraphRoutes.Route(List.of(new PlanGraphRoutes.Point(x1, y1), new PlanGraphRoutes.Point(x2, y2)));
        }
        void crossingOrdinaryLineCannotCoverSelectedRouteEvenIfGeneratedLast() {
            var node = new PlanGraph.Node(AEItemKey.of(Items.IRON_INGOT), false, BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO);
            var graph = new PlanGraph(List.of(node, node, node, node), List.of(
                    new PlanGraph.Edge(0, 1, BigInteger.ONE, false, false), new PlanGraph.Edge(2, 3, BigInteger.ONE, false, false)));
            var routes = List.of(route(2, 10, 18, 10), route(10, 2, 10, 18));
            var image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB); var g = image.createGraphics();
            try {
                for (int i : PlanGraphLinePixels.order(graph, 0)) {
                    g.setColor(i == 0 ? Color.YELLOW : Color.GRAY);
                    for (var r : PlanGraphLinePixels.rasterize(routes.get(i), 0, 0, 1))
                        g.fillRect(r.left(), r.top(), r.right() - r.left(), r.bottom() - r.top());
                }
            } finally { g.dispose(); }
            for (int x = 2; x <= 18; x++) assertEquals(Color.YELLOW.getRGB(), image.getRGB(x, 10));
            assertEquals(Color.GRAY.getRGB(), image.getRGB(10, 5));
        }
        void minimumZoomKeepsHorizontalVerticalAndSubpixelSegmentsVisible() {
            var path = new PlanGraphRoutes.Route(List.of(new PlanGraphRoutes.Point(0, 0), new PlanGraphRoutes.Point(1, 0),
                    new PlanGraphRoutes.Point(1, 400), new PlanGraphRoutes.Point(401, 400)));
            var image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB); var g = image.createGraphics();
            var rectangles = PlanGraphLinePixels.rasterize(path, 5.25, 5.25, 0.025);
            try {
                g.setColor(Color.WHITE);
                for (var r : rectangles) {
                    assertTrue(r.right() > r.left()); assertTrue(r.bottom() > r.top());
                    g.fillRect(r.left(), r.top(), r.right() - r.left(), r.bottom() - r.top());
                }
            } finally { g.dispose(); }
            for (int y = 5; y <= 15; y++) assertEquals(Color.WHITE.getRGB(), image.getRGB(5, y));
            for (int x = 5; x <= 15; x++) assertEquals(Color.WHITE.getRGB(), image.getRGB(x, 15));
        }
        void overviewKeepsLineButDoesNotStackArrowPixelsAroundEndpoint() {
            var path = route(0, 10, 100, 10);
            var image = new BufferedImage(60, 20, BufferedImage.TYPE_INT_RGB); var g = image.createGraphics();
            try {
                g.setColor(Color.WHITE);
                for (var r : PlanGraphLinePixels.rasterize(path, 0, 0, 0.5))
                    g.fillRect(r.left(), r.top(), r.right() - r.left(), r.bottom() - r.top());
            } finally { g.dispose(); }
            for (int x = 0; x <= 50; x++) assertEquals(Color.WHITE.getRGB(), image.getRGB(x, 5));
            assertEquals(Color.BLACK.getRGB(), image.getRGB(49, 4));
            assertEquals(Color.BLACK.getRGB(), image.getRGB(49, 6));
        }
        void verticalArrowPointsDownWithWingsAboveItsTip() {
            var image = new BufferedImage(24, 110, BufferedImage.TYPE_INT_RGB); var g = image.createGraphics();
            try {
                g.setColor(Color.WHITE);
                for (var r : PlanGraphLinePixels.rasterize(route(10, 0, 10, 100), 0, 0, 1, true))
                    g.fillRect(r.left(), r.top(), r.right() - r.left(), r.bottom() - r.top());
            } finally { g.dispose(); }
            assertEquals(Color.WHITE.getRGB(), image.getRGB(10, 100));
            assertEquals(Color.WHITE.getRGB(), image.getRGB(9, 99));
            assertEquals(Color.BLACK.getRGB(), image.getRGB(9, 101));
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanGraphLinePixelsCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphLinePixelsCases.crossingOrdinaryLineCannotCoverSelectedRouteEvenIfGeneratedLast", () -> new PlanGraphLinePixelsCases().crossingOrdinaryLineCannotCoverSelectedRouteEvenIfGeneratedLast()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphLinePixelsCases.minimumZoomKeepsHorizontalVerticalAndSubpixelSegmentsVisible", () -> new PlanGraphLinePixelsCases().minimumZoomKeepsHorizontalVerticalAndSubpixelSegmentsVisible()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphLinePixelsCases.overviewKeepsLineButDoesNotStackArrowPixelsAroundEndpoint", () -> new PlanGraphLinePixelsCases().overviewKeepsLineButDoesNotStackArrowPixelsAroundEndpoint()),
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphLinePixelsCases.verticalArrowPointsDownWithWingsAboveItsTip", () -> new PlanGraphLinePixelsCases().verticalArrowPointsDownWithWingsAboveItsTip())
            );
        }
    }

    /** PlanGraphPngCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanGraphPngCases {
        Path directory;
        void exportsOneFullResolutionPngWithNativeHighResolutionIcons() throws Exception {
            var node = new PlanGraph.Node(AEItemKey.of(Items.IRON_INGOT), false, BigInteger.TEN.pow(30), BigInteger.ONE, BigInteger.ZERO);
            var graph = new PlanGraph(List.of(node, node), List.of(new PlanGraph.Edge(0, 1, BigInteger.ONE, false, false)));
            var layout = new PlanGraphLayout(List.of(new PlanGraphLayout.Position(24, 24, 0, false),
                    new PlanGraphLayout.Position(24, 3100, 1, false)), 102, 3154);
            int resolution = 16 * PlanGraphPng.SCALE;
            var icon = new java.awt.image.BufferedImage(resolution, resolution, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < resolution; y++) for (int x = 0; x < resolution; x++)
                icon.setRGB(x, y, (x % 2 == 0 ? java.awt.Color.WHITE : java.awt.Color.RED).getRGB());
            try (var result = PlanGraphPng.export(graph, layout, directory, Map.of(node.key(), icon))) {
                assertTrue(Files.isRegularFile(result.path()));
                var png = ImageIO.read(result.path().toFile());
                assertEquals(layout.width() * PlanGraphPng.SCALE, png.getWidth());
                assertEquals(layout.height() * PlanGraphPng.SCALE, png.getHeight());
                assertEquals(java.awt.Color.WHITE.getRGB(), png.getRGB(31 * PlanGraphPng.SCALE, 31 * PlanGraphPng.SCALE));
                assertEquals(java.awt.Color.RED.getRGB(), png.getRGB(31 * PlanGraphPng.SCALE + 1, 31 * PlanGraphPng.SCALE));
                assertEquals(java.awt.Color.WHITE.getRGB(), png.getRGB(31 * PlanGraphPng.SCALE, 3107 * PlanGraphPng.SCALE));
                assertEquals(png.getRGB(0, 0), result.image().getRGB(0, 0));
            }
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanGraphPngCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphPngCases.exportsOneFullResolutionPngWithNativeHighResolutionIcons", () -> {
                            var scenario = new PlanGraphPngCases();
                            scenario.directory = java.nio.file.Files.createTempDirectory(temporaryRoot, "PlanGraphPngCases-");
                            scenario.exportsOneFullResolutionPngWithNativeHighResolutionIcons();
                        })
            );
        }
    }

    /** PlanGraphZoomCases 的独立场景夹具，按类别统一执行。 */
    public static final class PlanGraphZoomCases {
        void detailsFadeContinuouslyInsteadOfSwitchingAtOldThreshold() {
            assertEquals(0, PlanGraphZoom.routes(0.1)); assertEquals(1, PlanGraphZoom.routes(1));
            assertEquals(0, PlanGraphZoom.icons(0.1)); assertEquals(1, PlanGraphZoom.icons(1.5));
            assertEquals(0, PlanGraphZoom.amounts(0.5)); assertEquals(1, PlanGraphZoom.amounts(1.5));
            double previous = 0;
            for (double zoom = 0.01; zoom < 1.5; zoom += 0.001) {
                double value = PlanGraphZoom.routes(zoom);
                assertTrue(value >= previous); assertTrue(value - previous < 0.01); previous = value;
            }
            assertTrue(Math.abs(PlanGraphZoom.routes(0.651) - PlanGraphZoom.routes(0.649)) < 0.01);
            assertEquals(0, PlanGraphZoom.color(0xffffffff, 0) >>> 24);
            assertEquals(255, PlanGraphZoom.color(0xffffffff, 1) >>> 24);
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("PlanGraphZoomCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("PlanGraphZoomCases.detailsFadeContinuouslyInsteadOfSwitchingAtOldThreshold", () -> new PlanGraphZoomCases().detailsFadeContinuouslyInsteadOfSwitchingAtOldThreshold())
            );
        }
    }
}
