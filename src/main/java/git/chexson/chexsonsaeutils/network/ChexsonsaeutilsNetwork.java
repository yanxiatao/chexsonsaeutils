package git.chexson.chexsonsaeutils.network;

import git.chexson.chexsonsaeutils.network.custompatternencoder.CustomPatternEncoderUpdatePayload;
import git.chexson.chexsonsaeutils.network.custompatternencoder.CustomPatternSlotChangePacket;
import git.chexson.chexsonsaeutils.network.directprocessing.DirectProcessingJeiImportPayload;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixEntriesPayload;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixMatchesPayload;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixRealStacksPayload;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixSearchPayload;
import git.chexson.chexsonsaeutils.network.matrix.PatternMatrixVisibleRequestPayload;
import git.chexson.chexsonsaeutils.network.planning.PlanningOrderStatePayload;
import git.chexson.chexsonsaeutils.network.planning.PlanningOrderStateRequestPayload;
import git.chexson.chexsonsaeutils.network.planning.PlanningOrderUpdatePayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * network 侧的载荷注册宿主。
 *
 * <p>本 mod 的全部自定义网络载荷在这里逐枚挂到 NeoForge 的 payload registrar 上；
 * mod 入口只留一次挂接与一次下放调用，不再点名任何载荷类。
 * 注册使用各载荷公开的类型、编解码器与处理入口。
 */
public final class ChexsonsaeutilsNetwork {

    private ChexsonsaeutilsNetwork() {
    }

    /**
     * 把自定义载荷注册到服务端/客户端通道，包含精确终端会话与分片。
     *
     * @param event NeoForge 载荷注册事件
     */
    public static void registerPayloadHandlers(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(
                git.chexson.chexsonsaeutils.network.crafting.remote.RemotePreviewPayload.Request.TYPE,
                git.chexson.chexsonsaeutils.network.crafting.remote.RemotePreviewPayload.Request.CODEC,
                git.chexson.chexsonsaeutils.network.crafting.remote.RemotePreviewPayload.Request::handle);
        event.registrar("1").playToClient(
                git.chexson.chexsonsaeutils.network.crafting.remote.RemotePreviewPayload.Response.TYPE,
                git.chexson.chexsonsaeutils.network.crafting.remote.RemotePreviewPayload.Response.CODEC,
                git.chexson.chexsonsaeutils.network.crafting.remote.RemotePreviewPayload.Response::handle);
        var terminalRegistrar = event.registrar("1");
        terminalRegistrar.playToClient(git.chexson.chexsonsaeutils.network.crafting.WidePlanChunkPayload.TYPE,
                git.chexson.chexsonsaeutils.network.crafting.WidePlanChunkPayload.STREAM_CODEC,
                git.chexson.chexsonsaeutils.network.crafting.WidePlanChunkPayload::handle);
        terminalRegistrar.playToClient(git.chexson.chexsonsaeutils.network.crafting.ExactCraftingCpuPayload.TYPE,
                git.chexson.chexsonsaeutils.network.crafting.ExactCraftingCpuPayload.STREAM_CODEC,
                git.chexson.chexsonsaeutils.network.crafting.ExactCraftingCpuPayload::handle);
        terminalRegistrar.playToServer(git.chexson.chexsonsaeutils.network.crafting.WideCraftRequestPayload.TYPE,
                git.chexson.chexsonsaeutils.network.crafting.WideCraftRequestPayload.STREAM_CODEC,
                git.chexson.chexsonsaeutils.network.crafting.WideCraftRequestPayload::handle);
        terminalRegistrar.playToClient(git.chexson.chexsonsaeutils.network.crafting.CraftingAmountRestorePayload.TYPE,
                git.chexson.chexsonsaeutils.network.crafting.CraftingAmountRestorePayload.STREAM_CODEC,
                git.chexson.chexsonsaeutils.network.crafting.CraftingAmountRestorePayload::handle);
        terminalRegistrar.playToClient(git.chexson.chexsonsaeutils.network.terminal.TerminalChunkPayload.TYPE,
                git.chexson.chexsonsaeutils.network.terminal.TerminalChunkPayload.STREAM_CODEC,
                git.chexson.chexsonsaeutils.network.terminal.TerminalChunkPayload::handle);
        terminalRegistrar.playToServer(git.chexson.chexsonsaeutils.network.terminal.TerminalRequestPayload.TYPE,
                git.chexson.chexsonsaeutils.network.terminal.TerminalRequestPayload.STREAM_CODEC,
                git.chexson.chexsonsaeutils.network.terminal.TerminalRequestPayload::handle);
        terminalRegistrar.playToServer(git.chexson.chexsonsaeutils.network.terminal.TerminalInteractionPayload.TYPE,
                git.chexson.chexsonsaeutils.network.terminal.TerminalInteractionPayload.STREAM_CODEC,
                git.chexson.chexsonsaeutils.network.terminal.TerminalInteractionPayload::handle);
        event.registrar("1").playToServer(
                DirectProcessingJeiImportPayload.TYPE,
                DirectProcessingJeiImportPayload.STREAM_CODEC,
                DirectProcessingJeiImportPayload::handle
        );
        event.registrar("1").playToServer(
                CustomPatternSlotChangePacket.TYPE,
                CustomPatternSlotChangePacket.STREAM_CODEC,
                CustomPatternSlotChangePacket::handleOnServer
        );
        event.registrar("1").playToClient(
                CustomPatternEncoderUpdatePayload.TYPE,
                CustomPatternEncoderUpdatePayload.STREAM_CODEC,
                CustomPatternEncoderUpdatePayload::handle
        );
        event.registrar("1").playToServer(
                PlanningOrderStateRequestPayload.TYPE,
                PlanningOrderStateRequestPayload.STREAM_CODEC,
                PlanningOrderStateRequestPayload::handle
        );
        event.registrar("1").playToServer(
                PlanningOrderUpdatePayload.TYPE,
                PlanningOrderUpdatePayload.STREAM_CODEC,
                PlanningOrderUpdatePayload::handle
        );
        event.registrar("1").playToClient(
                PlanningOrderStatePayload.TYPE,
                PlanningOrderStatePayload.STREAM_CODEC,
                PlanningOrderStatePayload::handle
        );
        event.registrar("1").playToClient(
                git.chexson.chexsonsaeutils.network.directprocessing.rules.DirectProcessingPackRulesPayload.TYPE,
                git.chexson.chexsonsaeutils.network.directprocessing.rules.DirectProcessingPackRulesPayload.STREAM_CODEC,
                git.chexson.chexsonsaeutils.network.directprocessing.rules.DirectProcessingPackRulesPayload::handle
        );
        var matrixRegistrar = event.registrar("1");
        matrixRegistrar.playToServer(
                PatternMatrixSearchPayload.TYPE,
                PatternMatrixSearchPayload.STREAM_CODEC,
                PatternMatrixSearchPayload::handle
        );
        matrixRegistrar.playToServer(
                PatternMatrixVisibleRequestPayload.TYPE,
                PatternMatrixVisibleRequestPayload.STREAM_CODEC,
                PatternMatrixVisibleRequestPayload::handle
        );
        matrixRegistrar.playToClient(
                PatternMatrixEntriesPayload.TYPE,
                PatternMatrixEntriesPayload.STREAM_CODEC,
                PatternMatrixEntriesPayload::handle
        );
        matrixRegistrar.playToClient(
                PatternMatrixMatchesPayload.TYPE,
                PatternMatrixMatchesPayload.STREAM_CODEC,
                PatternMatrixMatchesPayload::handle
        );
        matrixRegistrar.playToClient(
                PatternMatrixRealStacksPayload.TYPE,
                PatternMatrixRealStacksPayload.STREAM_CODEC,
                PatternMatrixRealStacksPayload::handle
        );
    }
}
