package git.chexson.chexsonsaeutils.integration.neoecoae;

import appeng.api.stacks.GenericStack;
import appeng.crafting.inv.ListCraftingInventory;
import cn.dancingsnow.neoecoae.api.me.ECOFastPathFacade;
import git.chexson.chexsonsaeutils.crafting.hostbatch.PreparedBatch;

import java.util.List;
import java.util.Objects;

/** 将 NeoEco 的真实准备批次接入核心契约；整批能量结算仍由 CPU 独占。 */
public final class PreparedBatchImpl implements PreparedBatch {

    /** 已绑定准备时 CPU 库存的真实批次，submit 使用该库存执行提料。 */
    private final ECOFastPathFacade.PreparedBatch prepared;

    /** 接收真实准备结果；null 属于调用错误，必须在适配入口立即失败。 */
    public PreparedBatchImpl(ECOFastPathFacade.PreparedBatch prepared) {
        this.prepared = Objects.requireNonNull(prepared, "prepared");
    }

    @Override
    public boolean push(ListCraftingInventory inventory) {
        // Facade 已在 prepare 时绑定库存；透传能量账防止与 CPU 成功结算重复扣能。
        return prepared.submit(PASS_THROUGH_ENERGY_ACCOUNT);
    }

    @Override
    public long craftCount() {
        return prepared.craftCount();
    }

    @Override
    public List<GenericStack> inputTotal() {
        return prepared.inputTotal();
    }

    @Override
    public List<GenericStack> outputs() {
        return prepared.outputs();
    }

    @Override
    public List<GenericStack> remainders() {
        return prepared.remainders();
    }

    @Override
    public double power() {
        return prepared.power();
    }

    /** 预留不持有能量，提交和退款均无能量状态可更改。 */
    private static final ECOFastPathFacade.Reservation NO_OP_RESERVATION =
            new ECOFastPathFacade.Reservation() {
                @Override
                public void commit() {
                    // CPU 派发成功后唯一扣能；适配层不持有预留。
                }

                @Override
                public void refund() {
                    // 本层从未扣能，无能量需要退款。
                }
            };

    /** CPU 独占实际扣能，因此本适配层只放行提交所需的能量预留。 */
    private static final ECOFastPathFacade.EnergyAccount PASS_THROUGH_ENERGY_ACCOUNT =
            amount -> NO_OP_RESERVATION;
}
