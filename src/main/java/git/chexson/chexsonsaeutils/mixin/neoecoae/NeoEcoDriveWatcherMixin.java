package git.chexson.chexsonsaeutils.mixin.neoecoae;

import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.StorageCell;
import appeng.me.storage.DriveWatcher;
import git.chexson.chexsonsaeutils.integration.neoecoae.NeoEcoExactStorageActivity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** 精确插入保持 AE2 驱动器状态灯及活动回调与普通插入一致。 */
@Mixin(value = DriveWatcher.class, remap = false)
public abstract class NeoEcoDriveWatcherMixin implements NeoEcoExactStorageActivity {
    @Shadow private CellState oldStatus;
    @Shadow @Final private Runnable activityCallback;
    @Shadow public abstract StorageCell getCell();

    @Override public void exactStorageInserted() {
        CellState status = getCell().getStatus();
        if (status != oldStatus) {
            activityCallback.run();
            oldStatus = status;
        }
    }
}
