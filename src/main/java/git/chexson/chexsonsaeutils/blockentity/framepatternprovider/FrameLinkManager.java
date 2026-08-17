package git.chexson.chexsonsaeutils.blockentity.framepatternprovider;

/**
 * 框架与私有维度机器之间的 AE2 虚拟连接管理器。
 * <p>
 * 动机：机器被捕获后位于私有维度，其网格节点与主世界网格物理隔离。
 * 本接口负责通过 GridHelper.createConnection 建立跨维度虚拟连接（量子桥同款机制），
 * 并按隔离配置决定连接拓扑：非隔离并入主网格，隔离仅经石英纤维 overlay 共享能量。
 * <p>
 * 成员作用：
 * <ul>
 *   <li>ensureVirtualNode：创建私有维度侧虚拟节点（ManagedGridNode，非世界节点）。</li>
 *   <li>tick：每 tick 检查机器节点就绪状态，就绪则补连，失效则销毁连接。</li>
 *   <li>rebuild：隔离配置切换后重建连接拓扑。</li>
 *   <li>teardownLink：销毁全部连接与虚拟节点（拆除/卸载时调用）。</li>
 * </ul>
 */
public interface FrameLinkManager {

    /**
     * 创建私有维度侧虚拟节点（若尚未创建）。
     * <p>
     * 虚拟节点按当前隔离配置注册服务：隔离模式注册 overlay 能量桥，非隔离模式不注册。
     * 节点创建后若未连接任何节点，会形成无害的单节点网格。
     */
    void ensureVirtualNode();

    /**
     * 每 tick 检查连接状态：机器节点就绪则补连，机器节点失效（搬回/卸载）则销毁连接。
     */
    void tick();

    /**
     * 隔离配置切换后重建连接拓扑：销毁旧连接与虚拟节点，再按新配置重建。
     */
    void rebuild();

    /**
     * 销毁全部连接与虚拟节点（拆除、卸载、切换配置时调用）。
     */
    void teardownLink();
}