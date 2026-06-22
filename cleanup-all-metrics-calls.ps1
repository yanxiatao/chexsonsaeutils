$srcDir = "E:\IdeaProjects\chexsonsaeutils-migration-1-21-neoforge\src"

Get-ChildItem -Path $srcDir -Filter "*.java" -Recurse | ForEach-Object {
    $file = $_.FullName
    $content = Get-Content $file -Raw -Encoding UTF8
    $original = $content

    # Remove all metrics.record* calls
    $content = $content -replace '\s*metrics\.record[^;]+;', ''

    if ($content -ne $original) {
        [System.IO.File]::WriteAllText($file, $content, [System.Text.UTF8Encoding]::new($false))
        Write-Host "Cleaned: $($_.Name)"
    }
}

Write-Host "Done cleaning all metrics calls"
