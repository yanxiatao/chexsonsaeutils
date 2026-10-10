package git.chexson.chexsonsaeutils.modid;

// MODID 的宿主：D1 笔1 把 mod 标识从入口类搬到这里，断开 root 包与各反向引文件的成环包对。
//
// 零出边纪律：本类不许 import 任何本仓包，也不许长出新成员——
// 一旦引入本仓依赖或注册把手，刚断掉的边就会以新的成环包对形式回来
// （判据＝.scratch/271-A-deps.py 结构尺＋RegistrationRosterGoldenTest 的 modid 段）。
public final class ModIds {

    // mod 标识；值逐字等于迁移前入口里的声明，与 mods.toml、@Mod 注解同串。
    public static final String MODID = "chexsonsaeutils";

    private ModIds() {
    }
}
