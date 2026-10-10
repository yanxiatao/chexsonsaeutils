package git.chexson.chexsonsaeutils.crafting;

/**
 * 本模组并行 CPU 的能力契约，供规划层识别节点宿主而无需依赖具体方块实体。
 *
 * <p>仅描述当前能否承接并行合成；CPU 注册、任务执行和设备生命周期仍由宿主实现负责。
 */
public interface ParallelCpuProvider {

    /**
     * 判断宿主现在是否可处理并行 CPU 任务，作为图规划启用所需的活跃 CPU 条件。
     *
     * @return 宿主已准备就绪、接入网格且节点满足运行条件时为 {@code true}
     */
    boolean canProcessParallelCpuJobs();
}
