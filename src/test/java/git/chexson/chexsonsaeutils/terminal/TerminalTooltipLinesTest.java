package git.chexson.chexsonsaeutils.terminal;

import git.chexson.chexsonsaeutils.terminal.display.TerminalAmountFormatterImpl;
import git.chexson.chexsonsaeutils.terminal.display.TerminalTooltipLinesImpl;
import git.chexson.chexsonsaeutils.terminal.storage.StorageAmount;
import appeng.api.stacks.AEFluidKey;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

final class TerminalTooltipLinesTest {
    @Test void completeHugeAmountRemainsReadableAcrossManyLinesWithoutScientificNotation() {
        var formatter = new TerminalAmountFormatterImpl();
        var value = BigInteger.TEN.pow(4096).add(BigInteger.valueOf(123));
        var full = "存储数量: " + formatter.full(StorageAmount.finite(value));
        var lines = new TerminalTooltipLinesImpl().wrap(full, 120, s -> s.length() * 6);
        assertTrue(lines.size() > 100);
        assertEquals(full, String.join("", lines));
        assertTrue(lines.stream().allMatch(s -> s.length() * 6 <= 120));
        assertEquals(value.toString(), String.join("", lines).substring("存储数量: ".length()).replace(",", ""));
        assertThrows(UnsupportedOperationException.class, () -> lines.clear());
    }
    @Test void fluidFractionsUnitsAndUnicodeRemainIntactInWrappedTooltip() {
        var key = AEFluidKey.of(Fluids.WATER);
        var value = BigInteger.TEN.pow(100).add(BigInteger.ONE);
        var text = new TerminalAmountFormatterImpl().full(key, StorageAmount.finite(value)) + " 🧪";
        var lines = new TerminalTooltipLinesImpl().wrap(text, 17, s -> s.codePointCount(0, s.length()));
        assertEquals(text, String.join("", lines));
        assertTrue(text.contains(".001"));
        assertTrue(lines.stream().allMatch(s -> !Character.isHighSurrogate(s.charAt(s.length() - 1))));
    }
}
