package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;
import git.chexson.chexsonsaeutils.bigint.WidePlanLedgerCodec;
import git.chexson.chexsonsaeutils.bigint.WideAmountKind;

/**
 * M1 序列化/反序列化往返（含 AEKey 还原）离线用例。
 *
 * <p>注册表用 {@code RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)}
 * （NeoForge unitTest 源集已 bootstrap 原版注册表），键编解码走 AE2 自己的
 * {@code AEKey.writeKey/readKey}——与确认表 entry 同步同一 codec，保证客户端还原的
 * {@code AEKey} 与那一行 {@code entry.getWhat()} 按值相等。
 */
class WidePlanLedgerCodecTest {

    private static final RegistryAccess REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private static final BigInteger OVERFLOW =
            BigInteger.valueOf(16).multiply(BigInteger.TEN.pow(18)); // 1.6e19 > Long.MAX

    private static AEItemKey key(net.minecraft.world.item.Item item) {
        return AEItemKey.of(new ItemStack(item));
    }

    @Test
    void overflowKeysRoundTripWithExactAmountsAndReconstructedKeys() {
        WideKeyCounter missing = new WideKeyCounter();
        missing.add(key(Items.LAVA_BUCKET), OVERFLOW);
        WideKeyCounter emitted = new WideKeyCounter();
        emitted.add(key(Items.PAPER), OVERFLOW.add(BigInteger.ONE));

        WideLedgerSnapshot snapshot = WideLedgerSnapshot.capture(missing, null, emitted);
        String encoded = WidePlanLedgerCodec.encode(snapshot, REGISTRIES);
        assertTrue(!encoded.isEmpty(), "有溢出键时编码串非空");

        WideLedgerSnapshot decoded = WidePlanLedgerCodec.decode(encoded, REGISTRIES);

        AEKey lavaKey = key(Items.LAVA_BUCKET);
        AEKey paperKey = key(Items.PAPER);
        assertEquals(OVERFLOW, decoded.get(WideAmountKind.MISSING, lavaKey),
                "缺料真值往返一致且 AEKey 按值还原");
        assertEquals(OVERFLOW.add(BigInteger.ONE), decoded.get(WideAmountKind.EMIT, paperKey),
                "产出真值往返一致");
    }

    @Test
    void onlyOverflowKeysAreCaptured() {
        WideKeyCounter missing = new WideKeyCounter();
        missing.add(key(Items.STONE), 12_345L); // 能塞 long，不该进快照
        WideLedgerSnapshot snapshot = WideLedgerSnapshot.capture(missing, null, null);
        assertTrue(snapshot.isEmpty(), "无溢出键时快照为空");
        assertEquals("", WidePlanLedgerCodec.encode(snapshot, REGISTRIES));
        assertTrue(WidePlanLedgerCodec.decode("", REGISTRIES).isEmpty());
    }

    @Test
    void multipleOverflowRecordsSeparateAndReparse() {
        WideKeyCounter used = new WideKeyCounter();
        used.add(key(Items.IRON_INGOT), OVERFLOW);
        used.add(key(Items.GOLD_INGOT), OVERFLOW.multiply(BigInteger.valueOf(3)));
        WideLedgerSnapshot snapshot = WideLedgerSnapshot.capture(null, used, null);
        String encoded = WidePlanLedgerCodec.encode(snapshot, REGISTRIES);
        WideLedgerSnapshot decoded = WidePlanLedgerCodec.decode(encoded, REGISTRIES);

        Map<AEKey, BigInteger> usedEntries = decoded.entries(WideAmountKind.USED);
        assertEquals(2, usedEntries.size());
        assertEquals(OVERFLOW, decoded.get(WideAmountKind.USED, key(Items.IRON_INGOT)));
        assertEquals(OVERFLOW.multiply(BigInteger.valueOf(3)),
                decoded.get(WideAmountKind.USED, key(Items.GOLD_INGOT)));
    }

    @Test
    void decodeToleratesGarbageAndMissingProvider() {
        assertTrue(WidePlanLedgerCodec.decode(null, REGISTRIES).isEmpty());
        assertTrue(WidePlanLedgerCodec.decode("nonsense", REGISTRIES).isEmpty());
        assertTrue(WidePlanLedgerCodec.decode("miss|!!!|abc", REGISTRIES).isEmpty());
        assertTrue(WidePlanLedgerCodec.decode("miss|aGVsbG8=|123", null).isEmpty());
        assertNull(WidePlanLedgerCodec.decode("", REGISTRIES).get(WideAmountKind.MISSING, key(Items.STONE)));
    }
}
