package git.chexson.chexsonsaeutils.parts.playersimulation;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.StorageHelper;
import com.google.common.collect.ImmutableSet;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;

/** 每个槽至多保留一个补货计算或任务；不采用无日志吞异常的合成跟踪器。 */
public final class SimulationCrafting {
    private final ICraftingRequester owner;
    private final Future<ICraftingPlan>[] calculations;
    private final ICraftingLink[] links = new ICraftingLink[SimulationBuffer.SIZE];
    private final AEItemKey[] keys = new AEItemKey[SimulationBuffer.SIZE];
    @SuppressWarnings("unchecked")
    public SimulationCrafting(ICraftingRequester owner) {
        this.owner = owner;
        calculations = (Future<ICraftingPlan>[]) new Future<?>[SimulationBuffer.SIZE];
    }
    /** 缺货时启动或推进真实 AE2 自动合成。 */
    public void request(int slot, AEItemKey key, int amount, Level level, IGrid grid, IActionSource source) {
        if (keys[slot] != null && !keys[slot].equals(key)) cancel(slot);
        keys[slot] = key;
        if (links[slot] != null) return;
        var service = grid.getCraftingService();
        var calculation = calculations[slot];
        if (calculation == null) {
            calculations[slot] = service.beginCraftingCalculation(level, () -> source, key, amount, CalculationStrategy.CRAFT_LESS);
        } else if (calculation.isDone()) {
            try {
                var result = service.submitJob(calculation.get(), owner, null, false, source);
                if (result.successful()) links[slot] = result.link();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LogUtils.getLogger().error("玩家模拟面板补货计算被中断，槽位 {}", slot, e);
            } catch (ExecutionException e) {
                LogUtils.getLogger().error("玩家模拟面板补货计算失败，槽位 {}，物品 {}", slot, key, e.getCause());
            } finally {
                calculations[slot] = null;
            }
        }
    }
    /** 世界卸载时只取消尚未提交的计算，保留可恢复的合成连接。 */
    public void cancelCalculations() {
        for (int i = 0; i < calculations.length; i++) {
            if (calculations[i] != null) calculations[i].cancel(true);
            calculations[i] = null;
        }
    }
    /** 配置变化、升级卡移除或拆卸时取消对应任务。 */
    public void cancel(int slot) {
        if (calculations[slot] != null) calculations[slot].cancel(true);
        if (links[slot] != null) links[slot].cancel();
        calculations[slot] = null;
        links[slot] = null;
        keys[slot] = null;
    }
    /** 取消所有任务，释放计算引用。 */
    public void cancelAll() { for (int i = 0; i < links.length; i++) cancel(i); }
    /** 配置不再匹配时取消旧任务。 */
    public void reconcile(int slot, AEItemKey key, boolean enabled) {
        if (!enabled || (keys[slot] != null && !keys[slot].equals(key))) cancel(slot);
    }
    /** 暴露正在等待的合成回执给 AE2。 */
    public ImmutableSet<ICraftingLink> jobs() {
        var builder = ImmutableSet.<ICraftingLink>builder();
        for (var link : links) if (link != null) builder.add(link);
        return builder.build();
    }
    /** 找到合成产物对应的缓存槽；已经取消或被替换的连接没有槽位。 */
    public int slot(ICraftingLink link) {
        for (int i = 0; i < links.length; i++) if (links[i] == link) return i;
        return -1;
    }
    /** 收到完成或取消回执时移除引用。 */
    public void changed(ICraftingLink link) {
        for (int i = 0; i < links.length; i++) if (links[i] == link) {
            links[i] = null;
            keys[i] = null;
        }
    }
    /** 保存连接，避免重启重复提交。 */
    public void write(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        for (int i = 0; i < links.length; i++) if (links[i] != null) {
            var data = new CompoundTag();
            links[i].writeToNBT(data);
            if (keys[i] != null) data.put("simulationKey", keys[i].toTag(registries));
            tag.put("job" + i, data);
        }
    }
    /** 恢复 AE2 合成连接。 */
    public void read(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        for (int i = 0; i < links.length; i++) if (tag.contains("job" + i)) {
            var data = tag.getCompound("job" + i);
            links[i] = StorageHelper.loadCraftingLink(data, owner);
            keys[i] = AEItemKey.fromTag(registries, data.getCompound("simulationKey"));
        }
    }
}
