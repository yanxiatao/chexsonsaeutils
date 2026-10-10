package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.stacks.GenericStack;
import appeng.crafting.inv.ListCraftingInventory;

import java.util.List;

/**
 * 已准备的整批物理派发契约，供 CPU 在推送前检查份数、真实输入与结算明细。
 * <p>与声明宿主只收单发原型不同，本批次推送负责整批提料；CPU 不为其建立原型材料义务。
 */
public interface PreparedBatch {

    /** 将已准备批次从 CPU 库存物理派发；拒收应交回 false，能量由 CPU 成功结算时唯一扣除。 */
    boolean push(ListCraftingInventory inventory);

    /** 宿主实际准备推进的完整发数，用于请求上限检查和任务结算。 */
    long craftCount();

    /** 宿主申报的整批输入总量，用于 push 前复核；实际销账以 CPU 库存宽域差值为准。 */
    List<GenericStack> inputTotal();

    /** 整批预期产出，用于登记等待回网的数量。 */
    List<GenericStack> outputs();

    /** 整批预期容器回流，用于等待回网与回流结算。 */
    List<GenericStack> remainders();

    /** 整批功耗，由 CPU 在派发成功后扣除一次。 */
    double power();
}
