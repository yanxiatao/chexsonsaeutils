package git.chexson.chexsonsaeutils.mixin.ae2.menu;

import appeng.core.network.ClientboundPacket;
import appeng.core.network.clientbound.PatternAccessTerminalPacket;
import appeng.menu.AEBaseMenu;
import git.chexson.chexsonsaeutils.network.patternaccess.PatternAccessPacketBatch;
import git.chexson.chexsonsaeutils.network.patternaccess.PatternAccessPacketBatchImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在菜单真正发送的公共入口拆分，覆盖原生和扩展菜单的全量、增量同步。 */
@Mixin(value = AEBaseMenu.class, remap = false)
public abstract class PatternAccessMenuPacketBatchMixin {
    @Unique
    private static final PatternAccessPacketBatch chexsonsaeutils$patternBatches = new PatternAccessPacketBatchImpl();
    @Shadow(remap = false) public abstract Player getPlayer();

    @Inject(method = "sendPacketToClient", at = @At("HEAD"), cancellable = true, remap = false)
    private void chexsonsaeutils$sendPatternBatches(ClientboundPacket packet, CallbackInfo ci) {
        if (!(packet instanceof PatternAccessTerminalPacket patterns)
                || patterns.slots().size() <= PatternAccessPacketBatchImpl.SLOTS_PER_PACKET
                || !(getPlayer() instanceof ServerPlayer player)) return;
        var batches = chexsonsaeutils$patternBatches.split(patterns);
        com.mojang.logging.LogUtils.getLogger().debug("拆分样板终端同步：inventory={} slots={} packets={} full={}",
                patterns.inventoryId(), patterns.slots().size(), batches.size(), patterns.fullUpdate());
        for (var batch : batches) player.connection.send(batch);
        ci.cancel();
    }
}
