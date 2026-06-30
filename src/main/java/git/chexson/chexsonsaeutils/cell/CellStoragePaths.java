package git.chexson.chexsonsaeutils.cell;

import java.nio.file.Path;
import java.util.UUID;

public final class CellStoragePaths {

    private static final String CELL_DIR = "data/chexsonsaeutils/cells";

    private CellStoragePaths() {
    }

    public static Path getCellDir(Path worldRoot) {
        return worldRoot.resolve(CELL_DIR);
    }

    public static Path getNbtFile(Path worldRoot, UUID uuid) {
        return getCellDir(worldRoot).resolve(uuid + ".nbt");
    }
}
