package git.chexson.chexsonsaeutils.parts.playersimulation;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.Comparator;

/** 将固定点击面转换为真实轮廓表面上的命中点，支持台阶和多体素方块。 */
public final class SimulationClickFaces {
    private SimulationClickFaces() {}
    /** 自动模式保留原命中；固定模式选择该朝向最外侧体素面的中心。 */
    public static BlockHitResult hit(BlockHitResult detected,VoxelShape shape,SimulationSettings.ClickFace option) {
        if (option == SimulationSettings.ClickFace.AUTO) return detected;
        Direction direction = option.resolve(detected.getDirection());
        AABB box = shape.toAabbs().stream().max(Comparator.comparingDouble(bounds ->
                coordinate(bounds,direction)*(direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1)))
                .orElseThrow(() -> new IllegalArgumentException("命中方块的轮廓为空"));
        Vec3 center = box.getCenter();
        Vec3 local = switch (direction.getAxis()) {
            case X -> new Vec3(coordinate(box,direction),center.y,center.z);
            case Y -> new Vec3(center.x,coordinate(box,direction),center.z);
            case Z -> new Vec3(center.x,center.y,coordinate(box,direction));
        };
        return new BlockHitResult(local.add(Vec3.atLowerCornerOf(detected.getBlockPos())),direction,detected.getBlockPos(),false);
    }
    private static double coordinate(AABB box,Direction direction) {
        boolean positive = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        return switch (direction.getAxis()) {
            case X -> positive ? box.maxX : box.minX;
            case Y -> positive ? box.maxY : box.minY;
            case Z -> positive ? box.maxZ : box.minZ;
        };
    }
}
