package git.chexson.chexsonsaeutils.crafting.remote;

import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;

/** 跨网实物账本。所有数量都是最小存储单位，模拟不改账；提交、接收、交付分别记账。 */
public interface RemoteLedger<K> {
    /** 接收尚未提交的需求；负数、零与超过容量的接单都必须拒绝。 */
    boolean enqueue(K key, BigInteger amount);
    /** 尚未提交量的不可变快照，调度器按轮转顺序使用。 */
    Map<K, BigInteger> pending();
    /** 来源提交前的可接受额度；上限同时包含正在执行和已经收到的缓冲量。 */
    long batch(K key, long limit);
    /** 提交成功后固定来源 link 并预留完整接收额度。 */
    void submitted(UUID link, K key, long amount);
    /** 接收真实产物；simulate 只查询，无论来源完成顺序如何都不能超过预约。 */
    long receive(UUID link, K key, long amount, boolean simulate);
    /** 来源完成或取消时关闭接收；未收到的数量恢复待办或暂停重试，由调用者决定。 */
    void close(UUID link, boolean retry);
    /** 本设备已接收实物的快照，不含计划产物。 */
    Map<K, BigInteger> buffered();
    /** 成功向目的网络插入后，严格按插入返回值扣除。 */
    void delivered(K key, long amount);
    /** 取消尚未提交量，不改动实物与在途任务。 */
    void cancelPending();
    /** 另一个真实来源已接收未提交订单后，从原设备待办中等额扣除。 */
    void movePending(K key, BigInteger amount);
    /** 保存时用于重建来源任务预约的信息。 */
    Map<UUID, Reservation<K>> reservations();
    /** 一个已提交来源任务的固定目标及剩余接收额度。 */
    record Reservation<K>(K key, long remaining) {}
}
