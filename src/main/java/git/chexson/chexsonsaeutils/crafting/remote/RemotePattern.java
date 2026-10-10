package git.chexson.chexsonsaeutils.crafting.remote;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;

/** 接收侧的无输入合成能力；相同输出的多个来源共享身份，来源由接单路由决定。 */
public interface RemotePattern extends IPatternDetails {
    /** 真实目标键；仅此产物跨网，不宣告来源配方的副产物。 */
    AEKey output();
}
