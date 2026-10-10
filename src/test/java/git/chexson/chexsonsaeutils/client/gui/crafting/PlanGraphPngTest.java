package git.chexson.chexsonsaeutils.client.gui.crafting;

import appeng.api.stacks.AEItemKey;
import git.chexson.chexsonsaeutils.crafting.status.*;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.math.BigInteger;
import java.util.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

final class PlanGraphPngTest {
    @TempDir Path directory;
    @Test void exportsOneFullResolutionPngWithNativeHighResolutionIcons() throws Exception {
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
}
