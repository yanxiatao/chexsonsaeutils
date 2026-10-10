package git.chexson.chexsonsaeutils.terminal;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.cell.InfinityCellStore;
import git.chexson.chexsonsaeutils.terminal.storage.StorageAmount;
import git.chexson.chexsonsaeutils.terminal.sync.*;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 真实注册的 Applied Mekanistics 化学键验证，不使用反射或伪造类型注册表。 */
final class TerminalChemicalKeySdkTest {
    @Test void chemicalKeyPreservesLiveSnapshotFileAndTerminalFrame() throws Exception {
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
}
