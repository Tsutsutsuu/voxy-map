# Run the NeoForge 1.21.1 in-game smoke test (mod/tools/selftest/SelfTest.java) once.
#
# Responsibilities:
#   1. build (or reuse) a test jar that contains the temporary SelfTest harness,
#   2. swap the instance's voxy-map jar for it (always restored afterwards),
#   3. launch the Axolotl NeoForge instance directly via launch-direct.ps1 with
#      -Dvoxymap.selftest=true and optional extra JVM args,
#   4. wait for the harness to quit, collect screenshots + log excerpts under tools/logs/neoforge-selftest-*,
#   5. restore mods and print PASS/FAIL.
#
# The harness is TEMPORARY source: it must be copied to mod/src/main/java/com/voxymap/SelfTest.java and
# hooked in VoxyMod.clientTick (see mod/tools/README.md). This script checks that and refuses to run
# when the hook is missing, so a production jar can never be tested by accident.
#
# Usage:
#   pwsh -File mod/tools/run-selftest.ps1 -Build
#   pwsh -File mod/tools/run-selftest.ps1 -World '新的世界 (1)' -ExtraJvmArgs '-Dvoxymap.debugFallback=true'
param(
    [string]$Instance = 'D:\Minecraft\AxolotlLauncher\Data\profiles\1.21.1-NeoForge 21.1.265',
    [string]$VersionId = '1.21.1-21.1.265',
    [string]$JavaHome = 'D:\Minecraft\AxolotlLauncher\Data\meta\java_versions\zulu25.36.15-ca-jdk25.0.4-win_x64',
    [string]$World = '',
    [string]$Jar = '',
    [switch]$Build,
    [string[]]$ExtraJvmArgs = @(),
    [int]$TimeoutSec = 360,
    [string]$Tag = 'neoforge'
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandArgumentPassing = 'Standard'

$modDir = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $modDir '..')).Path
$instance = (Resolve-Path -LiteralPath $Instance).Path
$modsDir = Join-Path $instance 'mods'
$logsDir = Join-Path $repoRoot 'tools\logs'
New-Item -ItemType Directory -Force -Path $logsDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$consoleLog = Join-Path $logsDir "neoforge-selftest-$Tag-$stamp.log"

# ---- harness presence check --------------------------------------------------
$harnessSrc = Join-Path $modDir 'src\main\java\com\voxymap\SelfTest.java'
$hookFile = Join-Path $modDir 'src\main\java\com\voxymap\VoxyMapMod.java'
if (-not (Test-Path -LiteralPath $harnessSrc)) { throw "SelfTest.java not found; copy it from mod/tools/selftest first" }
$hook = Select-String -LiteralPath $hookFile -Pattern 'SelfTest\.tick\(' -Encoding UTF8
if (-not $hook) { throw 'VoxyMapMod.clientTick has no SelfTest.tick(mc) hook; add it before running the smoke test' }

# ---- build --------------------------------------------------------------------
if ($Build -or -not $Jar) {
    Write-Host '--- building offline selftest jar ---'
    & pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-offline.ps1') -PackJar |
        Out-Host
    if ($LASTEXITCODE -ne 0) { throw "offline build failed (exit $LASTEXITCODE)" }
    $Jar = @(Get-ChildItem -LiteralPath (Join-Path $modDir 'build\libs') -Filter 'voxy-map-*-offline*.jar' |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
if (-not $Jar -or -not (Test-Path -LiteralPath $Jar)) { throw "test jar not found: $Jar" }
Write-Host "jar: $Jar"

# ---- mods swap (always restored) --------------------------------------------
$backups = New-Object System.Collections.Generic.List[object]
function Install-TestJar {
    Get-ChildItem -LiteralPath $modsDir -File | Where-Object {
        $_.Name -like 'voxy-map-*.jar' -and $_.Name -notlike '*.disabled'
    } | ForEach-Object {
        $backup = Join-Path $modsDir ($_.Name + '.voxymaptest-backup')
        Move-Item -LiteralPath $_.FullName -Destination $backup -Force
        $backups.Add([pscustomobject]@{ Original = $_.FullName; Backup = $backup })
        Write-Host "mods: backed up $($_.Name)"
    }
    $dest = Join-Path $modsDir 'voxy-map-selftest.jar'
    Copy-Item -LiteralPath $Jar -Destination $dest -Force
    Write-Host "mods: installed $dest"
}
function Restore-Mods {
    $deadline = (Get-Date).AddSeconds(40)
    while ($true) {
        try {
            $dest = Join-Path $modsDir 'voxy-map-selftest.jar'
            if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Force }
            foreach ($b in $backups) {
                if (Test-Path -LiteralPath $b.Backup) {
                    Move-Item -LiteralPath $b.Backup -Destination $b.Original -Force
                    Write-Host "mods: restored $([System.IO.Path]::GetFileName($b.Original))"
                }
            }
            $backups.Clear()
            return
        } catch {
            if ((Get-Date) -gt $deadline) { throw }
            Start-Sleep -Milliseconds 500
        }
    }
}
function Repair-Mods {
    Get-ChildItem -LiteralPath $modsDir -File -Filter '*.voxymaptest-backup' -ErrorAction SilentlyContinue | ForEach-Object {
        $original = $_.FullName.Substring(0, $_.FullName.Length - '.voxymaptest-backup'.Length)
        if (-not (Test-Path -LiteralPath $original)) {
            Move-Item -LiteralPath $_.FullName -Destination $original -Force
            Write-Host "mods: repaired $([System.IO.Path]::GetFileName($original))"
        } else {
            Remove-Item -LiteralPath $_.FullName -Force
        }
    }
    $leftover = Join-Path $modsDir 'voxy-map-selftest.jar'
    if (Test-Path -LiteralPath $leftover) { Remove-Item -LiteralPath $leftover -Force }
}

$startedAt = Get-Date
$selftestJvm = @('-Dvoxymap.selftest=true')
if ($ExtraJvmArgs.Count -eq 1 -and $ExtraJvmArgs[0] -like '*,*') { $ExtraJvmArgs = $ExtraJvmArgs[0] -split ',' }
if ($ExtraJvmArgs) { $selftestJvm += $ExtraJvmArgs }
$exitCode = -1
try {
    Repair-Mods
    Install-TestJar
    Write-Host '--- launching game (a window will open; the harness quits it) ---'
    $launchArgs = @{
        Instance = $instance
        VersionId = $VersionId
        JavaHome = $JavaHome
        Width = 1280
        Height = 720
        ExtraJvmArgs = $selftestJvm
    }
    if ($World) { $launchArgs.World = $World }
    # Watchdog: kill the game if the harness hangs (it normally quits the client itself).
    $watchdog = Start-Job -ScriptBlock {
        param($seconds)
        Start-Sleep -Seconds $seconds
        Get-Process java, javaw -ErrorAction SilentlyContinue | Stop-Process -Force
    } -ArgumentList $TimeoutSec
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $runner = Join-Path $PSScriptRoot 'launch-direct.ps1'
    # Run the launcher in THIS process: it must own the console handles (Start-Process pipes killed
    # the JVM on this setup), and splatting the hashtable avoids -File array-argument mangling.
    & $runner @launchArgs 2>&1 | Tee-Object -FilePath $consoleLog | Out-Host
    $exitCode = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }
    $sw.Stop()
    Stop-Job -Job $watchdog -ErrorAction SilentlyContinue
    Remove-Job -Job $watchdog -Force -ErrorAction SilentlyContinue
} finally {
    Restore-Mods
}

# ---- collect + verdict -------------------------------------------------------
$shots = @()
$shotDirs = @((Join-Path $instance 'screenshots'), $instance)
Get-ChildItem -LiteralPath $shotDirs -Filter 'selftest-*.png' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.LastWriteTime -ge $startedAt.AddSeconds(-5) } |
    ForEach-Object {
        $dest = Join-Path $logsDir "neoforge-selftest-$Tag-$stamp-$($_.Name)"
        Copy-Item -LiteralPath $_.FullName -Destination $dest -Force
        $shots += $dest
    }
$latestLog = Join-Path $instance 'logs\latest.log'
$selftestLines = @()
$failed = $false
if (Test-Path -LiteralPath $latestLog) {
    $selftestLines = @(Select-String -LiteralPath $latestLog -Pattern 'selftest' -Encoding UTF8 |
        Select-Object -Last 60 | ForEach-Object { $_.Line })
    $selftestLines | Add-Content -LiteralPath $consoleLog -Encoding UTF8
    if ($selftestLines | Where-Object { $_ -match 'FAILED|no level after|WARNING: no 3D' }) { $failed = $true }
    if (-not ($selftestLines | Where-Object { $_ -match 'DONE in ' })) { $failed = $true; Write-Host 'no "[selftest] DONE" line - harness did not finish' }
    $hard = @(Select-String -LiteralPath $latestLog -Pattern 'Exception in thread|Crash report|A fatal error|OutOfMemoryError' -Encoding UTF8)
    if ($hard.Count -gt 0) { $hard | Select-Object -First 5 | ForEach-Object { Write-Host ('  ! ' + $_.Line) }; $failed = $true }
} else {
    Write-Host 'no instance logs/latest.log'; $failed = $true
}
Write-Host "console log: $consoleLog"
Write-Host "screenshots: $($shots.Count)"
$shots | ForEach-Object { Write-Host "  $_" }
if ($shots.Count -eq 0) { Write-Host 'no fresh selftest screenshots - check the log' -ForegroundColor Yellow }
$selftestLines | Where-Object { $_ -match 'DONE in|FAILED|screenshot queued|2D:' } | Select-Object -Last 10 | ForEach-Object { Write-Host "  $_" }
if ($failed -or $exitCode -ne 0) {
    Write-Host 'NEOFORGE SELFTEST: FAIL' -ForegroundColor Red
    exit 1
}
Write-Host 'NEOFORGE SELFTEST: PASS' -ForegroundColor Green
exit 0
