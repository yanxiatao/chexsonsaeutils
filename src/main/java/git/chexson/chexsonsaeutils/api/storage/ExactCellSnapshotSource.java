package git.chexson.chexsonsaeutils.api.storage;

/**
 * 存储单元的公开精确计数入口，供终端及第三方显示集成共用。
 * 不依赖可选模组，不创建存储、不标脏、不执行存取；调用者负责网格可见性投影。
 */
public interface ExactCellSnapshotSource {
    /** 原子读取键、有限精确数量和修订号；返回值不随后续存取改变。 */
    ExactCellSnapshot readExactSnapshot();
}
