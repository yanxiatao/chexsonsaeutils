package git.chexson.chexsonsaeutils.client.gui.matrix;

/** 与 ExtendedAE 高级样板管理终端一致的三种搜索范围。 */
public enum PatternSearchMode {
    INPUT, OUTPUT, INPUT_OUTPUT;

    public boolean includesInputs() { return this != OUTPUT; }
    public boolean includesOutputs() { return this != INPUT; }
    public PatternSearchMode next() { return values()[(ordinal() + 1) % values().length]; }
}
