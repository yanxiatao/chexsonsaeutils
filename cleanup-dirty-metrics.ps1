$file = "E:\IdeaProjects\chexsonsaeutils-migration-1-21-neoforge\src\main\java\git\chexson\chexsonsaeutils\blockentity\crafting\AbstractHighCapacityCraftingHostBlockEntity.java"

$content = Get-Content $file -Raw -Encoding UTF8

# Remove dirtyRefresh metrics
$content = $content -replace '\s*dirty\w+\s*\+=\s*[^;]+;', ''

[System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))

Write-Host "Removed dirty metrics"
