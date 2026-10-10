package git.chexson.chexsonsaeutils.crafting.remote;
import appeng.api.stacks.AEKey;
import net.minecraft.core.HolderLookup;
/** 装配期注入真实物品的编码行为，运行层不回指注册类。 */
@FunctionalInterface
public interface RemotePatternEncoding {
    /** 用指定世界注册表构造可重新解码的代理样板。 */
    RemotePattern encode(AEKey output, HolderLookup.Provider registries);
}
