$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Src = Join-Path $Root 'app\src\main\java\com\research\sensormax'

Write-Host 'SensorMax merge repair' -ForegroundColor Cyan

# Migrate the old project-facing compatibility parser out of the runtime source set.
# SensorMaxExperimentFile.kt is authoritative; keep a backup outside app/src before removal.
$oldParser = Join-Path $Src 'ExperimentFileCompatibility.kt'
$newParser = Join-Path $Src 'SensorMaxExperimentFile.kt'
if (Test-Path $oldParser) {
    if (-not (Test-Path $newParser)) {
        throw 'SensorMaxExperimentFile.kt is missing; refusing to remove ExperimentFileCompatibility.kt.'
    }
    $backupDir = Join-Path $Root 'reference\migration_backups'
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    $backup = Join-Path $backupDir 'ExperimentFileCompatibility.kt'
    Copy-Item $oldParser $backup -Force
    Remove-Item $oldParser -Force
    Write-Host 'Removed stale ExperimentFileCompatibility.kt; SensorMaxExperimentFile.kt is authoritative.' -ForegroundColor Green
}

# A stale local file can still exist after older integrations. Its duplicate
# declarations are excluded by app/build.gradle, but removing it keeps the tree clean.
$legacy = Join-Path $Src 'PhyphoxBufferModel.kt'
if (Test-Path $legacy) {
    $text = Get-Content $legacy -Raw
    $symbols = @(
        'class SignalBuffer',
        'enum class AnalysisInputPolicy',
        'data class AnalysisInput',
        'class AnalysisGate',
        'class ExperimentClock',
        'data class ClockEvent'
    )
    $matches = ($symbols | Where-Object { $text.Contains($_) }).Count
    if ($matches -ge 6) {
        $backupDir = Join-Path $Root 'reference\migration_backups'
        New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
        Copy-Item $legacy (Join-Path $backupDir 'PhyphoxBufferModel.kt') -Force
        Remove-Item $legacy -Force
        Write-Host 'Removed stale duplicate experiment-buffer model; backup kept outside runtime source.' -ForegroundColor Green
    } else {
        Write-Warning 'Legacy file exists but does not match the duplicate model signature. Review it manually.'
    }
}

# MainActivity must use the actual TableLayout from activity_main.xml.
$main = Join-Path $Src 'MainActivity.kt'
$text = Get-Content $main -Raw
$text = $text -replace '(?m)^\s*private lateinit var gridSecondaryTelemetry: View\s*\r?\n', ''
if (-not $text.Contains('private lateinit var gridSecondaryTelemetry: TableLayout')) {
    throw 'MainActivity is missing the authoritative TableLayout gridSecondaryTelemetry declaration.'
}
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($main, $text, $utf8NoBom)

Write-Host 'Merge repair complete.' -ForegroundColor Green
Write-Host 'Next commands:'
Write-Host '  gradlew.bat --stop'
Write-Host '  gradlew.bat clean assembleDebug --stacktrace'
