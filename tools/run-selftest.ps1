# Run the voxymap in-game self-test for one world and collect the results.
#
# Responsibilities:
#   1. swap the instance's voxy-map jar for the requested build (always restored afterwards),
#   2. optionally clear this world's 2D tile cache (cold first-open benchmark),
#   3. launch the Fabric 26.2 instance with -Dvoxymap.selftest=true via launch-fabric-26.2.ps1,
#   4. collect logs + screenshots + report.json under tools/selftest-results/<tag>/,
#   5. restore the instance mods and print a compact summary.
#
# The in-game side (com.voxymap.MapSelfTest, built into the jar) drives the map screen: it opens the
# map, cycles styles, takes screenshots, toggles 2D/3D and finally quits the game so this script's
# child process exits by itself. Part A (launcher validation / baseline without the harness) can be
# run with -RunSeconds N: no selftest properties are passed and the process tree is killed after N s.
#
# Usage:
#   pwsh -File tools/run-selftest.ps1 -Build -World '新的世界' -Styles all -View3d
#   pwsh -File tools/run-selftest.ps1 -World '新的世界' -Jar <selftest jar> -Styles core
#   pwsh -File tools/run-selftest.ps1 -World '新的世界' -Jar <jar> -Cold -ColdStyle current -Tag w1-cold-current
param(
    [string]$World = '新的世界',
    [string]$Jar = '',
    [string]$Tag = '',
    [switch]$Cold,
    [string]$ColdStyle = '',
    [string]$Styles = 'core',
    [switch]$Baseline,
    [switch]$View3d,
    [switch]$Build,
    [switch]$BuildOnly,
    [int]$TimeoutSec = 420,
    [int]$RunSeconds = 0,
    [int]$Width = 1280,
    [int]$Height = 720,
    [string]$Instance = 'D:\Minecraft\AxolotlLauncher\Data\profiles\26.2-Fabric 0.20.1',
    [string]$JavaHome = 'D:\Minecraft\AxolotlLauncher\Data\meta\java_versions\zulu25.36.15-ca-jdk25.0.4-win_x64',
    [string]$VersionId = '26.2-0.20.1'
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandArgumentPassing = 'Standard'

$RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$Instance = (Resolve-Path -LiteralPath $Instance).Path
$modsDir = Join-Path $Instance 'mods'
$savesDir = Join-Path $Instance 'saves'
$cacheRoot = Join-Path $Instance 'voxymap-cache'
$logsDir = Join-Path $Instance 'logs'

# ---- optional build ----------------------------------------------------------
# The scripted harness lives in tools/selftest and is only compiled into jars built
# with -Pselftest (the production jar has no test code at all).
function Build-SelftestJar {
    Write-Host '--- building selftest jar (-Pselftest, JDK25) ---'
    $gradleDir = Join-Path $RepoRoot 'stonecutter'
    $built = Join-Path $gradleDir 'versions\26.2-fabric\build\libs'
    $oldJava = $env:JAVA_HOME
    $PSNativeCommandArgumentPassing = 'Standard'
    $env:JAVA_HOME = $JavaHome
    try {
        Push-Location -LiteralPath $gradleDir
        try {
            # Out-Host keeps Gradle's console output visible without polluting this function's
            # return value (PowerShell would otherwise return every output line as part of $Jar).
            & .\gradlew.bat ':26.2-fabric:build' '-Pselftest' '--no-daemon' '--console=plain' |
                Out-Host
            if ($LASTEXITCODE -ne 0) { throw "gradle build failed (exit $LASTEXITCODE)" }
        } finally {
            Pop-Location
        }
    } finally {
        if ($null -eq $oldJava) { Remove-Item Env:\JAVA_HOME -ErrorAction SilentlyContinue } else { $env:JAVA_HOME = $oldJava }
    }
    $jar = Get-ChildItem -LiteralPath $built -Filter 'voxy-map-*-selftest.jar' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw "selftest jar not found in $built" }
    $dest = Join-Path $RepoRoot 'tools\feature\voxy-map-feature-harness.jar'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
    Copy-Item -LiteralPath $jar.FullName -Destination $dest -Force
    Write-Host "built: $($jar.Name) -> $dest"
    return $dest
}
if ($Build) { $Jar = Build-SelftestJar }
if ($BuildOnly) { Write-Host "built: $Jar"; exit 0 }
if (-not $Jar) {
    $defaultJar = Join-Path $RepoRoot 'tools\feature\voxy-map-feature-harness.jar'
    if (Test-Path -LiteralPath $defaultJar) { $Jar = $defaultJar }
    else { throw "no jar given: pass -Jar <jar> or -Build (builds the selftest jar)" }
}

# world folder -> short ASCII tag (the game side writes screenshots/report named after the tag)
$worldTags = @{
    '新的世界'  = 'w1'
    '新的世界2' = 'w2'
    '新的世界3' = 'w3'
    '新的世界4' = 'w4'
    '新的世界5' = 'w5'
}
if (-not $worldTags.ContainsKey($World)) { throw "unsupported world: $World (expected one of $($worldTags.Keys -join ', '))" }
$worldTag = $worldTags[$World]
if (-not $Tag) {
    $Tag = $worldTag
    if ($Cold) { $Tag += '-cold' }
}

$resultsRoot = Join-Path $RepoRoot 'tools\selftest-results'
$outDir = Join-Path $resultsRoot $Tag
if (Test-Path -LiteralPath $outDir) { Remove-Item -LiteralPath $outDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$consoleLog = Join-Path $outDir 'console.log'

Write-Host "=== voxymap selftest: world='$World' tag='$Tag' ==="
Write-Host "instance: $Instance"
Write-Host "results:  $outDir"
if ($Jar) {
    # accept the path relative to the current directory or to the repository root
    $resolved = @($Jar, (Join-Path $RepoRoot $Jar)) |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
    if (-not $resolved) { throw "jar not found: $Jar (tried '$Jar' and '$RepoRoot\$Jar')" }
    $Jar = (Resolve-Path -LiteralPath $resolved).Path
    Write-Host "jar:      $Jar ($([int]((Get-Item -LiteralPath $Jar).Length / 1024)) KiB)"
}

# ---- mods swap (always restored) ---------------------------------------------
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
    if ($Jar) {
        $dest = Join-Path $modsDir 'voxy-map-selftest.jar'
        Copy-Item -LiteralPath $Jar -Destination $dest -Force
        Write-Host "mods: installed $dest"
    }
}
function Restore-Mods {
    # The JVM may still be releasing the jars for a few seconds after the process exits; retry
    # briefly instead of failing the whole run and leaving the instance with the test jar.
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
# Self-heal a previous run that was killed while its test jar was installed (the PowerShell
# session that owned the restore never ran). Without this the instance would be left without the
# real voxy-map jar and the game/map would look broken.
function Repair-Mods {
    if (-not (Test-Path -LiteralPath $modsDir)) { return }
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
    if (Test-Path -LiteralPath $leftover) {
        Remove-Item -LiteralPath $leftover -Force
        Write-Host 'mods: removed leftover voxy-map-selftest.jar'
    }
}

# ---- per-world cache helpers -------------------------------------------------
$worldHashes = @()
$voxyDir = Join-Path (Join-Path $savesDir $World) 'voxy'
if (Test-Path -LiteralPath $voxyDir) {
    $worldHashes = @(Get-ChildItem -LiteralPath $voxyDir -Directory | ForEach-Object { $_.Name })
}
Write-Host "world hashes: $($worldHashes -join ', ')"

function Clear-TileCache {
    foreach ($h in $worldHashes) {
        $tiles = Join-Path (Join-Path $cacheRoot $h) 'tiles'
        if (Test-Path -LiteralPath $tiles) {
            Remove-Item -LiteralPath $tiles -Recurse -Force
            Write-Host "cache: removed tiles for $h"
        }
        $state = Join-Path (Join-Path $cacheRoot $h) 'state.properties'
        if (Test-Path -LiteralPath $state) {
            Remove-Item -LiteralPath $state -Force
            Write-Host "cache: removed state.properties for $h (default view/style so both cold runs match)"
        }
    }
}

# ---- launch ------------------------------------------------------------------
$startedAt = Get-Date
$newestTileLogBefore = Get-ChildItem -LiteralPath $logsDir -Filter 'voxymap-tile-*.log' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
$tileLogBefore = if ($newestTileLogBefore) { $newestTileLogBefore.FullName } else { '' }
$latestLogBefore = (Get-Item -LiteralPath (Join-Path $logsDir 'latest.log') -ErrorAction SilentlyContinue).LastWriteTime

$extraJvm = New-Object System.Collections.Generic.List[string]
$launchArgs = @{
    Instance     = $Instance
    VersionId    = $VersionId
    JavaHome     = $JavaHome
    World        = $World
    Width        = $Width
    Height       = $Height
    TimeoutSec   = $(if ($RunSeconds -gt 0) { $RunSeconds } else { $TimeoutSec })
}
if ($RunSeconds -le 0) {
    $extraJvm.Add('-Dvoxymap.selftest=true')
    $extraJvm.Add("-Dvoxymap.selftest.out=$outDir")
    $extraJvm.Add("-Dvoxymap.selftest.tag=$worldTag")
    $extraJvm.Add("-Dvoxymap.selftest.world=$World")
    $extraJvm.Add("-Dvoxymap.selftest.styles=$Styles")
    $extraJvm.Add("-Dvoxymap.selftest.expectBlocks=$(((-not $Baseline)).ToString().ToLower())")
    if ($View3d) { $extraJvm.Add('-Dvoxymap.selftest.view3d=true') }
    if ($Cold) { $extraJvm.Add('-Dvoxymap.selftest.cold=true') }
    if ($ColdStyle) { $extraJvm.Add("-Dvoxymap.selftest.coldStyle=$ColdStyle") }
} else {
    Write-Host "Part A mode: no selftest properties, killing the process tree after ${RunSeconds}s"
}
$launchArgs.ExtraJvmArgs = $extraJvm.ToArray()

$exitCode = -1
$launchFailed = $false
try {
    Repair-Mods
    Install-TestJar
    if ($Cold) { Clear-TileCache }

    Write-Host '--- launching game (a window will open; the harness quits it when done) ---'
    $launchScript = Join-Path $PSScriptRoot 'launch-fabric-26.2.ps1'
    $launchStart = Get-Date
    # Run the launcher in this same process (foreground call): the JVM inherits the console handles,
    # which this setup needs. Output is tee'd into console.log.
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        & $launchScript @launchArgs 2>&1 | Tee-Object -FilePath $consoleLog
        $exitCode = $LASTEXITCODE
        if ($null -eq $exitCode) { $exitCode = 0 }
        if ($RunSeconds -gt 0) { $exitCode = 0 } # killed on purpose, not a failure
    } catch {
        $launchFailed = $true
        $_ | Out-String | Out-File -FilePath $consoleLog -Append -Encoding utf8
        Write-Host "LAUNCH ERROR: $_"
    }
    $sw.Stop()
    Write-Host "--- game exited: code=$exitCode wall=$([int]$sw.Elapsed.TotalSeconds)s ---"
} finally {
    Restore-Mods
}

# ---- collect artifacts -------------------------------------------------------
function Copy-Fresh([string]$name, [string]$dest) {
    if (-not (Test-Path -LiteralPath $name)) { return $false }
    $item = Get-Item -LiteralPath $name
    if ($item.LastWriteTime -lt $startedAt.AddSeconds(-5)) { return $false }
    Copy-Item -LiteralPath $name -Destination $dest -Force
    return $true
}

if (Test-Path -LiteralPath (Join-Path $logsDir 'latest.log')) {
    $latestLog = Get-Item -LiteralPath (Join-Path $logsDir 'latest.log')
    if ($latestLog.LastWriteTime -ge $startedAt.AddSeconds(-5)) {
        Copy-Item -LiteralPath $latestLog.FullName -Destination (Join-Path $outDir 'game-latest.log') -Force
        Write-Host 'collected: game-latest.log'
    } else {
        Write-Host "game-latest.log is STALE (written $($latestLog.LastWriteTime), run started $startedAt) - the game never started"
    }
}
# the tile trace file may have been rotated if the game wrote a lot; take every fresh one
Get-ChildItem -LiteralPath $logsDir -Filter 'voxymap-tile-*.log' -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -ne $tileLogBefore -and $_.LastWriteTime -ge $startedAt.AddSeconds(-5) } |
    Sort-Object LastWriteTime |
    ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $outDir 'tile-trace.log') -Force
        Write-Host "collected: tile-trace.log (from $($_.Name))"
    }

$crashDir = Join-Path $Instance 'crash-reports'
if (Test-Path -LiteralPath $crashDir) {
    $newCrash = Get-ChildItem -LiteralPath $crashDir -Filter '*.txt' -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -ge $startedAt.AddSeconds(-5) } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($newCrash) {
        Copy-Item -LiteralPath $newCrash.FullName -Destination (Join-Path $outDir 'crash.txt') -Force
        Write-Host "collected: crash.txt (from $($newCrash.Name))"
    }
}

# ---- summary -----------------------------------------------------------------
# MapSelfTest emits valid JSON except that it omits the comma after the scalar "quietTicks" field and
# after the single-line "log" array (Java StringBuilder bug in the harness; the harness cannot be
# rebuilt here). Repair the missing separators line-by-line before parsing.
function ConvertFrom-SelftestJson([string]$raw) {
    $lines = $raw -split "`r?`n"
    $out = New-Object System.Collections.Generic.List[string]
    $prev = ''
    foreach ($line in $lines) {
        if ($prev -ne '' -and $line -match '^\s*"[^"]+"\s*:' -and $prev -notmatch '[,{\[]\s*$') {
            $out[$out.Count - 1] = $out[$out.Count - 1].TrimEnd() + ','
        }
        $out.Add($line)
        if ($line.Trim() -ne '') { $prev = $line }
    }
    return (($out -join "`n") | ConvertFrom-Json)
}

$reportPath = Join-Path $outDir 'report.json'
$gameLog = Join-Path $outDir 'game-latest.log'
$traceLog = Join-Path $outDir 'tile-trace.log'
$failed = $false
if (Test-Path -LiteralPath $reportPath) {
    try {
        $r = ConvertFrom-SelftestJson (Get-Content -LiteralPath $reportPath -Raw -Encoding utf8)
        Write-Host ''
        Write-Host "=== report ($Tag) result=$($r.result) ==="
        Write-Host ("world={0} cold={1} firstOpenMs={2} firstOpenStyle={3} worldReadyMs={4} worldQuietMs={5}" -f `
                $r.world, $r.cold, $r.firstOpenMs, $r.firstOpenStyle, $r.worldReadyMs, $r.worldQuietMs)
        Write-Host ("styles available: {0} | missing: {1}" -f (@($r.styleNames) -join ','), (@($r.missingStyles) -join ','))
        foreach ($s in @($r.styles)) {
            Write-Host ("  style {0,-16} settle={1,7}ms colorReq={2,-6} colorDone={3,-6} tex={4,-5} shot={5}" -f `
                    $s.name, $s.settleMs, $s.colorRequests, $s.colorDone, $s.textureBuilds, $s.screenshot)
        }
        if ($r.view3d -and $r.view3d.tested) {
            Write-Host ("  3D: entered={0} parity={1} styles={2} shots={3}" -f `
                    $r.view3d.entered, $r.view3d.parity, (@($r.view3d.styles) -join ','), (@($r.view3d.screenshots) -join ','))
        }
        if ($r.errors -and @($r.errors).Count -gt 0) {
            Write-Host ("  errors: {0}" -f (@($r.errors) -join ' | '))
        }
        if ($r.warnings -and @($r.warnings).Count -gt 0) {
            Write-Host ("  warnings: {0}" -f (@($r.warnings) -join ' | '))
        }
        Write-Host ("  screenshots: {0}" -f (@($r.screenshots) -join ', '))
        if ($r.tileTraceFile) { Write-Host ("  tileTraceFile: {0}" -f $r.tileTraceFile) }
        if ($r.result -ne 'PASS') { $failed = $true }
    } catch {
        Write-Host "could not parse report.json: $_"
        $failed = $true
    }
} else {
    if ($RunSeconds -le 0) { Write-Host 'NO report.json - the harness did not finish'; $failed = $true }
}

# quick log check shared by Part A and the harness runs
if (Test-Path -LiteralPath $gameLog) {
    $init = Select-String -LiteralPath $gameLog -Pattern 'Voxy Map \(Fabric, 26\.2\) initialised' -SimpleMatch:$false
    $joined = Select-String -LiteralPath $gameLog -Pattern 'joined the game|加入了游戏'
    $selftestLines = @(Select-String -LiteralPath $gameLog -Pattern '\[selftest' -SimpleMatch)
    $hardErrors = @(Select-String -LiteralPath $gameLog -Pattern 'Exception in thread|Crash report|A fatal error|java\.lang\.NullPointerException|OutOfMemoryError' -SimpleMatch)
    Write-Host ("log: initialised={0} worldJoined={1} selftestLines={2} hardErrorMatches={3}" -f `
            ([bool]$init), ([bool]$joined), $selftestLines.Count, $hardErrors.Count)
    if ($hardErrors.Count -gt 0 -and $RunSeconds -le 0) {
        $hardErrors | Select-Object -First 5 | ForEach-Object { Write-Host ("  ! " + $_.Line) }
        $failed = $true
    }
    if ($RunSeconds -gt 0 -and (-not $init -or -not $joined)) { $failed = $true }
} else {
    Write-Host 'no game log collected (did the game start?)'
    $failed = $true
}

$shots = @(Get-ChildItem -LiteralPath $outDir -Filter '*.png' -ErrorAction SilentlyContinue)
Write-Host "results: $outDir"
Write-Host "report:  $reportPath"
if (Test-Path -LiteralPath $traceLog) { Write-Host "trace:   $traceLog" }
Write-Host "files in results dir: $((Get-ChildItem -LiteralPath $outDir -File).Count) ($($shots.Count) screenshots)"
if ($failed -or $launchFailed) {
    Write-Host 'RUN FAILED' -ForegroundColor Red
    exit 1
}
Write-Host 'RUN OK'
exit 0
