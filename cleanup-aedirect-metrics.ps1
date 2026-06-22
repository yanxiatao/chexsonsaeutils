$file = "E:\IdeaProjects\chexsonsaeutils-migration-1-21-neoforge\src\main\java\git\chexson\chexsonsaeutils\blockentity\directprocessing\AEDirectProcessingMachineBlockEntity.java"

$content = Get-Content $file -Raw -Encoding UTF8

# Remove all metrics.record* calls
$content = $content -replace '\s*metrics\.record[^;]+;', ''

[System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))

Write-Host "Removed metrics calls from AEDirectProcessingMachineBlockEntity"
