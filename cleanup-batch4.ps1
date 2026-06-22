$file = "E:\IdeaProjects\chexsonsaeutils-migration-1-21-neoforge\src\main\java\git\chexson\chexsonsaeutils\blockentity\crafting\AbstractHighCapacityCraftingHostBlockEntity.java"

$content = Get-Content $file -Raw -Encoding UTF8

# Remove Math.max assignments
$content = $content -replace '\s*largestVirtualPatternMultiplier\s*=\s*Math\.max\([^;]+\);', ''

# Remove if blocks checking virtualScaledPatternLogicalExecutionsSaved
$content = $content -replace '\s*if\s*\(\s*virtualScaledPatternLogicalExecutionsSaved\s*>\s*Long\.MAX_VALUE\s*-\s*logicalExecutionsSaved\s*\)\s*\{[^}]*\}', ''

[System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))

Write-Host "Removed remaining metrics code"
