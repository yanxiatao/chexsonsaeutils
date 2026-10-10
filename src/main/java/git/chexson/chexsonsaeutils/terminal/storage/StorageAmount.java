package git.chexson.chexsonsaeutils.terminal.storage;

import java.math.BigInteger;
import java.util.Objects;

/** 库存显示语义；无限资源与无容量上限的有限库存必须分开。 */
public record StorageAmount(BigInteger value, boolean unbounded) {
    public static final StorageAmount ZERO = new StorageAmount(BigInteger.ZERO, false);
    public StorageAmount {
        Objects.requireNonNull(value, "数量");
        if (value.signum() < 0 || (unbounded && value.signum() != 0))
            throw new IllegalArgumentException("库存数量语义错误");
    }
    public static StorageAmount finite(BigInteger value) { return new StorageAmount(value, false); }
    public static StorageAmount infinite() { return new StorageAmount(BigInteger.ZERO, true); }
    public StorageAmount add(StorageAmount other) {
        return unbounded || other.unbounded ? infinite() : finite(value.add(other.value));
    }
    /** 按物品数量比较，无限资源置于有限量之后。 */
    public int compareTo(StorageAmount other) {
        return unbounded == other.unbounded ? value.compareTo(other.value) : Boolean.compare(unbounded, other.unbounded);
    }
}
