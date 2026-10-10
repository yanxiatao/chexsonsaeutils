package git.chexson.chexsonsaeutils.mixin.ae2.storage;

import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.StorageCell;
import appeng.me.storage.DriveWatcher;
import git.chexson.chexsonsaeutils.api.storage.ExactStorageActivity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** 精确扣料后通知驱动器单元状态变化，与原 long 提取一致。 */
@Mixin(value = DriveWatcher.class, remap = false)
public abstract class DriveExactStorageMixin implements ExactStorageActivity {
    @Shadow private CellState oldStatus;
    @Shadow @Final private Runnable activityCallback;
    @Shadow public abstract StorageCell getCell();
    @Override public void exactStorageChanged() {
        CellState status = getCell().getStatus();
        if (status != oldStatus) { activityCallback.run(); oldStatus = status; }
    }
}
