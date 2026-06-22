$file = "E:\IdeaProjects\chexsonsaeutils-migration-1-21-neoforge\src\main\java\git\chexson\chexsonsaeutils\blockentity\crafting\AbstractHighCapacityCraftingHostBlockEntity.java"

$content = Get-Content $file -Raw -Encoding UTF8

# Remove specific assignments
$content = $content -replace '\s*virtualScaledPatternLogicalExecutionsSaved\s*=\s*Long\.MAX_VALUE;', ''

[System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))

Write-Host "Removed virtualScaledPatternLogicalExecutionsSaved assignments"
