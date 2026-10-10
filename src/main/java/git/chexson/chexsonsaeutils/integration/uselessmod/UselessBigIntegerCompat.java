package git.chexson.chexsonsaeutils.integration.uselessmod;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * useless_mod BigInteger 批次的 mod 生命周期入口。
 *
 * <p>动机：useless_mod 是 compileOnly 软依赖（见 {@code build.gradle} 的
 * {@code compileOnly files('reference-sources/useless_mod-*.jar')}），注册必须 ModLoaded 门控
 * ＋ {@code catch (Throwable)} 兜底——没装 useless_mod、或装了但没有 {@code api.crafting.bigint}
 * 通道（2.3.8 之前）时，一行都不碰它的类。仿 {@code AppFluxCompat#registerUpgrade} 的先例。
 *
 * <p>成员作用：{@link #registerCpuAdapter()} 在本 mod 的 {@code FMLCommonSetupEvent#enqueueWork}
 * 里注册 bigint 产物回执适配器；运行期推送与申报在
 * {@code ParallelCraftingCpuLogic} 的 {@code UselessFurnaceBatchHost} 那一支里现判现用，
 * 不缓存在册状态。
 */
public final class UselessBigIntegerCompat {

    private static final Logger LOGGER = LogUtils.getLogger();

    private UselessBigIntegerCompat() {
    }

    /**
     * 注册 bigint 批次的 CPU 回执适配器；通道不在场时静默返回。
     *
     * <p>适配器交回的是机器在 {@code admit} 那一刻按配方<b>自报的计划入网量</b>，只用于把"不会再回
     * CPU 池子的那一截"从实物预期里摘出去——它是<b>预期账</b>，不是"已入网"的回执对账：
     * 产物侧没有任何入网回执读数，{@code blocked=} 里的 {@code 网络sim=/同族[…]下界} 说的是
     * <b>投入侧</b>材料在网上的现量，产物键从不进那一格。注册失败不影响推送本身
     * （料照常吃、批次照常跑），只是这一截摘不出去、只能整批挂 {@code waitingFor} 等实物，
     * 所以整段吞 {@code Throwable}：与 {@code AppFluxCompat} 的降级口径一致，不阻断本 mod 加载。
     */
    public static void registerCpuAdapter() {
        if (!UselessBigIntegerBatchClient.modLoaded()) {
            return;
        }
        try {
            if (!UselessBigIntegerBatchClient.apiAvailable()) {
                return;
            }
            UselessBigIntegerApiBridge.registerCpuAdapter();
        } catch (Throwable e) {
            LOGGER.warn("[chexsonsaeutils-bigint] 注册 bigint 回执适配器失败：批次仍可推送，"
                    + "但没有机器侧产出回执", e);
        }
    }
}
