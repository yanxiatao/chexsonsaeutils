$file = "E:\IdeaProjects\chexsonsaeutils-migration-1-21-neoforge\src\main\java\git\chexson\chexsonsaeutils\blockentity\crafting\AbstractHighCapacityCraftingHostBlockEntity.java"

$content = Get-Content $file -Raw -Encoding UTF8

# Remove increments
$content = $content -replace '\s*virtualScaledPatternHitCount\+\+;', ''
$content = $content -replace '\s*virtualScaledPatternFallbackCount\+\+;', ''

[System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))

Write-Host "Removed virtual scaled pattern counters"
