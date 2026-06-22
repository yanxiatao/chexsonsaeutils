package git.chexson.chexsonsaeutils.crafting.formalmachine;

import appeng.api.stacks.GenericStack;
import appeng.blockentity.crafting.IMolecularAssemblerSupportedPattern;
import git.chexson.chexsonsaeutils.crafting.formalmachine.FormalMachineDelegatingPattern;
import git.chexson.chexsonsaeutils.crafting.planning.FormalMachineAggregationStep;
import git.chexson.chexsonsaeutils.crafting.planning.FormalMachineHostLocator;

import java.util.List;

/**
 * 形式化机器聚合模式接口，表示一个将同一主机上的多个原生 AE2 模式
 * 合并为单个逻辑执行单元的多步骤聚合模式。
 * <p>
 * 由 <code>FormalMachineAggregatedPatternImpl</code> 实现，
 * 用于 Formal Machine 规划聚合流程将同一主机的多次合成折叠为一个节点。
 */
public interface FormalMachineAggregatedPattern
        extends IMolecularAssemblerSupportedPattern, FormalMachineDelegatingPattern {

    /**
     * 返回此聚合模式所属形式化机器主机的定位器。
     *
     * @return 主机定位器，标识具体的方块位置和维度
     */
    FormalMachineHostLocator hostLocator();

    /**
     * 返回聚合后所有步骤的输入总量。
     *
     * @return 聚合输入列表，每项为物品/流体及其总量
     */
    List<GenericStack> aggregatedInputs();

    /**
     * 返回聚合后所有步骤的输出总量。
     *
     * @return 聚合输出列表，每项为物品/流体及其总量
     */
    List<GenericStack> aggregatedOutputs();

    /**
     * 返回聚合后所有步骤的剩余物总量（如模板回填物）。
     *
     * @return 聚合剩余物列表
     */
    List<GenericStack> aggregatedRemainders();

    /**
     * 返回此聚合模式包含的所有执行步骤。
     * <p>
     * 每个步骤对应一个原生模式的一次执行，包含其输入、输出和执行顺序。
     *
     * @return 步骤列表，顺序即为执行顺序
     */
    List<FormalMachineAggregationStep> steps();

    /**
     * 返回此聚合模式的预估执行总耗时（以 tick 为单位）。
     *
     * @return 总 tick 数
     */
    int totalTicks();
}
