package git.chexson.chexsonsaeutils.terminal;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.cell.InfinityCellStore;
import git.chexson.chexsonsaeutils.terminal.storage.StorageAmount;
import git.chexson.chexsonsaeutils.terminal.sync.*;
import io.netty.buffer.Unpooled;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 SDK 的同类行为统一验证，按原有集成环境显式运行。 */
final class ChemicalTerminalCategoryTest {
    @Test
    void verifyLoadedIntegration() {
        org.junit.jupiter.api.Assertions.assertAll("ChemicalTerminalCategoryTest",
                TerminalChemicalKeySdkCases::verify,
                () -> {
                    // 玩家存档核对只在显式指定世界时执行。
                    if (System.getProperty("terminal.worldRoot") != null) {
                        TerminalSavedWorldSdkCases.verify();
                    }
                }
        );
    }

    private static final class TerminalChemicalKeySdkCases {
        void chemicalKeyPreservesLiveSnapshotFileAndTerminalFrame() throws Exception {
            assertTrue(ModList.get().isLoaded("appmek")); assertTrue(ModList.get().isLoaded("mekanism"));
            var chemical = MekanismAPI.CHEMICAL_REGISTRY.get(ResourceLocation.parse("mekanism:hydrogen"));
            assertNotNull(chemical);
            var key = MekanismKey.of(new ChemicalStack(chemical, 1));
            var registry = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var id = UUID.randomUUID(); var root = Files.createTempDirectory("terminal-chemical-codec");
            var store = new InfinityCellStore();
            store.cell(id).insert(key, Long.MAX_VALUE, Actionable.MODULATE);
            store.cell(id).insert(key, Long.MAX_VALUE, Actionable.MODULATE);
            var amount = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO);
            store.save(root, registry); InfinityCellStore.awaitPendingWrites();
            var reloaded = new InfinityCellStore(); reloaded.load(root, registry);
            assertEquals(amount, reloaded.cell(id).readExactSnapshot(id).amount(key));
            // 旧版 type+tag 也走相同通用注册表，不能仅依赖新 generic 字段。
            var file = root.resolve("data/chexsonsaeutils/cells").resolve(id + ".nbt");
            var tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            var encoded = tag.getList("keys", Tag.TAG_COMPOUND).getCompound(0);
            encoded.remove("generic"); encoded.putString("type", key.getType().getId().toString());
            encoded.put("tag", key.toTag(registry)); NbtIo.writeCompressed(tag, file);
            var legacy = new InfinityCellStore(); legacy.load(root, registry);
            assertEquals(amount, legacy.cell(id).readExactSnapshot(id).amount(key));
            var frame = new TerminalFrame(1, 0, true, true, true,
                    List.of(new TerminalEntry(0, key, StorageAmount.finite(amount), false, null)), List.of());
            var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registry);
            try {
                TerminalFrameCodec.write(buf, frame);
                var decoded = TerminalFrameCodec.read(buf);
                assertEquals(frame, decoded); assertInstanceOf(MekanismKey.class, decoded.changes().getFirst().key());
            } finally { buf.release(); }
        }

        static void verify() {
            org.junit.jupiter.api.Assertions.assertAll("TerminalChemicalKeySdkCases",
                    () -> org.junit.jupiter.api.Assertions.assertAll("chemicalKeyPreservesLiveSnapshotFileAndTerminalFrame", () -> new TerminalChemicalKeySdkCases().chemicalKeyPreservesLiveSnapshotFileAndTerminalFrame())
            );
        }
    }

    private static final class TerminalSavedWorldSdkCases {
        void existingFilesDecodeToExactSnapshotsWithoutDroppingPositiveAmounts() throws Exception {
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

        static void verify() {
            org.junit.jupiter.api.Assertions.assertAll("TerminalSavedWorldSdkCases",
                    () -> org.junit.jupiter.api.Assertions.assertAll("existingFilesDecodeToExactSnapshotsWithoutDroppingPositiveAmounts", () -> new TerminalSavedWorldSdkCases().existingFilesDecodeToExactSnapshotsWithoutDroppingPositiveAmounts())
            );
        }
    }
}
