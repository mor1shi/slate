param([switch]$Lan)
# Запуск сервера Slate в Windows PowerShell.
#   .\run-server.ps1          — только этот компьютер (127.0.0.1)
#   .\run-server.ps1 -Lan     — доступ из локальной сети (0.0.0.0)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (Test-Path .env) {
    Get-Content .env | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
        }
    }
}
if ($Lan) { $env:WS_HOST = "0.0.0.0" }
if (-not $env:WS_HOST) { $env:WS_HOST = "127.0.0.1" }
if (-not $env:WS_PORT) { $env:WS_PORT = "8765" }

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

$java = Get-Java 21
if (-not $java) {
    Write-Error "Не найден JDK 21+. Установите: winget install EclipseAdoptium.Temurin.21.JDK"
    exit 1
}
$env:JAVA_HOME = Split-Path (Split-Path $java -Parent) -Parent

$mongoOpen = Test-NetConnection -ComputerName 127.0.0.1 -Port 27017 -InformationLevel Quiet -WarningAction SilentlyContinue
if (-not $mongoOpen) {
    Write-Error "MongoDB не отвечает на 127.0.0.1:27017. Установите MongoDB Community Server как службу Windows."
    exit 1
}

$jar = "java-collab\server\target\canvas-collab-server-1.0.0.jar"
if (-not (Test-Path $jar)) {
    Write-Host "Сборка сервера…"
    & .\mvnw.cmd -q -f java-collab/server/pom.xml package
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host "Сервер: ws://$($env:WS_HOST):$($env:WS_PORT)"
& $java -jar $jar
