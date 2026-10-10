package git.chexson.chexsonsaeutils.crafting.numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import git.chexson.chexsonsaeutils.bigint.PlanAmount;
import git.chexson.chexsonsaeutils.bigint.KeyIndex;
import git.chexson.chexsonsaeutils.bigint.WideKeyCounter;

/**
 * 等值闸门（第 72 刀第二步用）：对固定输入脚本记录 {@link WideKeyCounter} 全部
 * 对外出口读数（含 0 值在册条目、越界 id、溢出侧表、clear/回滚路径）并折叠成
 * SHA-256 摘要。容器容量/稀疏策略收紧前后，同一脚本的摘要与逐项读数必须一字不差。
 *
 * <p>摘要常量 {@link #EXPECTED_DIGEST} 在 HEAD（收紧前）录制；任何语义漂移都会
 * 让它在改动后拒绝通过。逐行读数同时打到 stdout，便于人工比对。
 */
class WideKeyCounterCapacityEquivalenceTest {

    /** 收紧前（HEAD a50cd5e 初容量 64 + 自带 KeyIndex(64)）录制的读数摘要。 */
    private static final String EXPECTED_DIGEST = "287479b68247866b3247336f12d1edf2d61b8f095dc5ca8d2187cd6cdf33f478";

    private static final Item[] ITEMS = {
            Items.PAPER, Items.STONE, Items.COBBLESTONE, Items.DIRT, Items.SAND, Items.OAK_LOG,
            Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND, Items.REDSTONE, Items.LAPIS_LAZULI,
            Items.COAL, Items.GLASS, Items.BUCKET, Items.CLOCK, Items.STICK, Items.COPPER_INGOT,
            Items.RAW_COPPER, Items.RAW_IRON, Items.SLIME_BALL, Items.GUNPOWDER, Items.SUGAR,
            Items.WHEAT, Items.EGG, Items.APPLE, Items.BREAD, Items.BOW, Items.ARROW,
            Items.FEATHER, Items.LEATHER, Items.BONE, Items.INK_SAC,
            Items.STRING, Items.SNOWBALL, Items.CLAY_BALL, Items.CLAY, Items.BRICK, Items.NETHER_BRICK,
            Items.ENDER_PEARL, Items.BLAZE_ROD, Items.ENDER_EYE, Items.GLOWSTONE_DUST, Items.NETHERITE_SCRAP,
            Items.EMERALD, Items.AMETHYST_SHARD, Items.QUARTZ, Items.PRISMARINE_SHARD, Items.PRISMARINE_CRYSTALS,
            Items.COPPER_BLOCK, Items.IRON_BLOCK, Items.GOLD_BLOCK, Items.DIAMOND_BLOCK, Items.EMERALD_BLOCK,
            Items.LAPIS_BLOCK, Items.REDSTONE_BLOCK, Items.NETHERITE_BLOCK, Items.COAL_BLOCK, Items.SLIME_BLOCK,
            Items.HONEY_BOTTLE, Items.SPIDER_EYE, Items.ROTTEN_FLESH, Items.MELON_SLICE, Items.PUMPKIN_SEEDS,
            Items.MELON_SEEDS, Items.CACTUS, Items.SUGAR_CANE, Items.KELP, Items.SEAGRASS, Items.VINE,
            Items.FERN, Items.DEAD_BUSH, Items.SUNFLOWER, Items.LILAC, Items.ROSE_BUSH, Items.PEONY,
            Items.TORCH, Items.LANTERN, Items.SOUL_LANTERN, Items.CAMPFIRE, Items.OAK_PLANKS,
            Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS,
            Items.DARK_OAK_PLANKS, Items.CRIMSON_PLANKS, Items.WARPED_PLANKS, Items.MANGROVE_PLANKS,
            Items.CHERRY_PLANKS, Items.BAMBOO_PLANKS,
    };

    private static AEKey key(int i) {
        return AEItemKey.of(ITEMS[i]);
    }

    @Test
    void readingsIdenticalAcrossCapacityPolicyChange() throws Exception {
        List<String> lines = new ArrayList<>();

        phaseAPerPatternShape(lines);
        phaseBSharedIndexBeyondFloor(lines);
        phaseCScriptedChurn(lines);

        StringBuilder joined = new StringBuilder();
        for (String line : lines) {
            joined.append(line).append('\n');
        }
        String digest = sha256Hex(joined.toString());
        System.out.println("== WideKeyCounter 等值读数逐项表 ==");
        lines.forEach(System.out::println);
        System.out.println("digest=" + digest + " lines=" + lines.size());
        if (!EXPECTED_DIGEST.equals("PENDING")) {
            assertEquals(EXPECTED_DIGEST, digest, "容量策略改动前后逐项读数摘要必须相等");
        }
    }

    /** 无参形态：每样板一份的账（planMaterialEaten / materialPlan / bill 同款）。 */
    private static void phaseAPerPatternShape(List<String> lines) {
        var c = new WideKeyCounter();
        var paper = key(0);
        var stone = key(1);
        var iron = key(6);

        c.add(paper, 100L);
        c.add(paper, -40L);
        c.add(stone, Long.MAX_VALUE - 1L);
        c.add(stone, 5L); // 越界 → 溢出侧表
        c.subtract(stone, 3L); // 回到窄域 → 侧表条目清除
        c.add(iron, Long.MAX_VALUE);
        c.add(iron, Long.MAX_VALUE); // 常驻溢出
        c.set(paper, 0L); // 0 值在册
        c.setExact(stone, BigInteger.ZERO); // 精确 0 值在册
        c.add(key(2), -7L); // 负值在册
        c.add(key(3), BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)); // 宽域加
        c.add(key(4), PlanAmount.of(BigInteger.TEN).multiply(PlanAmount.of(2L)));

        dump(lines, "A", c);

        // 越界 id 出口：簿上还没有这些 id。
        lines.add("A.beyond.getLongById1000=" + c.getLongById(1000));
        lines.add("A.beyond.containsKeyById1000=" + c.containsKeyById(1000));
        lines.add("A.beyond.rawFastById1000=" + c.rawFastById(1000));
        lines.add("A.beyond.getExactById1000=" + c.getExactById(1000));
        lines.add("A.beyond.amountById1000=" + c.getAmountById(1000));

        // byId 直写只打在真实在册的 id 上：账本不变式「present id ⊆ 在册 id」
        // 不保证未注册直写的形态（产线既有前置条件，本刀不动它）。先把全部键
        // 注册，id 必然覆盖 64/65/70 这些新旧初容量分界两侧的位置。
        int[] ids = new int[ITEMS.length];
        for (int i = 0; i < ITEMS.length; i++) {
            ids[i] = c.idOf(key(i));
        }
        c.addById(ids[70], 5L);
        c.addById(ids[64], Long.MIN_VALUE + 1L);
        c.addById(ids[65], Long.MIN_VALUE);
        c.addById(ids[65], -1L); // 溢出到负宽域
        c.setById(ids[ITEMS.length - 2], 1L);
        c.removeById(ids[ITEMS.length - 2]);
        c.setExactById(ids[ITEMS.length - 1], new BigInteger("123456789012345678901234567890"));
        dump(lines, "A.grown", c);

        var snapshot = c.copy();
        c.add(paper, 123L);
        c.add(key(2), 9L);
        c.clear();
        dump(lines, "A.afterClear", c);
        c.add(stone, 42L);
        dump(lines, "A.refill", c);
        c.restore(snapshot);
        dump(lines, "A.restored", c);

        var src = new WideKeyCounter();
        src.add(stone, Long.MAX_VALUE);
        src.add(stone, 7L);
        src.add(iron, -1L);
        c.putAll(src);
        dump(lines, "A.putAll", c);
    }

    /** 共享键表形态：distinct 键 90 个，id 区间 0..89 穿越新旧初容量分界，验扩容链读数一致。 */
    private static void phaseBSharedIndexBeyondFloor(List<String> lines) {
        var index = new KeyIndex(256);
        var c = new WideKeyCounter(index);
        for (int i = 0; i < 200; i++) {
            int id = index.idOf(key(i % ITEMS.length));
            c.setExactById(id, BigInteger.valueOf(i).multiply(BigInteger.valueOf(1_000_000_007L)));
        }
        c.addById(index.idOf(key(ITEMS.length - 1)), Long.MAX_VALUE);
        c.addById(index.idOf(key(ITEMS.length - 1)), Long.MAX_VALUE);
        lines.add("B.size=" + c.size());
        lines.add("B.total=" + c.total());
        lines.add("B.hasOverflow=" + c.hasOverflow());
        lines.add("B.hasAnyNegativeFast=" + c.hasAnyNegativeFast());
        for (int i = 0; i < ITEMS.length; i++) {
            AEKey k = key(i);
            lines.add("B.k" + i + "=" + c.getExact(k) + "|" + c.getLong(k) + "|" + c.containsKey(k));
        }
    }

    /** 定长脚本搅动：加减设删 + 回滚，每 25 笔全量读数入摘要。 */
    private static void phaseCScriptedChurn(List<String> lines) {
        var c = new WideKeyCounter();
        var snapshotSlots = new WideKeyCounter[20];
        long seed = 0x5DEECE66DL;
        for (int op = 0; op < 500; op++) {
            seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            int pick = (int) Math.floorMod(seed >> 16, ITEMS.length);
            int kind = (int) Math.floorMod(seed >> 5, 7);
            long amount = (seed >>> 10) % 1_000_000_007L;
            AEKey k = key(pick);
            switch (kind) {
                case 0 -> c.add(k, amount);
                case 1 -> c.subtract(k, amount);
                case 2 -> c.set(k, amount);
                case 3 -> c.add(k, BigInteger.valueOf(amount).shiftLeft(64));
                case 4 -> c.remove(k);
                case 5 -> c.setExact(k, BigInteger.valueOf(-amount));
                case 6 -> {
                }
            }
            if (op % 25 == 24) {
                snapshotSlots[op / 25] = c.copy();
            }
            if (op % 100 == 99) {
                var rollback = snapshotSlots[op / 25 - 1];
                if (rollback != null) {
                    c.restore(rollback);
                    lines.add("C.rollback@" + op);
                }
            }
            if (op % 25 == 24) {
                dump(lines, "C@" + op, c);
            }
        }
    }

    /** 全量对外出口读数。 */
    private static void dump(List<String> lines, String tag, WideKeyCounter c) {
        lines.add(tag + ".size=" + c.size());
        lines.add(tag + ".empty=" + c.isEmpty());
        lines.add(tag + ".total=" + c.total());
        lines.add(tag + ".hasOverflow=" + c.hasOverflow());
        lines.add(tag + ".hasAnyNegativeFast=" + c.hasAnyNegativeFast());
        StringBuilder keysSb = new StringBuilder();
        for (AEKey k : c.keys()) {
            keysSb.append(k.toString()).append(';');
        }
        lines.add(tag + ".keys=" + keysSb);
        for (int i = 0; i < ITEMS.length; i++) {
            AEKey k = key(i);
            lines.add(tag + ".k" + i + "=" + c.getExact(k) + "|" + c.getLong(k)
                    + "|" + c.containsKey(k) + "|" + c.getAmount(k) + "|" + c.getIfPresent(k));
        }
        StringBuilder forEachSb = new StringBuilder();
        c.forEachPresent((k, v) -> forEachSb.append(k).append('=').append(v).append(';'));
        lines.add(tag + ".forEach=" + forEachSb);
    }

    private static String sha256Hex(String text) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
        var sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
