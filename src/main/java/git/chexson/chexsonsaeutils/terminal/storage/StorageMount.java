package git.chexson.chexsonsaeutils.terminal.storage;

import appeng.api.storage.MEStorage;
import java.util.Objects;

/** 实际贡献和其可见视图；identity 用于去除同一贡献的重复发现路径。 */
public record StorageMount(Object identity, MEStorage original, MEStorage visible) {
    public StorageMount {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(original);
        Objects.requireNonNull(visible);
    }
}
