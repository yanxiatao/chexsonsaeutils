$ErrorActionPreference = 'Stop'

$processes = Get-Process | Where-Object { -not [string]::IsNullOrWhiteSpace($_.MainWindowTitle) }
$processes |
    Select-Object ProcessName, Id, MainWindowTitle |
    Sort-Object ProcessName, Id |
    Format-Table -AutoSize
