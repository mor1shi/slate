# Запуск клиента Slate в Windows PowerShell.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (Test-Path .env) {
    Get-Content .env | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
        }
    }
}

function Get-Java([int]$minMajor) {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\java.exe") }
    if (Test-Path "$env:ProgramFiles\Eclipse Adoptium") {
        $candidates += Get-ChildItem "$env:ProgramFiles\Eclipse Adoptium" -Directory |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName "bin\java.exe" }
    }
    if (Test-Path "$env:ProgramFiles\Java") {
        $candidates += Get-ChildItem "$env:ProgramFiles\Java" -Directory |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName "bin\java.exe" }
    }
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($cmd) { $candidates += $cmd.Source }

    foreach ($java in $candidates) {
        if (-not (Test-Path $java)) { continue }
        $line = (& $java -version 2>&1 | Select-Object -First 1) -join ""
        if ($line -match 'version "(\d+)') {
            if ([int]$matches[1] -ge $minMajor) { return $java }
        }
    }
    return $null
}

$java = Get-Java 17
if (-not $java) {
    Write-Error "Не найден JDK 17+. Установите: winget install EclipseAdoptium.Temurin.17.JDK"
    exit 1
}
$env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent

Write-Host "Клиент Slate (JDK: $env:JAVA_HOME)"
& .\mvnw.cmd -q javafx:run
