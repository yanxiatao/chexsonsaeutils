package git.chexson.chexsonsaeutils.mixin.neoecoae;

import appeng.api.stacks.AEKeyType;
import cn.dancingsnow.neoecoae.crafting.execution.ElapsedTimeTracker;
import cn.dancingsnow.neoecoae.crafting.amount.NEMath;
import git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoExactCompletedWork;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import java.math.BigInteger;
import java.util.Map;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/** 在 NeoEco 原有持久化进度账中累计精确实收量，不另建进度或存档。 */
@Pseudo
@Mixin(value = ElapsedTimeTracker.class, remap = false)
public abstract class NeoEcoExactProgressMixin implements NeoEcoExactCompletedWork {
    @Shadow private boolean exact;
    @Shadow @Final private Map<AEKeyType, BigInteger> exactCompleted;
    @Shadow @Final private Reference2LongMap<AEKeyType> completedWorkByType;
    @Shadow private void updateTime() { throw new AssertionError(); }
    @Override public void chexsonsaeutils$recordCompleted(BigInteger amount, AEKeyType type) {
        if (!exact || amount.signum() < 0) throw new IllegalStateException("非精确进度或负完成量");
        updateTime();
        exactCompleted.merge(type, amount, BigInteger::add);
        completedWorkByType.merge(type, amount.min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact(),
                NEMath::saturatingAdd);
    }
}
