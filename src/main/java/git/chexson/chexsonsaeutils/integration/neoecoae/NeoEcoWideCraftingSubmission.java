package git.chexson.chexsonsaeutils.integration.neoecoae;

import appeng.api.networking.crafting.ICraftingPlan;
import cn.dancingsnow.neoecoae.api.me.bigorder.ECOBigOrderRequest;
import cn.dancingsnow.neoecoae.api.me.network.ECOCraftingNetworkSettings;
import cn.dancingsnow.neoecoae.api.me.planning.ECOPlannerOptions;
import git.chexson.chexsonsaeutils.crafting.recursive.WideAmountPlanCarrier;
import java.util.function.Function;

/** 在公开提交作用域内携带精确根数量，离开作用域后官方 API 自动清理上下文。 */
public final class NeoEcoWideCraftingSubmission {
    private NeoEcoWideCraftingSubmission() {}
    public static <T> T submit(ICraftingPlan plan, ECOCraftingNetworkSettings settings,
            Function<ICraftingPlan, T> submitter) {
        var request = new ECOBigOrderRequest(plan.finalOutput().what(),
                WideAmountPlanCarrier.resolveFinalOutput(plan).exactValue(), plan.simulation(),
                ECOPlannerOptions.from(settings));
        return request.submit(submitter);
    }
}
