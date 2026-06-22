$file = "E:\IdeaProjects\chexsonsaeutils-migration-1-21-neoforge\src\main\java\git\chexson\chexsonsaeutils\blockentity\crafting\AbstractHighCapacityCraftingHostBlockEntity.java"

$content = Get-Content $file -Raw -Encoding UTF8

# Remove metrics counter increments
$content = $content -replace '\s*templatedCompletionHitCount\+\+;', ''
$content = $content -replace '\s*templatedCompletionSavedExecutions \+= [^;]+;', ''
$content = $content -replace '\s*batchedAeReturnCount\+\+;', ''
$content = $content -replace '\s*aeReturnRetryCount\+\+;', ''

[System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))

Write-Host "Removed remaining metrics counters"
