package git.chexson.chexsonsaeutils.client.gui.crafting;

import com.sun.jna.Memory;
import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PlanGraphClipboardTest {
    @Test void writesExactImagePixelsAsBottomUpDibWithoutUsingDesktopClipboard() {
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
}
