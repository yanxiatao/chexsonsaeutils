package git.chexson.chexsonsaeutils;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

/** 同机性能度量统一入口，仍按 footprint 标签显式启用。 */
@Tag("footprint")
final class PerformanceCategoryTest {
    @Test
    void measureExistingWorkloads(@TempDir Path temporaryRoot) {
        org.junit.jupiter.api.Assertions.assertAll("按需性能度量",
                () -> git.chexson.chexsonsaeutils.crafting.graphplan.PlanningScenarios.measure(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.crafting.graphplan.solve.ClosureScenarios.measure(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.crafting.numeric.NumericScenarios.measure(temporaryRoot),
                () -> git.chexson.chexsonsaeutils.crafting.status.DisplayScenarios.measure(temporaryRoot)
        );
    }
}
