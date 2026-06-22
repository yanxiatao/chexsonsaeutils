$file = "E:\IdeaProjects\chexsonsaeutils-migration-1-21-neoforge\src\main\java\git\chexson\chexsonsaeutils\blockentity\crafting\AbstractHighCapacityCraftingHostBlockEntity.java"

$content = Get-Content $file -Raw -Encoding UTF8

# Remove metrics counters
$content = $content -replace '\s*maxExecutableRunsFallbackCount\+\+;', ''
$content = $content -replace '\s*templatedDispatchHitCount\+\+;', ''

# Rename ForTest method call
$content = $content -replace '\.clearWithoutDirtyMarksForTest\(\)', '.clearWithoutDirtyMarks()'

[System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))

Write-Host "Cleaned up remaining metrics and renamed ForTest method"
