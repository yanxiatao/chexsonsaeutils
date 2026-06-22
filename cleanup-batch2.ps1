$file = "E:\IdeaProjects\chexsonsaeutils-migration-1-21-neoforge\src\main\java\git\chexson\chexsonsaeutils\blockentity\crafting\AbstractHighCapacityCraftingHostBlockEntity.java"

$content = Get-Content $file -Raw -Encoding UTF8

# Remove all metrics patterns
$patterns = @(
    'virtualScaledPatternLogicalExecutionsSaved',
    'scaledPatternNbtRestoreCount'
)

foreach ($pattern in $patterns) {
    # Remove increment
    $content = $content -replace "\s*$pattern\+\+;", ''
    # Remove += operations
    $content = $content -replace "\s*$pattern\s*\+=\s*[^;]+;", ''
    # Remove = Math.max operations
    $content = $content -replace "\s*$pattern\s*=\s*Math\.max\([^;]+\);", ''
}

[System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))

Write-Host "Removed remaining metrics"
