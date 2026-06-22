$file = "E:\IdeaProjects\chexsonsaeutils-migration-1-21-neoforge\src\main\java\git\chexson\chexsonsaeutils\blockentity\crafting\AbstractHighCapacityCraftingHostBlockEntity.java"

$content = Get-Content $file -Raw -Encoding UTF8

# Find all increment patterns and remove them
$content = $content -replace '\s*\w+Count\+\+;', ''
$content = $content -replace '\s*\w+Ticks\+\+;', ''
$content = $content -replace '\s*\w+Max\s*=\s*Math\.max\([^;]+\);', ''

[System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))

Write-Host "Removed all remaining metrics patterns"
