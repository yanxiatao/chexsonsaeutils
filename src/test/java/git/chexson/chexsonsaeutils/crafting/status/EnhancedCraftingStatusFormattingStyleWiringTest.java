package git.chexson.chexsonsaeutils.crafting.status;

import git.chexson.chexsonsaeutils.config.ChexsonsaeutilsCompatibilityConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 计数方式接线尺（MF-42）：夹住"配置串 → EnhancedCraftingStatusFormatting.currentStyle()"这一行接线。
//
// 治的病：序3 那笔"行为不变"此前只靠论证（读点唯一＋表达式原样搬运），不靠尺。
// 实测过的无牙变异＝把 currentStyle() 改成恒返 ABBREVIATED（读了配置一个字没用）⇒ 全量仍绿、census 不降
// （见 .scratch/280-must-fix.md MF-42）。既有用例只在 AmountNumberStyle 的参数档上打
// （EnhancedCraftingStatusFormattingWideTest），从不驱动配置读点；grep -rn "currentStyle" src/test 现量 0 命中。
//
// 驱动入口＝公开的 ModConfigSpec.ConfigValue#set(T)（net/neoforged/neoforge/common/ModConfigSpec.java，
// 原文注释 "Directly sets the value, without firing events or writing the config to disk."）：
// 不落盘、不发事件；这枚键没标 restart ⇒ RestartType.NONE ⇒ set() 同步刷 cachedValue ⇒ 立刻读得回。
//
// ⚠ 不走"写 TOML ＋ chexsonsaeutils.configDir"那条路：stringValue() 的 !isLoaded() 分支只回
// getDefault()、根本不查 TOML（ChexsonsaeutilsCompatibilityConfig.java:478-479，读点 :484-486），
// 对这个键是空枪——那套只对 featureEnabled/readInt 这类走 StartupConfigReader 的键有效。
//
// 一行只判一件事：①前置 loaded（红要落在缺失前提上，不许假绿）②scientific→SCIENTIFIC
// ③abbreviated→ABBREVIATED ④认不出的串→ABBREVIATED（极性单开一行）。
// @AfterEach 用 set(getDefault()) 还原这一枚键；不摘 loaded 轨、不动 SPEC 之外的全局。
final class EnhancedCraftingStatusFormattingStyleWiringTest {

    // 被驱动的那枚键：config 侧的 public 常量，不需要反射。
    private static final ModConfigSpec.ConfigValue<String> STYLE_KEY =
            ChexsonsaeutilsCompatibilityConfig.CRAFTING_AMOUNT_NUMBER_STYLE;

    // 门名标签：红字自带归因（测试日志不印用例名）。
    private static final String TAG = "STYLEWIRING";

    @AfterEach
    void restoreConfiguredDefault() {
        // 只把这枚键 set 回默认串；不摘 loaded 轨、不碰别的全局。
        STYLE_KEY.set(STYLE_KEY.getDefault());
    }

    @Test
    void currentStyleFollowsTheLoadedConfigString() {
        // ①前置：本臂量的是 loaded 轨；轨不在就红在这里，而不是红在后面三档（不许假绿）。
        assertTrue(ChexsonsaeutilsCompatibilityConfig.SPEC.isLoaded(),
                TAG + "|gate=SW-PRE|SPEC 未加载 ⇒ loaded 轨不存在，四档一根都没被求值（缺失前提，不是行为差异）");

        // ②正向档：串 scientific 接到 SCIENTIFIC。
        STYLE_KEY.set("scientific");
        assertEquals(AmountNumberStyle.SCIENTIFIC, EnhancedCraftingStatusFormatting.currentStyle(),
                TAG + "|gate=SW-SCIENTIFIC|配置串 scientific 没接到 SCIENTIFIC ⇒ 读点被摘或接线断（序3 那笔即此形状）");

        // ③默认档：串 abbreviated 接到 ABBREVIATED。
        STYLE_KEY.set("abbreviated");
        assertEquals(AmountNumberStyle.ABBREVIATED, EnhancedCraftingStatusFormatting.currentStyle(),
                TAG + "|gate=SW-ABBREVIATED|配置串 abbreviated 没接到 ABBREVIATED");

        // ④极性档：认不出的串回缩写（配置写错时不该让界面空），单开一行只判这一件事。
        STYLE_KEY.set("bogus");
        assertEquals(AmountNumberStyle.ABBREVIATED, EnhancedCraftingStatusFormatting.currentStyle(),
                TAG + "|gate=SW-FALLBACK|无法识别的串没回 ABBREVIATED ⇒ 兜底极性被翻");
    }
}
