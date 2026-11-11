# Full Fabric 26.2 acceptance matrix for the voxymap mod.
#
# One command to build the scripted-test jar and validate the map on the five real test worlds:
#   2D style screenshots, 2D->3D switching, cold-cache first-open timing (feature vs HEAD baseline)
#   and per-style TileTrace colour-work timings.
#
# Prerequisites: the Minecraft instance + test worlds described in tools/README.md. The baseline jar
# (tools/baseline/voxy-map-baseline-harness.jar) is rebuilt from the git worktree at
# C:\Users\Tsutsutsu\Documents\voxy-map-baseline-head when it is missing or -RebuildBaseline is set.
#
# Usage:
#   pwsh -File tools/verify-26.2.ps1                 # full matrix (~25-35 min, opens the game 10x)
#   pwsh -File tools/verify-26.2.ps1 -SkipPerf       # only the style/2D/3D matrix (~12 min)
#   pwsh -File tools/verify-26.2.ps1 -Worlds w1,w3   # subset
param(
    [string]$Worlds = 'w1,w2,w3,w4,w5',
    [switch]$SkipPerf,
    [switch]$Skip3d,
    [switch]$RebuildBaseline,
    [switch]$SkipBuild,
    [string]$Instance = 'D:\Minecraft\AxolotlLauncher\Data\profiles\26.2-Fabric 0.20.1',
    [string]$Jdk25 = 'D:\Minecraft\AxolotlLauncher\Data\meta\java_versions\zulu25.36.15-ca-jdk25.0.4-win_x64',
    [string]$BaselineWorktree = 'C:\Users\Tsutsutsu\Documents\voxy-map-baseline-head'
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandArgumentPassing = 'Standard'

$RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$logsDir = Join-Path $RepoRoot 'tools\logs'
New-Item -ItemType Directory -Force -Path $logsDir | Out-Null
$runner = Join-Path $PSScriptRoot 'run-selftest.ps1'
$featureJar = Join-Path $RepoRoot 'tools\feature\voxy-map-feature-harness.jar'
$baselineJar = Join-Path $RepoRoot 'tools\baseline\voxy-map-baseline-harness.jar'

$worldNames = @{
    w1 = '新的世界'; w2 = '新的世界2'; w3 = '新的世界3'; w4 = '新的世界4'; w5 = '新的世界5'
}
$wanted = @($Worlds.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })

function Fail([string]$msg) { Write-Host "FAILED: $msg" -ForegroundColor Red; exit 1 }

# ---------------------------------------------------------------- feature jar
if (-not $SkipBuild) {
    Write-Host '=== building the scripted-test jar (-Pselftest) ==='
    & pwsh -NoProfile -File $runner -Build -BuildOnly *> (Join-Path $logsDir 'build.log')
    if (-not (Test-Path -LiteralPath $featureJar)) { Fail "feature jar not built, see tools/logs/build.log" }
}
if (-not (Test-Path -LiteralPath $featureJar)) { Fail "missing $featureJar (run without -SkipBuild)" }

# ---------------------------------------------------------------- baseline jar
$needBaseline = (-not $SkipPerf)
if ($needBaseline) {
    $baselineOk = (Test-Path -LiteralPath $baselineJar)
    if (-not $baselineOk -or $RebuildBaseline) {
        if (-not (Test-Path -LiteralPath $BaselineWorktree)) {
            Write-Host "WARNING: baseline worktree not found ($BaselineWorktree); skipping perf comparison" -ForegroundColor Yellow
            $needBaseline = $false
        } else {
            Write-Host '=== rebuilding baseline harness jar from the HEAD worktree ==='
            $wtHarness = Join-Path $BaselineWorktree 'stonecutter\src\main\java\com\voxymap\MapSelfTest.java'
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'selftest\com\voxymap\MapSelfTest.java') -Destination $wtHarness -Force
            $oldJava = $env:JAVA_HOME
            $env:JAVA_HOME = $Jdk25
            try {
                Push-Location -LiteralPath (Join-Path $BaselineWorktree 'stonecutter')
                try {
                    & .\gradlew.bat ':26.2-fabric:build' '--no-daemon' '--console=plain' | Out-Host
                    if ($LASTEXITCODE -ne 0) { Fail 'baseline gradle build failed' }
                } finally { Pop-Location }
            } finally { if ($null -eq $oldJava) { Remove-Item Env:\JAVA_HOME -ErrorAction SilentlyContinue } else { $env:JAVA_HOME = $oldJava } }
            $built = Join-Path $BaselineWorktree 'stonecutter\versions\26.2-fabric\build\libs\voxy-map-0.3.0+26.2+fabric.jar'
            if (-not (Test-Path -LiteralPath $built)) { Fail "baseline jar missing: $built" }
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $baselineJar) | Out-Null
            Copy-Item -LiteralPath $built -Destination $baselineJar -Force
        }
    }
}

function Run-World([string]$tag, [string]$world, [string]$jar, [string[]]$extra) {
    $out = Join-Path $logsDir ("selftest-$tag.log")
    Write-Host "=== run $tag ($world) ==="
    $runArgs = @('-NoProfile', '-File', $runner, '-World', $world, '-Jar', $jar) + $extra
    & pwsh @runArgs *> $out
    $code = $LASTEXITCODE
    $report = Join-Path (Join-Path $RepoRoot 'tools\selftest-results') $tag 'report.json'
    if (Test-Path -LiteralPath $report) {
        try {
            $r = Get-Content -LiteralPath $report -Raw -Encoding utf8 | ConvertFrom-Json
            Write-Host ("  result={0} firstOpenMs={1} style={2}" -f $r.result, $r.firstOpenMs, $r.firstOpenStyle)
            $r.styles | ForEach-Object { Write-Host ("  style {0,-16} settle={1}ms" -f $_.name, $_.settleMs) }
            if ($r.errors -and @($r.errors).Count) { $r.errors | ForEach-Object { Write-Host "  ERROR: $_" -ForegroundColor Yellow } }
        } catch { Write-Host "  report JSON invalid: $_" -ForegroundColor Yellow }
    } else {
        Write-Host "  no report.json (exit $code); see $out" -ForegroundColor Yellow
    }
    return $code
}

# ---------------------------------------------------------------- matrix
foreach ($w in $wanted) {
    if (-not $worldNames.ContainsKey($w)) { Fail "unknown world key '$w' (use w1..w5)" }
    $extra = @('-Styles', 'core')
    if ($w -eq 'w1') { $extra = @('-Styles', 'all') }
    if (-not $Skip3d -and ($w -eq 'w1' -or $w -eq 'w3')) { $extra += '-View3d' }
    Run-World "matrix-$w" $worldNames[$w] $featureJar $extra
}

# ---------------------------------------------------------------- perf
if ($needBaseline) {
    Run-World 'perf-cold-current-baseline' '新的世界' $baselineJar @('-Baseline', '-Styles', 'current', '-Cold', '-ColdStyle', 'current')
    Run-World 'perf-cold-current-feature' '新的世界' $featureJar @('-Styles', 'current', '-Cold', '-ColdStyle', 'current')
    Run-World 'perf-cold-blocks-feature' '新的世界' $featureJar @('-Styles', 'blocks', '-Cold', '-ColdStyle', 'blocks')
    Run-World 'perf-warm-current' '新的世界' $featureJar @('-Styles', 'current')
    Run-World 'perf-warm-blocks' '新的世界' $featureJar @('-Styles', 'blocks')
    Run-World 'perf-warm-reopen' '新的世界' $featureJar @('-Styles', 'core')
}

# ---------------------------------------------------------------- analysis
$py = $null
foreach ($cand in @($env:DSH_PYTHON,
        'C:\Users\Tsutsutsu\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe',
        'python', 'py')) {
    if (-not $cand) { continue }
    try { & $cand -c 'import statistics, json' 2>$null; if ($LASTEXITCODE -eq 0) { $py = $cand; break } } catch { }
}
if ($py) {
    Write-Host '=== analysis ==='
    & $py (Join-Path $PSScriptRoot 'analyze-selftest.py') (Join-Path $RepoRoot 'tools\selftest-results') 2>&1 | Out-Host
} else {
    Write-Host 'WARNING: no usable python found; skipped tools/analyze-selftest.py' -ForegroundColor Yellow
}
Write-Host 'DONE. Results: tools/selftest-results/<tag>/report.json + screenshots, raw logs: tools/logs/'
