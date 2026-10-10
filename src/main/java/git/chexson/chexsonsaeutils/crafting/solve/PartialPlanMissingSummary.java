package git.chexson.chexsonsaeutils.crafting.solve;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.KeyCounter;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import git.chexson.chexsonsaeutils.integration.ae2ct.Ae2ctJobBridge;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import git.chexson.chexsonsaeutils.bigint.WideLedgerSnapshot;

/**
 * 把残缺计划里被 AE2 抹成 0 的缺料行还原出来。
 *
 * <p>AE2 的 {@code CraftingPlanSummary.fromJob} 有两步：把 {@code job.missingItems()} 直接
 * 累进 {@code stored}（{@code :117-119}），然后只在 {@code simulation && !canEmitFor(key)}
 * 时才把缺口算成 {@code missingAmount}（{@code :139-145}），否则 {@code missingAmount = 0}。
 * 于是"网里有样板能产它"的键——海量级计划的缺口几乎全是这类——永远不显示缺料，
 * 而那份缺口还被当成"可用数量"报给玩家。底部"残缺的合成计划"只由
 * {@code plan.isSimulation()} 决定，跟逐格缺口是两件事，所以会出现"说材料不足、又不说缺什么"。
 *
 * <p>这里只做一件事：AE2 报 {@code missing=0} 而计划本身申报了缺口的条目，把那份缺口从
 * {@code stored} 里挪回 {@code missing}。AE2 已经算出缺口的条目一律不动，避免重复计。
 * 超过 {@code long} 的真值仍由确认屏的宽域改写负责（那份账在 {@link WideLedgerSnapshot} 里）。
 */
public final class PartialPlanMissingSummary {

    private PartialPlanMissingSummary() {
    }

    /**
     * @param summary AE2 自己算出的概要
     * @param job     该概要对应的计划（用它的申报缺料）
     * @return 还原过缺口的概要；无需还原时原样返回入参对象
     */
    public static CraftingPlanSummary revealDeclaredMissing(
            @Nullable CraftingPlanSummary summary,
            @Nullable ICraftingPlan job
    ) {
        if (summary == null || job == null || !job.simulation()) {
            return summary;
        }
        KeyCounter declaredMissing = job.missingItems();
        List<CraftingPlanSummaryEntry> entries = summary.getEntries();
        if (declaredMissing == null || declaredMissing.isEmpty() || entries.isEmpty()) {
            return summary;
        }
        List<CraftingPlanSummaryEntry> rebuilt = null;
        for (int index = 0; index < entries.size(); index++) {
            CraftingPlanSummaryEntry entry = entries.get(index);
            if (entry.getMissingAmount() != 0L) {
                continue;
            }
            long declared = declaredMissing.get(entry.getWhat());
            if (declared <= 0L) {
                continue;
            }
            if (rebuilt == null) {
                rebuilt = new ArrayList<>(entries);
            }
            long stored = entry.getStoredAmount();
            rebuilt.set(index, new CraftingPlanSummaryEntry(
                    entry.getWhat(),
                    declared,
                    stored > declared ? stored - declared : 0L,
                    entry.getCraftAmount()));
        }
        if (rebuilt == null) {
            return summary;
        }
        // 与 AE2 同一判据重排：缺口最大的排前面，别让玩家往下翻才看到缺什么。
        Collections.sort(rebuilt);
        CraftingPlanSummary revealed =
                new CraftingPlanSummary(summary.getUsedBytes(), summary.isSimulation(), List.copyOf(rebuilt));
        if (summary instanceof git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingPlanSummary source
                && revealed instanceof git.chexson.chexsonsaeutils.crafting.status.EnhancedCraftingPlanSummary target)
            target.chexsonsaeutils$setGraph(source.chexsonsaeutils$graph());
        // 换对象等于把 ae2ct 挂在旧对象上的那份 recipe helper 丢了——它的 write TAIL 无条件
        // 解引用那个字段，丢了就是把整条连接的编码打死。这里把 job 搬过来，合成树照旧。
        // 没装 ae2ct 时是空操作（Ae2ctJobBridge 全程反射，认不出形状就什么都不做）。
        Ae2ctJobBridge.copyJob(summary, revealed);
        return revealed;
    }
}
