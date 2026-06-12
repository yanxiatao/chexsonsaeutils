package git.chexson.chexsonsaeutils.crafting.formalmachine;

public record FormalMachineFastPathResult(
        FormalMachineFastPathDisposition disposition,
        int acceptedExecutions
) {
    public static FormalMachineFastPathResult accepted(int acceptedExecutions) {
        return new FormalMachineFastPathResult(FormalMachineFastPathDisposition.ACCEPTED, Math.max(0, acceptedExecutions));
    }

    public static FormalMachineFastPathResult rejected() {
        return new FormalMachineFastPathResult(FormalMachineFastPathDisposition.REJECTED, 0);
    }

    public static FormalMachineFastPathResult fallback() {
        return new FormalMachineFastPathResult(FormalMachineFastPathDisposition.FALLBACK, 0);
    }
}
