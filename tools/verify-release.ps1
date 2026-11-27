#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Release gate for voxymap: builds the in-game selftest harness for each Fabric version and runs a
    short scenario matrix (cold open, warm open, style/2D-3D functional, live-generation grey check).

.DESCRIPTION
    This is the "quick pre-release check" meant to be run before tagging a release. It wraps
    tools/run-selftest.ps1 (one game launch per scenario) and turns its report.json into PASS/FAIL
    gates, so a release stops on regressions instead of relying on manual screenshot review.

    Scenarios per version (default = quick, ~8-10 min per version):
      cold        fresh world via the harness, map opened 2.5 s after spawn, probe 25 s
                  gate: PASS report, no errors, first paint within budget
      warm        same world reopened with the tile cache, probe 15 s
                  gate: PASS report, cache hits > 0, first paint within budget
      functional  2D style cycle (all 7 styles) + 2D->3D parity on the same world
                  gate: PASS report, all styles tested, 3D entered and parity ok
      grey        fresh world with SeedGen generating, zoom 0.08, FALLBACK pixels painted magenta
                  (-NoShading -DebugFallback), screenshots at 20 s / 40 s
                  gate: final screenshot has 0 magenta pixels (see doc/灰色群系bug-定位与修复-2026-11-27.md)
    -Full adds one extra scenario:
      lowzoom     cold reopen at zoom 0.04 (576 visible tiles), probe 35 s
                  gate: PASS report, first paint within budget

.PARAMETER Versions
    Versions to check: '26.2', '26.1.2' (default both). 1.21.1/NeoForge is intentionally not covered.

.PARAMETER SkipBuild
    Reuse the existing tools/feature/voxy-map-feature-harness-<version>.jar instead of rebuilding.

.PARAMETER Full
    Include the extra low-zoom scenario.

.PARAMETER KeepWorlds
    Keep the temporary worlds (rc-*-cold / rc-*-grey) created by this run.

.PARAMETER SkipFunctional
    Skip the style/3D scenario (only performance + grey checks).

.EXAMPLE
    pwsh -File tools/verify-release.ps1                    # both versions, quick
    pwsh -File tools/verify-release.ps1 -Versions 26.1.2   # one version
    pwsh -File tools/verify-release.ps1 -Full -SkipBuild   # reuse jars, add low-zoom
#>
param(
    [string[]]$Versions = @('26.2', '26.1.2'),
    [switch]$SkipBuild,
    [switch]$Full,
    [switch]$KeepWorlds,
    [switch]$SkipFunctional,
    [int]$TimeoutSec = 420
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandArgumentPassing = 'Standard'

$RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$RunSelftest = Join-Path $PSScriptRoot 'run-selftest.ps1'
$ResultsRoot = Join-Path $RepoRoot 'tools\selftest-results'
$DateStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$ReleaseDir = Join-Path $RepoRoot "tools\release-checks\$DateStamp"
New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null

$versionMap = @{
    '26.2'   = @{ Instance = 'D:\Minecraft\AxolotlLauncher\Data\profiles\26.2-Fabric 0.20.1';
                  VersionId = '26.2-0.20.1'; GradleProject = '26.2-fabric'; Num = '262' }
    '26.1.2' = @{ Instance = 'D:\Minecraft\AxolotlLauncher\Data\profiles\26.1.2-Fabric 0.20.1';
                  VersionId = '26.1.2-0.20.1'; GradleProject = '26.1-fabric'; Num = '2612' }
}
foreach ($v in $Versions) {
    if (-not $versionMap.ContainsKey($v)) {
        throw "unsupported -Versions entry '$v' (expected: $($versionMap.Keys -join ', '))"
    }
}

# python is only needed for the grey check (magenta pixel counting)
$Python = $null
$pythonCmd = Get-Command python -ErrorAction SilentlyContinue |
    Where-Object { $_.Source -and $_.Source -notmatch 'WindowsApps' } | Select-Object -First 1
if ($pythonCmd) { $Python = $pythonCmd.Source }
if (-not $Python) {
    $dshPython = 'C:\Users\Tsutsutsu\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
    if (Test-Path -LiteralPath $dshPython) { $Python = $dshPython }
}

# ---------------------------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------------------------
function ConvertFrom-SelftestJson([string]$raw) {
    # The harness emits JSON with two missing commas (quietTicks field / single-line log array).
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

function Get-LastProbe([object]$report) {
    $probes = @($report.probe)
    if ($probes.Count -eq 0) { return $null }
    return $probes[$probes.Count - 1]
}

function Get-ProbeInt([string]$probeLine, [string]$field) {
    if (-not $probeLine) { return -1 }
    $m = [regex]::Match($probeLine, "$field=(\d+)")
    if ($m.Success) { return [int]$m.Groups[1].Value }
    return -1
}

function Remove-TestWorld([string]$instance, [string]$worldName) {
    $savesDir = Join-Path $instance 'saves'
    $cacheRoot = Join-Path $instance 'voxymap-cache'
    $worldDir = Join-Path $savesDir $worldName
    if (-not (Test-Path -LiteralPath $worldDir)) { return }
    $voxyDir = Join-Path $worldDir 'voxy'
    if (Test-Path -LiteralPath $voxyDir) {
        Get-ChildItem -LiteralPath $voxyDir -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $cacheDir = Join-Path $cacheRoot $_.Name
            if (Test-Path -LiteralPath $cacheDir) {
                Remove-Item -LiteralPath $cacheDir -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
    }
    Remove-Item -LiteralPath $worldDir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "  cleanup: removed test world '$worldName'"
}

# ---------------------------------------------------------------------------------------------
# scenario matrix
# ---------------------------------------------------------------------------------------------
function Get-Scenarios([string]$version) {
    $vm = $versionMap[$version]
    $coldWorld = "rc-$($vm.Num)-cold"
    $greyWorld = "rc-$($vm.Num)-grey"
    $list = New-Object System.Collections.Generic.List[object]

    $list.Add([pscustomobject]@{
        Id     = 'cold'
        Kind   = 'cold'
        World  = $coldWorld
        Params = @{
            CreateWorld = $coldWorld; OpenDelayMs = 2500; ProbeMs = 25000;
            Tag = "rc-$($vm.Num)-cold"; TimeoutSec = $TimeoutSec
        }
    })
    $list.Add([pscustomobject]@{
        Id     = 'warm'
        Kind   = 'warm'
        World  = $coldWorld
        Params = @{
            World = $coldWorld; ProbeMs = 15000;
            Tag = "rc-$($vm.Num)-warm"; TimeoutSec = $TimeoutSec
        }
    })
    if (-not $SkipFunctional) {
        $list.Add([pscustomobject]@{
            Id     = 'functional'
            Kind   = 'functional'
            World  = $coldWorld
            Params = @{
                World = $coldWorld; Styles = 'all'; View3d = $true;
                Tag = "rc-$($vm.Num)-func"; TimeoutSec = $TimeoutSec
            }
        })
    }
    $list.Add([pscustomobject]@{
        Id     = 'grey'
        Kind   = 'grey'
        World  = $greyWorld
        Params = @{
            CreateWorld = $greyWorld; OpenDelayMs = 2000; ProbeMs = 40000; Zoom = 0.08;
            NoShading = $true; DebugFallback = $true; ShotAtMs = '20000,40000';
            Tag = "rc-$($vm.Num)-grey"; TimeoutSec = $TimeoutSec
        }
    })
    if ($Full) {
        $list.Add([pscustomobject]@{
            Id     = 'lowzoom'
            Kind   = 'lowzoom'
            World  = $coldWorld
            Params = @{
                World = $coldWorld; Cold = $true; ProbeMs = 35000; Zoom = 0.04;
                Tag = "rc-$($vm.Num)-lowzoom"; TimeoutSec = $TimeoutSec
            }
        })
    }
    return $list
}

# ---------------------------------------------------------------------------------------------
# evaluation
# ---------------------------------------------------------------------------------------------
function Test-Scenario {
    param(
        [string]$Version,
        [object]$Scenario,
        [int]$ExitCode,
        [string]$ScenarioDir
    )
    $checks = New-Object System.Collections.Generic.List[object]
    function Add-Check([string]$name, [bool]$ok, [string]$detail) {
        $checks.Add([pscustomobject]@{ Name = $name; Ok = $ok; Detail = $detail })
    }

    $reportPath = Join-Path $ScenarioDir 'report.json'
    if ($ExitCode -ne 0) { Add-Check 'run-selftest exit code' $false "exit=$ExitCode" }
    else { Add-Check 'run-selftest exit code' $true 'exit=0' }

    if (-not (Test-Path -LiteralPath $reportPath)) {
        Add-Check 'report.json' $false 'missing'
        return [pscustomobject]@{ Scenario = $Scenario; Checks = $checks; Metrics = @{}; Dir = $ScenarioDir }
    }

    $r = $null
    try {
        $r = ConvertFrom-SelftestJson (Get-Content -LiteralPath $reportPath -Raw -Encoding utf8)
        Add-Check 'report.json' $true "result=$($r.result)"
    } catch {
        Add-Check 'report.json' $false "parse error: $_"
        return [pscustomobject]@{ Scenario = $Scenario; Checks = $checks; Metrics = @{}; Dir = $ScenarioDir }
    }

    $firstPaint = [int]$r.firstPaintMs
    $errors = @($r.errors)
    $lastProbe = Get-LastProbe $r
    $metrics = @{
        FirstPaintMs = $firstPaint
        Errors       = $errors.Count
        Result       = "$($r.result)"
        LastProbe    = "$lastProbe"
    }

    Add-Check 'harness PASS' ($r.result -eq 'PASS') "result=$($r.result)"
    Add-Check 'no harness errors' ($errors.Count -eq 0) "errors=$($errors.Count)"

    switch ($Scenario.Kind) {
        'cold' {
            $budget = 15000
            Add-Check 'first paint budget' ($firstPaint -gt 0 -and $firstPaint -le $budget) "${firstPaint}ms <= ${budget}ms"
        }
        'warm' {
            $budget = 10000
            $cacheH = Get-ProbeInt $lastProbe 'cacheH'
            Add-Check 'first paint budget' ($firstPaint -gt 0 -and $firstPaint -le $budget) "${firstPaint}ms <= ${budget}ms"
            Add-Check 'tile cache used' ($cacheH -gt 0) "cacheH=$cacheH"
            $metrics.CacheHits = $cacheH
        }
        'functional' {
            if (-not $SkipFunctional) {
                $styles = @($r.styles)
                $missing = @($r.missingStyles)
                Add-Check 'all 7 styles tested' ($styles.Count -ge 7 -and $missing.Count -eq 0) "styles=$($styles.Count) missing=$($missing -join ',')"
                $d3 = $r.view3d
                $d3ok = ($d3 -and $d3.tested -and $d3.entered -and $d3.parity)
                Add-Check '2D->3D parity' ([bool]$d3ok) "entered=$($d3.entered) parity=$($d3.parity)"
                $metrics.Styles = $styles.Count
                $metrics.View3d = [bool]$d3ok
            }
        }
        'grey' {
            $budget = 20000
            Add-Check 'first paint budget' ($firstPaint -gt 0 -and $firstPaint -le $budget) "${firstPaint}ms <= ${budget}ms"
            $fbVis = Get-ProbeInt $lastProbe 'fbVis'
            Add-Check 'no FALLBACK tiles visible' ($fbVis -eq 0) "fbVis=$fbVis"
            $metrics.FbVis = $fbVis

            # magenta = FALLBACK pixels painted by -Dvoxymap.debugFallback; only the debug colour can
            # be judged (grey stone/andesite is legit terrain). Allow a tiny transient count from a
            # single frame, but the last screenshot must be clean.
            $shots = @(Get-ChildItem -LiteralPath $ScenarioDir -Filter '*.png' -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match '-t(\d+)\.png$' })
            if ($shots.Count -eq 0) {
                Add-Check 'grey screenshots' $false 'no -t<ms>.png screenshots'
            } elseif (-not $Python) {
                Add-Check 'grey screenshots' $false 'python not found (needed by analyze-grey.py)'
            } else {
                $shotPaths = @($shots | ForEach-Object { $_.FullName })
                $jsonLines = & $Python (Join-Path $PSScriptRoot 'analyze-grey.py') @shotPaths --json
                if ($LASTEXITCODE -ne 0 -or -not $jsonLines) {
                    Add-Check 'grey screenshots' $false 'analyze-grey.py failed'
                } else {
                    $stats = @($jsonLines | ForEach-Object { $_ | ConvertFrom-Json })
                    $lastShot = $stats | Sort-Object { [int]([regex]::Match($_.file, '-t(\d+)').Groups[1].Value) } |
                        Select-Object -Last 1
                    $maxMagenta = ($stats | Measure-Object -Property magenta -Maximum).Maximum
                    $totalPx = [math]::Max(1, ($lastShot.width * $lastShot.height))
                    $lastPct = 100.0 * $lastShot.magenta / $totalPx
                    $maxAllowed = 500   # ~0.05% of a 1280x720 frame: a single transient frame at most
                    Add-Check 'no magenta in final screenshot' ($lastShot.magenta -eq 0) "last magenta=$($lastShot.magenta) ($('{0:F3}' -f $lastPct)%)"
                    Add-Check 'magenta only transient' ($maxMagenta -le $maxAllowed) "max magenta=$maxMagenta <= $maxAllowed"
                    $metrics.MagentaLast = $lastShot.magenta
                    $metrics.MagentaMax = $maxMagenta
                }
            }
        }
        'lowzoom' {
            $budget = 20000
            Add-Check 'first paint budget' ($firstPaint -gt 0 -and $firstPaint -le $budget) "${firstPaint}ms <= ${budget}ms"
        }
    }

    return [pscustomobject]@{ Scenario = $Scenario; Checks = $checks; Metrics = $metrics; Dir = $ScenarioDir }
}

# ---------------------------------------------------------------------------------------------
# main loop
# ---------------------------------------------------------------------------------------------
$allResults = New-Object System.Collections.Generic.List[object]
$createdWorlds = New-Object System.Collections.Generic.List[object]
$overallOk = $true

foreach ($version in $Versions) {
    $vm = $versionMap[$version]
    $instance = $vm.Instance
    Write-Host ''
    Write-Host "=============================================================="
    Write-Host " release check: voxymap $version"
    Write-Host " instance: $instance"
    Write-Host "=============================================================="

    $harnessJar = Join-Path $RepoRoot "tools\feature\voxy-map-feature-harness-$version.jar"
    if (-not $SkipBuild) {
        Write-Host "--- building $version harness jar ---"
        $buildLog = Join-Path $ReleaseDir "build-$version.log"
        & pwsh -NoProfile -File $RunSelftest -Version $version -Build -BuildOnly 2>&1 |
            Tee-Object -FilePath $buildLog | Out-Host
        if ($LASTEXITCODE -ne 0) {
            Write-Host "BUILD FAILED for $version (see $buildLog)" -ForegroundColor Red
            $overallOk = $false
            continue
        }
    }
    if (-not (Test-Path -LiteralPath $harnessJar)) {
        Write-Host "harness jar missing: $harnessJar (run without -SkipBuild)" -ForegroundColor Red
        $overallOk = $false
        continue
    }

    $scenarios = Get-Scenarios $version
    foreach ($scenario in $scenarios) {
        $id = $scenario.Id
        Write-Host ''
        Write-Host "--- [$version] scenario: $id ---"
        $consoleLog = Join-Path $ReleaseDir "console-$version-$id.log"

        $runParams = @{
            Version = $version
            Jar     = $harnessJar
        }
        foreach ($k in $scenario.Params.Keys) { $runParams[$k] = $scenario.Params[$k] }

        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        & pwsh -NoProfile -File $RunSelftest @runParams 2>&1 | Tee-Object -FilePath $consoleLog | Out-Host
        $exitCode = $LASTEXITCODE
        $sw.Stop()

        $scenarioTag = $scenario.Params['Tag']
        $scenarioDir = Join-Path $ResultsRoot $scenarioTag
        $eval = Test-Scenario -Version $version -Scenario $scenario -ExitCode $exitCode -ScenarioDir $scenarioDir
        $eval | Add-Member -NotePropertyName DurationSec -NotePropertyValue ([int]$sw.Elapsed.TotalSeconds)
        $eval | Add-Member -NotePropertyName Version -NotePropertyValue $version
        $allResults.Add($eval)

        $failedChecks = @($eval.Checks | Where-Object { -not $_.Ok })
        if ($failedChecks.Count -eq 0) {
            Write-Host "RESULT [$version/$id]: PASS ($($sw.Elapsed.TotalSeconds -as [int]) s)" -ForegroundColor Green
        } else {
            $overallOk = $false
            Write-Host "RESULT [$version/$id]: FAIL ($($sw.Elapsed.TotalSeconds -as [int]) s)" -ForegroundColor Red
            foreach ($c in $failedChecks) {
                Write-Host ("  x {0}: {1}" -f $c.Name, $c.Detail) -ForegroundColor Red
            }
        }
        if ($scenario.Params.ContainsKey('CreateWorld')) {
            $createdWorlds.Add([pscustomobject]@{ Instance = $instance; Name = $scenario.Params['CreateWorld'] })
        }
    }
}

# ---------------------------------------------------------------------------------------------
# cleanup + report
# ---------------------------------------------------------------------------------------------
if (-not $KeepWorlds) {
    foreach ($w in $createdWorlds) { Remove-TestWorld $w.Instance $w.Name }
} else {
    Write-Host 'cleanup: -KeepWorlds set, test worlds kept'
}

$summaryJson = Join-Path $ReleaseDir 'summary.json'
$summaryMd = Join-Path $ReleaseDir 'summary.md'
$allResults | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryJson -Encoding UTF8

$md = New-Object System.Collections.Generic.List[string]
$md.Add("# voxymap release check - $DateStamp")
$md.Add('')
$md.Add("Versions: $($Versions -join ', ') | scenarios: quick$(if ($Full) {' + lowzoom'}) | result: $(if ($overallOk) {'PASS'} else {'FAIL'})")
$md.Add('')
$md.Add('| version | scenario | result | first paint | duration | notes |')
$md.Add('|---|---|---|---|---|---|')
foreach ($r in $allResults) {
    $failed = @($r.Checks | Where-Object { -not $_.Ok })
    $status = if ($failed.Count -eq 0) { 'PASS' } else { 'FAIL' }
    $fp = if ($r.Metrics.ContainsKey('FirstPaintMs')) { "$($r.Metrics.FirstPaintMs) ms" } else { '' }
    $notes = @($r.Checks | ForEach-Object { "$($_.Name)=$($_.Detail)" }) -join '; '
    $md.Add("| $($r.Version) | $($r.Scenario.Id) | $status | $fp | $($r.DurationSec)s | $notes |")
}
$md.Add('')
$md.Add('Console logs and per-scenario report.json files are next to this file; full artifacts live in `tools/selftest-results/<tag>/`.')
Set-Content -LiteralPath $summaryMd -Value ($md -join "`n") -Encoding UTF8

Write-Host ''
Write-Host "release check artifacts: $ReleaseDir"
Write-Host "summary: $summaryMd"
if ($overallOk) {
    Write-Host 'RELEASE CHECK: PASS' -ForegroundColor Green
    exit 0
}
Write-Host 'RELEASE CHECK: FAIL' -ForegroundColor Red
exit 1
