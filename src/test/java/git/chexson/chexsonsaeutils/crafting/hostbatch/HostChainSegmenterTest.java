package git.chexson.chexsonsaeutils.crafting.hostbatch;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostChainSegmenterTest {

    @Test
    void componentSiblingWithoutExactDependencyStaysInSeparateSegment() {
        AEItemKey version1 = componentKey(Items.AMETHYST_SHARD, "version-1");
        AEItemKey version2 = componentKey(Items.AMETHYST_SHARD, "version-2");
        AEItemKey version3 = componentKey(Items.AMETHYST_SHARD, "version-3");
        AEItemKey wrongVersion2 = componentKey(Items.AMETHYST_SHARD, "wrong-version-2");

        var segments = HostChainSegmenter.splitPerPatternFormalAggregationSegments(Map.of(
                version2, List.of(version1, AEItemKey.of(Items.GOLD_INGOT)),
                version3, List.of(version2, AEItemKey.of(Items.DIAMOND)),
                wrongVersion2, List.of(version1, AEItemKey.of(Items.REDSTONE))
        ));

        assertEquals(2, segments.size());
        assertTrue(segments.contains(Set.of(version2, version3)));
        assertTrue(segments.contains(Set.of(wrongVersion2)));
    }

    /**
     * 夹顶值就是顶：同键先记 {@code Long.MAX_VALUE} 再记 5，读数必须停在顶上而不是回绕成 4。
     * 该口按 {@code amount <= 0} 早退，负方向夹底那一半在这里走不到——算术契约（双向饱和）
     * 由 {@code bigint/ClampedMathTest} 钉，本条只钉这一个调用点的替换结果。
     */
    @Test
    void mergeStackPinsSaturatedTotalAtLongTopInsteadOfWrappingAround() {
        AEKey shard = AEItemKey.of(Items.AMETHYST_SHARD);
        Map<AEKey, Long> target = new LinkedHashMap<>();

        HostChainSegmenter.mergeStack(target, shard, Long.MAX_VALUE);
        HostChainSegmenter.mergeStack(target, shard, 5L);

        assertEquals(Long.MAX_VALUE, target.get(shard).longValue(),
                () -> "saturated merge must stay on the ceiling, path=HostChainSegmenter.mergeStack");
    }

    private static AEItemKey componentKey(Item item, String value) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(value));
        return AEItemKey.of(stack);
    }
}
