package git.chexson.chexsonsaeutils.integration.ae2ct;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import git.chexson.chexsonsaeutils.integration.ae2ct.Ae2ctJobBridge.Outcome;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.Items;

/**
 * ae2ct 兼容守卫的离线用例。
 *
 * <p>单测源集不过 mixin 转换器（ae2ct 也压根不在测试 classpath 上），所以这里用
 * {@link ShapedSummary} 手工复刻"ae2ct mixin 应用之后"的那对 {@code getJob/setJob}，
 * {@link FakeJob} 逐行对着 {@code reference-sources/ae2ct/.../api/RecipeHelper.java}
 * 的 write/read 抄（{@code GenericStack.writeBuffer(output, buf)} +
 * {@code writeVarInt(recipes.size())}）——字段为 null 时炸的就是实机那句。
 *
 * <p>mixin 是否真被应用、注入点是否真落在 {@code write} 的 HEAD，离线证不到，只能实机验。
 */
class Ae2ctJobBridgeTest {

    private static final RegistryAccess REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
    }

    private static List<CraftingPlanSummaryEntry> entries() {
        return List.of(new CraftingPlanSummaryEntry(AEItemKey.of(Items.GLASS), 7L, 1_000L, 5L));
    }

    private static byte[] bytesOf(RegistryFriendlyByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }

    /** 没装 ae2ct：真实的 CraftingPlanSummary 上没有那对方法，守卫必须什么都不改。 */
    @Test
    void guardIsANoOpWhenAe2ctIsAbsent() {
        CraftingPlanSummary control = new CraftingPlanSummary(1L, true, entries());
        CraftingPlanSummary guarded = new CraftingPlanSummary(1L, true, entries());

        assertEquals(Outcome.NO_AE2CT_SHAPE, Ae2ctJobBridge.fillEmptyJobWhenMissing(guarded),
                "没有 ae2ct 形状时应原样放过");
        assertEquals(Outcome.NO_AE2CT_SHAPE, Ae2ctJobBridge.copyJob(control, guarded),
                "两头都没有形状时也不该动手");

        RegistryFriendlyByteBuf controlBuf = buffer();
        RegistryFriendlyByteBuf guardedBuf = buffer();
        control.write(controlBuf);
        guarded.write(guardedBuf);
        assertArrayEquals(bytesOf(controlBuf), bytesOf(guardedBuf), "守卫不许动一个字节");
    }

    /** 复现实机那句 NPE，并证明守卫补的那个空 helper 编得出、读得回。 */
    @Test
    void nullJobKillsEncodingAndGuardMakesItEncode() {
        ShapedSummary shaped = new ShapedSummary();
        RegistryFriendlyByteBuf doomed = buffer();

        assertThrows(NullPointerException.class, () -> shaped.writeLikeAe2ct(doomed),
                "job 为 null 时 jobs.write(buffer) 必炸（实机那句 NPE）");
        assertNull(shaped.getJob());

        assertEquals(Outcome.FILLED_EMPTY, Ae2ctJobBridge.fillEmptyJobWhenMissing(shaped));
        assertNotNull(shaped.getJob(), "守卫应补上一个非空的 helper");
        assertNull(shaped.getJob().output, "补的是空计划：没有产物");
        assertTrue(shaped.getJob().recipes.isEmpty(), "补的是空计划：没有配方");

        RegistryFriendlyByteBuf encoded = buffer();
        assertDoesNotThrow(() -> shaped.writeLikeAe2ct(encoded), "补完之后整包应能编码");

        // helper 那段的线格式与 RecipeHelper.read 对称：output 写 false、recipes 写 0
        RegistryFriendlyByteBuf helperOnly = buffer();
        shaped.getJob().write(helperOnly);
        FakeJob readBack = FakeJob.read(helperOnly);
        assertNull(readBack.output);
        assertTrue(readBack.recipes.isEmpty());
        assertEquals(0, helperOnly.readableBytes(), "写出去的字节应能被 read 全吃完");
    }

    /** 守卫只在字段为空时动手：正常路径（AE2CTRecipeHelperCompatMixin 已填好）原样放过。 */
    @Test
    void existingJobIsNeverOverwritten() {
        ShapedSummary shaped = new ShapedSummary();
        FakeJob real = new FakeJob(new GenericStack(AEItemKey.of(Items.GLASS), 1L), List.<Object>of("one recipe"));
        shaped.setJob(real);

        assertEquals(Outcome.UNCHANGED, Ae2ctJobBridge.fillEmptyJobWhenMissing(shaped));
        assertSame(real, shaped.getJob(), "已有的 recipe helper 不该被动过");

        RegistryFriendlyByteBuf encoded = buffer();
        assertDoesNotThrow(() -> shaped.writeLikeAe2ct(encoded));
    }

    /** 残缺计划重建出的新对象要拿到原来那份 job，否则合成树白屏、还得靠 write 兜底。 */
    @Test
    void copyJobMovesHelperOntoRebuiltSummary() {
        ShapedSummary source = new ShapedSummary();
        FakeJob real = new FakeJob(new GenericStack(AEItemKey.of(Items.GLASS), 1L), List.<Object>of("one recipe"));
        source.setJob(real);
        ShapedSummary rebuilt = new ShapedSummary();

        assertEquals(Outcome.COPIED, Ae2ctJobBridge.copyJob(source, rebuilt));
        assertSame(real, rebuilt.getJob());
        assertSame(real, source.getJob(), "搬运不该把源头掏空");

        RegistryFriendlyByteBuf encoded = buffer();
        assertDoesNotThrow(() -> rebuilt.writeLikeAe2ct(encoded), "搬完之后重建的概要也能编码");

        assertEquals(Outcome.UNCHANGED, Ae2ctJobBridge.copyJob(source, rebuilt), "已有 job 就不再覆盖");
        assertEquals(Outcome.NO_AE2CT_SHAPE, Ae2ctJobBridge.copyJob(new ShapedSummary(), rebuilt),
                "源头本来是空的就什么都不做");
    }

    /** 反射自身出问题（setJob 一调就抛）时静默放行，异常绝不漏回编码路径。 */
    @Test
    void brokenReflectionIsSwallowed() {
        BrokenSummary broken = new BrokenSummary();

        assertEquals(Outcome.FAILED_SILENT, Ae2ctJobBridge.fillEmptyJobWhenMissing(broken));
        assertEquals(1, broken.setJobCalls, "确实试过补，只是补不上");
        assertNull(broken.getJob());

        RegistryFriendlyByteBuf encoded = buffer();
        // 补不上时字段仍是 null，ae2ct 那条 TAIL 依旧会炸——但守卫自己绝不添乱，
        // AE2 本体的 write 必须照常完成。
        assertDoesNotThrow(() -> broken.write(encoded));
    }

    /** 复刻 ae2ct mixin 应用后的 CraftingPlanSummary：一对 getJob/setJob + write 的 TAIL。 */
    public static class ShapedSummary extends CraftingPlanSummary {

        private FakeJob job;

        ShapedSummary() {
            super(1L, true, entries());
        }

        public FakeJob getJob() {
            return job;
        }

        public void setJob(FakeJob job) {
            this.job = job;
        }

        /** AE2 本体的 write + ae2ct 那条无条件 {@code jobs.write(buffer)} 的 TAIL 注入。 */
        void writeLikeAe2ct(RegistryFriendlyByteBuf buffer) {
            write(buffer);
            job.write(buffer);
        }
    }

    /** 形状同 {@code com.neuvillette.ae2ct.api.RecipeHelper}：构造器与读写签名逐一对齐。 */
    public static class FakeJob {

        private final GenericStack output;
        private final List<Object> recipes;

        public FakeJob(GenericStack output, List<Object> recipes) {
            this.output = output;
            this.recipes = recipes;
        }

        public void write(RegistryFriendlyByteBuf buffer) {
            GenericStack.writeBuffer(output, buffer);
            buffer.writeVarInt(recipes.size());
        }

        public static FakeJob read(RegistryFriendlyByteBuf buffer) {
            GenericStack output = GenericStack.readBuffer(buffer);
            List<Object> recipes = new ArrayList<>();
            int size = buffer.readVarInt();
            for (int i = 0; i < size; i++) {
                recipes.add("recipe " + i);
            }
            return new FakeJob(output, recipes);
        }
    }

    /** getJob 认得出来、setJob 一调就抛——用来验守卫的静默放行。 */
    public static class BrokenSummary extends CraftingPlanSummary {

        private int setJobCalls;

        BrokenSummary() {
            super(1L, true, entries());
        }

        public FakeJob getJob() {
            return null;
        }

        public void setJob(FakeJob job) {
            this.setJobCalls++;
            throw new IllegalStateException("simulated ae2ct accessor failure");
        }
    }
}
