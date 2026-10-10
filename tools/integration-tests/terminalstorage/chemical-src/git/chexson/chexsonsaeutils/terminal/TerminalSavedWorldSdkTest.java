package git.chexson.chexsonsaeutils.terminal;

import git.chexson.chexsonsaeutils.cell.InfinityCellStore;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 显式指定世界后只读核对现有单元文件，不保存或改写玩家存档。 */
final class TerminalSavedWorldSdkTest {
    @Test void existingFilesDecodeToExactSnapshotsWithoutDroppingPositiveAmounts() throws Exception {
        var root = Path.of(java.util.Objects.requireNonNull(System.getProperty("terminal.worldRoot"),
                "必须通过 -PterminalWorldRoot 指定验收世界"));
        var store = new InfinityCellStore(); store.load(root);
        try (var files = Files.list(root.resolve("data/chexsonsaeutils/cells"))) {
            var cells = files.filter(p -> p.getFileName().toString().endsWith(".nbt")).toList();
            assertFalse(cells.isEmpty());
            for (var file : cells) {
                var tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                var entries = tag.getList("entries", Tag.TAG_COMPOUND);
                var expected = BigInteger.ZERO;
                for (int i = 0; i < entries.size(); i++) {
                    var entry = entries.getCompound(i);
                    var amount = entry.contains("bigAmount", Tag.TAG_BYTE_ARRAY)
                            ? new BigInteger(entry.getByteArray("bigAmount")) : BigInteger.valueOf(entry.getLong("amount"));
                    if (amount.signum() > 0) expected = expected.add(amount);
                }
                var id = UUID.fromString(file.getFileName().toString().replace(".nbt", ""));
                var snapshot = store.cell(id).readExactSnapshot(id);
                assertEquals(expected, snapshot.amounts().values().stream().reduce(BigInteger.ZERO, BigInteger::add), file.toString());
                assertEquals(expected, store.cell(id).totalAmount());
            }
            System.out.println("只读核对单元文件：" + cells.size());
        }
    }
}
