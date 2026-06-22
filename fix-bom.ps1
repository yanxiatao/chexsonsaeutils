$files = Get-ChildItem -Path "src\main\java" -Filter "*.java" -Recurse

foreach ($file in $files) {
    $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($file.FullName, $content, $utf8NoBom)
}

Write-Host "Fixed BOM in all Java files!"
