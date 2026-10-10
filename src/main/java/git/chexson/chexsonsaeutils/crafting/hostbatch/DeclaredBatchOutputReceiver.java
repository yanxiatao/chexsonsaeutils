package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import java.math.BigInteger;

/** 批量机器实物的宽域接收入口；声明批回调与装配机回流共用，计划清单不得调用此口。 */
@FunctionalInterface
public interface DeclaredBatchOutputReceiver {
    /**
     * 接收机器当前网络中的真实产物，仅认领 CPU 实际等待的数量。
     * @param sourceGrid 交付时机器所在网络，用于阻止拆网后的跨网交付
     * @param what 真实产物键
     * @param available 机器尚未交出的精确数量
     * @return 已进入 CPU 库存的数量；机器必须按此数扣队列，余量保留原交付路径
     */
    BigInteger receive(IGrid sourceGrid, AEKey what, BigInteger available);
}
