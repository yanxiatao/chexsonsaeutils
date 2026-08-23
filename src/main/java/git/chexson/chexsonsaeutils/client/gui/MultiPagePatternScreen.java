package git.chexson.chexsonsaeutils.client.gui;

/**
 * 多页样板供应器屏幕标记接口：向布局 mixin 暴露当前渲染页。
 * <p>
 * 动机：照抄 ExtendedAE_Plus 的多页摆位机制——网格布局 mixin 拦截时需要知道
 * "当前页"；页号由菜单 @GuiSync 服务端权威同步，经本接口从 Screen 读取，
 * 避免布局逻辑耦合具体 Screen 子类。
 */
public interface MultiPagePatternScreen {
    /**
     * @return 当前渲染的样板页（0 起，来自菜单同步值）
     */
    int chexsonsaeutils$getCurrentPage();
}
