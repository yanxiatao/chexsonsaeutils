package git.chexson.chexsonsaeutils.client.gui.implementations;

import appeng.client.gui.style.StyleManager;
import git.chexson.chexsonsaeutils.client.gui.matrix.GuiAssemblerMatrix;
import git.chexson.chexsonsaeutils.menu.implementations.ExtremeAssemblerMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 归墟界面：整块照抄 ExtendedAE 装配矩阵，九列四行的样板区、背景、搜索框与滚动条同位同尺寸。
 */
public class ExtremeAssemblerScreen extends GuiAssemblerMatrix<ExtremeAssemblerMenu> {

    private static final int MATRIX_COLUMNS = 9;
    private static final int MATRIX_ROWS = 4;
    /** 与移植件一致：格子区左上角 (8, 31)，一行 18 像素。 */
    private static final int MATRIX_LEFT = 8;
    private static final int MATRIX_TOP = 31;
    /** 样板计数占用的信息行，与移植件画线程数的位置相同。 */
    private static final String PATTERN_COUNT_TEXT = "pattern_count";

    public ExtremeAssemblerScreen(
            ExtremeAssemblerMenu menu,
            Inventory playerInventory,
            Component title
    ) {
        super(
                menu,
                playerInventory,
                title,
                StyleManager.loadStyleDoc("/screens/extreme_assembler.json"),
                menu.matrixLink(),
                MATRIX_COLUMNS,
                MATRIX_ROWS,
                MATRIX_LEFT,
                MATRIX_TOP,
                MatrixSearchMatchers.create()
        );
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        setTextContent(PATTERN_COUNT_TEXT, statusLine());
    }
}
