package git.chexson.chexsonsaeutils.mattermassstore;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 存储与终端的行为场景；由类别测试统一调用。 */
public final class StorageScenarios {
    private StorageScenarios() {}

    /** 执行本包的行为场景，保留各场景名称与状态隔离。 */
    public static void verify(java.nio.file.Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("git.chexson.chexsonsaeutils.mattermassstore",
                () -> MatterMassStoreWideLedgerCases.verify(temporaryRoot)
        );
    }

    /** MatterMassStoreWideLedgerCases 的独立场景夹具，按类别统一执行。 */
    public static final class MatterMassStoreWideLedgerCases {
        private static final RegistryAccess REGISTRIES =
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
        private static final AEItemKey COPPER = AEItemKey.of(Items.COPPER_INGOT);

        /** {@code 2^63 + 1}＝{@code Long.MAX_VALUE} 再续 2 枚：越出 long 上界。 */
        private static final BigInteger ABOVE_LONG = new BigInteger("9223372036854775809");

        /** 溢出侧表随 append 累积、随落盘出门、随重启回来；饱和 long 视图一格都不许多报。 */
        void matterMassAppendKeepsOverflowAndSurvivesDiskRoundTrip() throws Exception {
            var store = MatterMassStore.global();
            Path root = Files.createTempDirectory("matter-mass-wide");
            UUID id = UUID.randomUUID();
            try {
                store.load(root, REGISTRIES);

                store.append(id, List.of(new GenericStack(IRON, Long.MAX_VALUE)));
                store.append(id, List.of(new GenericStack(IRON, 2L), new GenericStack(COPPER, 5L)));

                assertEquals(ABOVE_LONG, store.exactAmount(id, IRON),
                        "append 第二笔那 2 枚必须进侧表，不许被夹顶后连着侧表一起丢");
                assertEquals(Long.MAX_VALUE, longView(store, id, IRON),
                        "网络那侧仍只看到饱和读数：报小不报大这条口径不许动");
                assertEquals(BigInteger.valueOf(5L), store.exactAmount(id, COPPER),
                        "没越界的键本数就是 long 视图，一位都不许多出来");

                store.save(root, REGISTRIES);
                var onDisk = NbtIo.readCompressed(root.resolve("data/chexsonsaeutils/matter_mass/"
                        + id + ".nbt"), NbtAccounter.unlimitedHeap());
                var contents = onDisk.getList("contents", Tag.TAG_COMPOUND);
                assertEquals(2, contents.size(), "铁那一格与铜那一格各一条");
                assertTrue(contents.getCompound(0).contains("bigAmount", Tag.TAG_BYTE_ARRAY),
                        "越界那一格必须带着补码全量字节出门");
                assertFalse(contents.getCompound(1).contains("bigAmount", Tag.TAG_BYTE_ARRAY),
                        "控制腿：装得进 long 的铜格不许多挂宽域键");

                // 换一根 root 把内存抹干净，再读回同一份磁盘文件——模拟重启
                store.load(Files.createTempDirectory("matter-mass-other"), REGISTRIES);
                assertTrue(store.isEmpty(id), "前提：内存条目确实被抹掉了");
                store.load(root, REGISTRIES);

                assertEquals(ABOVE_LONG, store.exactAmount(id, IRON),
                        "重启后越界那一截逐位回来，不是只把 Long.MAX_VALUE 搬回来");
                assertEquals(BigInteger.valueOf(5L), store.exactAmount(id, COPPER), "没越界那格逐字不变");

                // 侧表是本数：按真值扣，扣掉 1 枚还剩 2^63——越界那一截还在
                assertEquals(1L, store.extract(id, IRON, 1L, Actionable.MODULATE));
                assertEquals(ABOVE_LONG.subtract(BigInteger.ONE), store.exactAmount(id, IRON),
                        "扣 1 枚后本数是 2^63，不是从饱和读数回退出来的 9223372036854775806");
                assertEquals(Long.MAX_VALUE, longView(store, id, IRON), "减完仍越界，饱和视图照旧顶格");

                // 再扣 1 枚恰好落回 long 域顶格：侧表条目必须撤下，否则旧真值盖住新账
                assertEquals(1L, store.extract(id, IRON, 1L, Actionable.MODULATE));
                assertEquals(BigInteger.valueOf(Long.MAX_VALUE), store.exactAmount(id, IRON),
                        "落回 long 域那一档要撤掉侧表条目，本数逐字等于饱和视图");
                assertEquals(Long.MAX_VALUE, longView(store, id, IRON), "网络读数照旧顶格");
            } finally {
                store.load(Files.createTempDirectory("matter-mass-drain"), REGISTRIES);
            }
        }

        /** 两团合并走侧表真值：老写法 {@code append(target, takeAll(source))} 交回的是饱和视图。 */
        void matterMassMergeMovesOverflowInsteadOfClampingIt() throws Exception {
            var store = MatterMassStore.global();
            Path root = Files.createTempDirectory("matter-mass-merge");
            UUID source = UUID.randomUUID();
            UUID survivor = UUID.randomUUID();
            try {
                store.load(root, REGISTRIES);
                store.append(source, List.of(new GenericStack(IRON, Long.MAX_VALUE)));
                store.append(source, List.of(new GenericStack(IRON, 2L)));
                store.append(survivor, List.of(new GenericStack(COPPER, 1L)));
                assertEquals(ABOVE_LONG, store.exactAmount(source, IRON), "前提：源团那一格确实越界");

                store.transferAll(survivor, source);

                assertEquals(ABOVE_LONG, store.exactAmount(survivor, IRON),
                        "合并这一刀要把越界那一截一起搬过来，不许只搬 Long.MAX_VALUE");
                assertEquals(BigInteger.valueOf(1L), store.exactAmount(survivor, COPPER), "目标团自己的格不受影响");
                assertTrue(store.isEmpty(source), "源团条目随合并删除");
            } finally {
                store.load(Files.createTempDirectory("matter-mass-drain"), REGISTRIES);
            }
        }

        private static long longView(MatterMassStore store, UUID id, AEItemKey key) {
            for (var stack : store.getContents(id)) {
                if (stack.what().equals(key)) {
                    return stack.amount();
                }
            }
            throw new AssertionError("内容物里没有这一格: " + key);
        }

        static void verify(java.nio.file.Path temporaryRoot) {
            org.junit.jupiter.api.Assertions.assertAll("MatterMassStoreWideLedgerCases",
                () -> org.junit.jupiter.api.Assertions.assertAll("MatterMassStoreWideLedgerCases.matterMassAppendKeepsOverflowAndSurvivesDiskRoundTrip", () -> new MatterMassStoreWideLedgerCases().matterMassAppendKeepsOverflowAndSurvivesDiskRoundTrip()),
                () -> org.junit.jupiter.api.Assertions.assertAll("MatterMassStoreWideLedgerCases.matterMassMergeMovesOverflowInsteadOfClampingIt", () -> new MatterMassStoreWideLedgerCases().matterMassMergeMovesOverflowInsteadOfClampingIt())
            );
        }
    }
}
