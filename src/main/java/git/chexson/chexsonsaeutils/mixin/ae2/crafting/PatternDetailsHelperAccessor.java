package git.chexson.chexsonsaeutils.mixin.ae2.crafting;

import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.crafting.PatternDetailsHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(value = PatternDetailsHelper.class, remap = false)
public interface PatternDetailsHelperAccessor {
    @Accessor("DECODERS")
    static List<IPatternDetailsDecoder> chexsonsaeutils$getDecoders() {
        throw new AssertionError();
    }
}
