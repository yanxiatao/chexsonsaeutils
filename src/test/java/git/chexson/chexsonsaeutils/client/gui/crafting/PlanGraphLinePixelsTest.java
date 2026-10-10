package git.chexson.chexsonsaeutils.client.gui.crafting;

import appeng.api.stacks.AEItemKey;
import net.minecraft.world.item.Items;
import git.chexson.chexsonsaeutils.crafting.status.PlanGraph;
import git.chexson.chexsonsaeutils.crafting.status.PlanGraphRoutes;
import org.junit.jupiter.api.Test;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.math.BigInteger;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class PlanGraphLinePixelsTest {
    private static PlanGraphRoutes.Route route(int x1, int y1, int x2, int y2) {
        return new PlanGraphRoutes.Route(List.of(new PlanGraphRoutes.Point(x1, y1), new PlanGraphRoutes.Point(x2, y2)));
    }
    @Test void crossingOrdinaryLineCannotCoverSelectedRouteEvenIfGeneratedLast() {
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
    @Test void minimumZoomKeepsHorizontalVerticalAndSubpixelSegmentsVisible() {
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
    @Test void overviewKeepsLineButDoesNotStackArrowPixelsAroundEndpoint() {
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
    @Test void verticalArrowPointsDownWithWingsAboveItsTip() {
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

}
