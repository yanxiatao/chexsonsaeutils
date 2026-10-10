package git.chexson.chexsonsaeutils.crafting.solve;

/**
 * 确认菜单上的规划归属（@GuiSync 同步）：客户端标题从菜单读，不依赖 summary
 * 对象与 plan packet——其他规划 mod 接管 planJob、自建 summary、自送 packet
 * 时依然可用。
 */
public interface MenuProvenanceHost {

    long chexsonsaeutils$menuProvenanceMillis();

    String chexsonsaeutils$menuProvenanceSolver();

    void chexsonsaeutils$setMenuProvenance(long millis, String solver);
}
