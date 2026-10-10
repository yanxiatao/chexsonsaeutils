package git.chexson.chexsonsaeutils.parts.playersimulation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimulationClickFacesTest {
    private final BlockPos target = new BlockPos(12,34,56);
    private final BlockHitResult detected = new BlockHitResult(new Vec3(12.5,34.5,56),Direction.NORTH,target,false);
    @Test void automaticKeepsOriginalHitAndAllSixFacesKeepTarget() {
        assertSame(detected,SimulationClickFaces.hit(detected,Shapes.block(),SimulationSettings.ClickFace.AUTO));
        for (var option : SimulationSettings.ClickFace.values()) {
            if (option == SimulationSettings.ClickFace.AUTO) continue;
            var direction = option.resolve(Direction.NORTH);
            var hit = SimulationClickFaces.hit(detected,Shapes.block(),option);
            assertEquals(direction,hit.getDirection());assertEquals(target,hit.getBlockPos());
            assertEquals(Vec3.atCenterOf(target).add(Vec3.atLowerCornerOf(direction.getNormal()).scale(0.5)),hit.getLocation());
        }
    }
    @Test void slabAndSeparatedVoxelShapesUseActualExteriorFace() {
        var slab = Shapes.box(0,0,0,1,0.5,1);
        assertEquals(new Vec3(12.5,34.5,56.5),SimulationClickFaces.hit(detected,slab,SimulationSettings.ClickFace.UP).getLocation());
        assertEquals(new Vec3(12.5,34,56.5),SimulationClickFaces.hit(detected,slab,SimulationSettings.ClickFace.DOWN).getLocation());
        var pieces = Shapes.or(Shapes.box(0,0,0,0.25,0.5,0.25),Shapes.box(0.75,0,0.75,1,1,1));
        assertEquals(new Vec3(12.875,35,56.875),SimulationClickFaces.hit(detected,pieces,SimulationSettings.ClickFace.UP).getLocation());
        assertEquals(new Vec3(12,34.25,56.125),SimulationClickFaces.hit(detected,pieces,SimulationSettings.ClickFace.WEST).getLocation());
    }
    @Test void fixedFaceWithEmptyShapeFailsExplicitly() {
        assertThrows(IllegalArgumentException.class,() -> SimulationClickFaces.hit(detected,Shapes.empty(),SimulationSettings.ClickFace.UP));
    }
}
