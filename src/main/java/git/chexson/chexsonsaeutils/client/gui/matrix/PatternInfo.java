package git.chexson.chexsonsaeutils.client.gui.matrix;

import git.chexson.chexsonsaeutils.menu.matrix.PatternMatrixClientView;

import java.util.ArrayList;
import java.util.List;

/**
 * 装配矩阵的行切分：ExtendedAE {@code GuiAssemblerMatrix.PatternInfo} 的移植件。
 *
 * <p>把整份样板库存按每行 {@code columns} 格切成固定的行序列，格子的屏幕位置只由
 * 全局槽号决定，所以滚动的是行、对应的始终是真槽位；空位也占一格，放样直接落在空行上。
 * 参与滚动的只是其中一段前缀，见 {@link #listRows(PatternMatrixClientView)}。</p>
 */
public final class PatternInfo {

    private final List<PatternRow> internalRows = new ArrayList<>();

    public PatternInfo(int totalSlots, int columns) {
        int left = Math.max(0, totalSlots);
        if (columns <= 0 || left <= 0) {
            return;
        }
        int offset = 0;
        do {
            this.internalRows.add(new PatternRow(offset, Math.min(left, columns)));
            left -= columns;
            offset += columns;
        } while (left > 0);
    }

    /**
     * 列出参与滚动的行：搜索时只留命中行；平时列到"最后一个有内容的行 + 一条空行"为止。
     *
     * <p>不把整份容量都排进滚动条：库存上限一万六千格，那样得滚上千条空行才到头。
     * 截断只砍尾部，行首偏移仍由槽号区间唯一决定，所以格子落在的真槽位不受影响。</p>
     */
    public List<PatternRow> listRows(PatternMatrixClientView view) {
        List<PatternRow> listed = new ArrayList<>();
        if (view.filterActive()) {
            for (PatternRow row : internalRows) {
                if (row.matchesSearch(view)) {
                    listed.add(row);
                }
            }
            return listed;
        }
        int lastContent = -1;
        for (int i = 0; i < internalRows.size(); i++) {
            if (internalRows.get(i).hasContent(view)) {
                lastContent = i;
            }
        }
        int through = lastContent < 0 ? 0 : Math.min(internalRows.size(), lastContent + 1);
        for (int i = 0; i <= through && i < internalRows.size(); i++) {
            listed.add(internalRows.get(i));
        }
        return listed;
    }

    /** 完整的行序列（含尾部空行），仅供同包测试核对切片；生产侧一律走 {@link #listRows}。 */
    List<PatternRow> internalRows() {
        return internalRows;
    }

    int rowCount() {
        return internalRows.size();
    }
}
