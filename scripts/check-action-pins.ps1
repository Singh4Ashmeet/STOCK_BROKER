#!/usr/bin/env pwsh
# scripts/check-action-pins.ps1 - Verify all GitHub Actions are pinned by commit SHA
# Fails if any uses: in .github/workflows (excluding local ./ paths) is not @<40 hex>

param(
    [string]$WorkflowDir = ".github/workflows"
)

$script:failCount = 0

function Log-Pass { Write-Host "[PASS] $($args[0])" -ForegroundColor Green }
function Log-Fail { Write-Host "[FAIL] $($args[0])" -ForegroundColor Red; $script:failCount++ }
function Log-Info { Write-Host "[INFO] $($args[0])" -ForegroundColor Yellow }

if (-not (Test-Path $WorkflowDir)) {
    Log-Fail "Workflow directory $WorkflowDir not found"
    exit 1
}

Log-Info "Checking GitHub Action pins in $WorkflowDir..."

$usesLines = Select-String -Path "$WorkflowDir\*.yml" -Pattern "^\s*(-?\s*)?uses:" -AllMatches

foreach ($match in $usesLines) {
    $file = $match.Path
    $line = $match.Line
    
    # Extract the uses value (everything after "uses:")
    $usesLine = $line -replace '^\s*(-?\s*)?uses:\s*', '' -replace '\s*#.*$', '' -replace '\s*$', ''
    
    # Skip local actions
    if ($usesLine -match '^\./') {
        continue
    }
    
    # Extract action and ref
    if ($usesLine -match '@') {
        $parts = $usesLine -split '@', 2
        $action = $parts[0].Trim()
        $ref = $parts[1].Trim()
        
        # Check if ref is a 40-character hex SHA
        if ($ref -match '^[a-f0-9]{40}$') {
            Log-Pass "${file}: ${action}@${ref} (pinned by SHA)"
        } else {
            Log-Fail "${file}: ${action}@${ref} (NOT pinned by 40-char SHA)"
        }
    } else {
        Log-Fail "${file}: ${usesLine} (no @ ref at all)"
    }
}

if ($script:failCount -eq 0) {
    Log-Pass "All GitHub Actions are pinned by commit SHA"
    exit 0
} else {
    Log-Fail "Found $script:failCount unpinned action(s)"
    exit 1
}