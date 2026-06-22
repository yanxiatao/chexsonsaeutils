$replacements = @{
    'ProcessingSlotTagService' = 'ProcessingSlotTagExpander'
    'EnhancedCraftingStatusService' = 'CraftingStatusEnhancer'
    'ProcessingExecutionBudgetController' = 'ProcessingExecutionBudget'
    'ScaledCraftingPatternEligibilityService' = 'ScaledCraftingPatternAnalyzer'
    'MachineRecipeDiscoveryService' = 'MachineRecipeIndexBuilder'
    'CraftingContinuationStatusService' = 'CraftingContinuationTracker'
}

$files = Get-ChildItem -Path "src\main\java" -Filter "*.java" -Recurse

foreach ($file in $files) {
    $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8
    $modified = $false

    foreach ($old in $replacements.Keys) {
        $new = $replacements[$old]
        if ($content -match [regex]::Escape($old)) {
            $content = $content -replace [regex]::Escape($old), $new
            $modified = $true
        }
    }

    if ($modified) {
        Set-Content -Path $file.FullName -Value $content -Encoding UTF8 -NoNewline
        Write-Host "Updated: $($file.FullName)"
    }
}

Write-Host "Replacement complete!"
