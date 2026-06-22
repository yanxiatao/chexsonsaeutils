$file = "E:\IdeaProjects\chexsonsaeutils-migration-1-21-neoforge\src\main\java\git\chexson\chexsonsaeutils\blockentity\crafting\AbstractHighCapacityCraftingHostBlockEntity.java"

$content = Get-Content $file -Raw -Encoding UTF8

# Remove all remaining metrics counter increments (match any pattern)
$patterns = @(
    'pendingCompletionTicks',
    'peakRunningUniquePatterns',
    'formalStatusHeartbeatCount'
)

foreach ($pattern in $patterns) {
    $content = $content -replace "\s*$pattern\+\+;", ''
    $content = $content -replace "\s*$pattern\s*=\s*Math\.max\([^;]+;", ''
}

[System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))

Write-Host "Removed all remaining metrics"
