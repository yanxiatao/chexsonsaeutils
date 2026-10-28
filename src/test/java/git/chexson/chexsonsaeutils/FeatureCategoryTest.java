package git.chexson.chexsonsaeutils;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

/** 按功能类别维护测试入口；已有场景合并到对应类别，新增小项无需单独建立测试。 */
final class FeatureCategoryTest {
    @Test
    @DisplayName("精确数量与材料账本")
    void exactAmountsAndMaterialAccounts(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("精确数量与材料账本",
                () -> git.chexson.chexsonsaeutils.bigint.NumericScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.crafting.material.NumericScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.crafting.memory.NumericScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.crafting.numeric.NumericScenarios.verify(temporaryRoot)
        );
    }

    @Test
    @DisplayName("存储与终端")
    void storageAndTerminal(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("存储与终端",
                () -> git.chexson.chexsonsaeutils.api.storage.StorageScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.cell.StorageScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.mattermassstore.StorageScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.terminal.StorageScenarios.verify(temporaryRoot)
        );
    }

    @Test
    @DisplayName("图编译与路径选择")
    void graphCompilationAndRoutes(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("图编译与路径选择",
                () -> git.chexson.chexsonsaeutils.crafting.depgraph.GraphScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.crafting.graphplan.graph.GraphScenarios.verify(temporaryRoot)
        );
    }

    @Test
    @DisplayName("规划求解与原生对照")
    void planningAndNativeEquivalence(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("规划求解与原生对照",
                () -> git.chexson.chexsonsaeutils.crafting.graphplan.PlanningScenarios.verify(temporaryRoot)
        );
    }

    @Test
    @DisplayName("材料闭合与事务恢复")
    void materialClosureAndTransactions(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("材料闭合与事务恢复",
                () -> git.chexson.chexsonsaeutils.crafting.graphplan.solve.ClosureScenarios.verify(temporaryRoot)
        );
    }

    @Test
    @DisplayName("规划入口与提交")
    void planningEntryAndSubmission(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("规划入口与提交",
                () -> git.chexson.chexsonsaeutils.crafting.progress.SubmissionScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.crafting.recursive.SubmissionScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.crafting.solve.SubmissionScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.crafting.submit.SubmissionScenarios.verify(temporaryRoot)
        );
    }

    @Test
    @DisplayName("并行 CPU 调度")
    void parallelCpuScheduling(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("并行 CPU 调度",
                () -> git.chexson.chexsonsaeutils.crafting.parallelcpu.CpuScenarios.verify(temporaryRoot)
        );
    }

    @Test
    @DisplayName("批量申报与产出凭据")
    void declaredBatchesAndCredentials(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("批量申报与产出凭据",
                () -> git.chexson.chexsonsaeutils.crafting.parallelcpu.BatchScenarios.verify(temporaryRoot)
        );
    }

    @Test
    @DisplayName("精确交付与持久化")
    void exactDeliveryAndPersistence(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("精确交付与持久化",
                () -> git.chexson.chexsonsaeutils.crafting.parallelcpu.DeliveryScenarios.verify(temporaryRoot)
        );
    }

    @Test
    @DisplayName("供应器与可选模组桥接")
    void providersAndOptionalBridges(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("供应器与可选模组桥接",
                () -> git.chexson.chexsonsaeutils.blockentity.crafting.ProvidersScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.crafting.hostbatch.ProvidersScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.integration.ae2ct.ProvidersScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.integration.hostbatch.ProvidersScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.integration.neoecoae.ProvidersScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.integration.uselessmod.ProvidersScenarios.verify(temporaryRoot)
        );
    }

    @Test
    @DisplayName("跨网合成")
    void remoteCrafting(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("跨网合成",
                () -> git.chexson.chexsonsaeutils.crafting.remote.RemoteScenarios.verify(temporaryRoot)
        );
    }

    @Test
    @DisplayName("合成预览与状态")
    void craftingPreviewAndStatus(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("合成预览与状态",
                () -> git.chexson.chexsonsaeutils.client.gui.crafting.DisplayScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.crafting.request.DisplayScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.crafting.status.DisplayScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.menu.crafting.DisplayScenarios.verify(temporaryRoot)
        );
    }

    @Test
    @DisplayName("网络协议")
    void networkProtocols(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("网络协议",
                () -> git.chexson.chexsonsaeutils.network.codec.ProtocolScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.network.crafting.ProtocolScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.network.ProtocolScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.network.patternaccess.ProtocolScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.network.planning.ProtocolScenarios.verify(temporaryRoot)
        );
    }

    @Test
    @DisplayName("矩阵搜索与同步")
    void matrixSearchAndSynchronization(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("矩阵搜索与同步",
                () -> git.chexson.chexsonsaeutils.client.gui.matrix.MatrixScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.menu.matrix.MatrixScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.network.matrix.MatrixScenarios.verify(temporaryRoot)
        );
    }

    @Test
    @DisplayName("直连加工")
    void directProcessing(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("直连加工",
                () -> git.chexson.chexsonsaeutils.client.integration.jei.ProcessingScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.crafting.directprocessing.ProcessingScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.integration.directprocessing.ProcessingScenarios.verify(temporaryRoot)
        );
    }

    @Test
    @DisplayName("玩家模拟")
    void playerSimulation(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("玩家模拟",
                () -> git.chexson.chexsonsaeutils.parts.playersimulation.SimulationScenarios.verify(temporaryRoot)
        );
    }

    @Test
    @DisplayName("注册与配置")
    void registrationAndConfiguration(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("注册与配置",
                () -> git.chexson.chexsonsaeutils.block.controller.RegistrationScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.RegistrationScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.client.gui.config.RegistrationScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.client.gui.RegistrationScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.config.RegistrationScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.mixinplugin.RegistrationScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.pattern.replacement.RegistrationScenarios.verify(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.registration.RegistrationScenarios.verify(temporaryRoot)
        );
    }

}
