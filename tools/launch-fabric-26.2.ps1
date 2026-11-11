# Launch the real AxolotlLauncher Fabric 26.2 instance without the launcher GUI.
#
# Adapted from mod/tools/launch-direct.ps1 (NeoForge 1.21.1): the launcher's per-version JSON is a
# complete vanilla-style launch description, so the java command is rebuilt from it. Differences:
#   - Fabric 26.2 has no --fml.* / --launchTarget game arguments (KnotClient main class only).
#   - No natives-windows classifiers in the JSON: modern LWJGL ships the native jars as ordinary
#     rule-gated classpath entries and extracts itself into ${natives_directory}.
#   - Java 25 (zulu25.36.15-ca).
#
# The JVM is started with Start-Process -NoNewWindow (no redirected pipes: on this setup redirecting
# the JVM's stdio through Start-Process got the process killed with CTRL_C) so the caller keeps the
# console handles and can see/capture the game output. -TimeoutSec kills the whole process tree if
# the game does not quit on its own (the self-test harness quits by itself).
#
# Usage:
#   pwsh -File tools/launch-fabric-26.2.ps1 -PrintOnly
#   pwsh -File tools/launch-fabric-26.2.ps1 -World '新的世界' -ExtraJvmArgs '-Dvoxymap.selftest=true'
param(
    [string]$Instance = 'D:\Minecraft\AxolotlLauncher\Data\profiles\26.2-Fabric 0.20.1',
    [string]$VersionId = '26.2-0.20.1',
    [string]$JavaHome = 'D:\Minecraft\AxolotlLauncher\Data\meta\java_versions\zulu25.36.15-ca-jdk25.0.4-win_x64',
    [string]$World = '',
    [string]$Username = 'Tester',
    [int]$Width = 1280,
    [int]$Height = 720,
    [string[]]$ExtraJvmArgs = @(),
    [int]$TimeoutSec = 0,
    [switch]$PrintOnly
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandArgumentPassing = 'Standard'

if (-not (Test-Path -LiteralPath $Instance)) { throw "instance not found: $Instance" }
$Instance = (Resolve-Path -LiteralPath $Instance).Path
$dataDir = Split-Path -Parent (Split-Path -Parent $Instance)
$meta = Join-Path $dataDir 'meta'
if (-not (Test-Path -LiteralPath $meta)) { throw "launcher meta not found: $meta" }
$versionDir = Join-Path $meta "versions\$VersionId"
$versionJsonPath = Join-Path $versionDir "$VersionId.json"
if (-not (Test-Path -LiteralPath $versionJsonPath)) { throw "version json not found: $versionJsonPath" }
$v = Get-Content -LiteralPath $versionJsonPath -Raw | ConvertFrom-Json

function Test-Rules($rules) {
    if ($null -eq $rules -or @($rules).Count -eq 0) { return $true }
    $allowed = $false
    foreach ($r in @($rules)) {
        if ($r.os -and $r.os.name -and $r.os.name -ne 'windows') { continue }
        if ($r.os -and $r.os.arch -and $r.os.arch -ne 'x86_64') { continue }
        if ($r.action -eq 'allow') { $allowed = $true }
        elseif ($r.action -eq 'disallow') { return $false }
    }
    return $allowed
}

# ---- classpath ---------------------------------------------------------------
$libraries = Join-Path $meta 'libraries'
$cpEntries = New-Object System.Collections.Generic.List[string]
$missing = New-Object System.Collections.Generic.List[string]
# Fallback for launcher-format entries that carry only a Maven coordinate ("group:artifact:version")
# plus a repository url instead of an explicit downloads.artifact path (Fabric loader, sponge-mixin,
# ASM in this profile): derive the canonical maven layout path from the coordinate.
function Get-MavenRelativePath([string]$name) {
    $parts = $name.Split(':')
    if ($parts.Count -lt 3) { return $null }
    $group = $parts[0]; $artifact = $parts[1]; $version = $parts[2]
    $classifier = if ($parts.Count -ge 4) { '-' + $parts[3] } else { '' }
    $file = "$artifact-$version$classifier.jar"
    return (($group.Split('.') -join '/') + "/$artifact/$version/$file")
}
foreach ($lib in $v.libraries) {
    if (-not (Test-Rules $lib.rules)) { continue }
    $art = $lib.downloads.artifact
    $include = -not ($lib.PSObject.Properties.Name -contains 'include_in_classpath') -or $lib.include_in_classpath
    if (-not $include) { continue }
    $rel = $null
    if ($art -and $art.path) { $rel = $art.path } else { $rel = Get-MavenRelativePath $lib.name }
    if (-not $rel) { continue }
    $p = Join-Path $libraries $rel
    if (Test-Path -LiteralPath $p) { $cpEntries.Add($p) } else { $missing.Add($p) }
}
$clientJar = Join-Path $versionDir "$VersionId.jar"
if (Test-Path -LiteralPath $clientJar) { $cpEntries.Add($clientJar) } else { $missing.Add($clientJar) }
# Fabric loader libraries: the version JSON of a Fabric profile normally already lists them; when it
# does not, fail loudly instead of starting a half-classpath.
$hasKnot = $false
foreach ($e in $cpEntries) { if ($e -match 'fabric-loader') { $hasKnot = $true } }
if (-not $hasKnot) { throw 'fabric-loader is not on the rebuilt classpath; version json looks incomplete' }
if ($missing.Count -gt 0) {
    Write-Host 'MISSING libraries:' -ForegroundColor Red
    $missing | ForEach-Object { Write-Host "  $_" }
    throw "$($missing.Count) launch files are missing"
}

$nativesDir = Join-Path $Instance '.voxymap-launch\natives'
New-Item -ItemType Directory -Force -Path $nativesDir | Out-Null
$assetsDir = Join-Path $meta 'assets'
$assetIndex = if ($v.assetIndex) { $v.assetIndex.id } else { $v.assets }

$repl = @{
    '${natives_directory}'   = $nativesDir
    '${launcher_name}'       = 'voxymap-test'
    '${launcher_version}'    = '1'
    '${classpath}'           = ($cpEntries -join ';')
    '${classpath_separator}' = ';'
    '${library_directory}'   = $libraries
    '${version_name}'        = $VersionId
    '${auth_player_name}'    = $Username
    '${game_directory}'      = $Instance
    '${assets_root}'         = $assetsDir
    '${assets_index_name}'   = $assetIndex
    '${auth_uuid}'           = '00000000000000000000000000000000'
    '${auth_access_token}'   = '0'
    '${clientid}'            = '0'
    '${auth_xuid}'           = '0'
    '${user_type}'           = 'legacy'
    '${version_type}'        = 'release'
    '${user_properties}'     = '{}'
}
function Expand-Arg([string]$s) {
    foreach ($k in $repl.Keys) { $s = $s.Replace($k, [string]$repl[$k]) }
    return $s
}

# ---- jvm arguments ------------------------------------------------------------
$jvm = New-Object System.Collections.Generic.List[string]
foreach ($a in $v.arguments.jvm) {
    if ($a -is [string]) { $jvm.Add((Expand-Arg $a)); continue }
    if (Test-Rules $a.rules) { foreach ($x in @($a.value)) { $jvm.Add((Expand-Arg $x)) } }
}
# sane dev defaults; the real launcher's own -Xmx / GC args are not in the version json
$jvm.Add('-Xms1G')
$jvm.Add('-Xmx4G')
foreach ($extra in $ExtraJvmArgs) { $jvm.Add($extra) }

# ---- game arguments -----------------------------------------------------------
$game = New-Object System.Collections.Generic.List[string]
$skipFeatureArgs = @('--demo', '--quickPlayPath', '--quickPlaySingleplayer', '--quickPlayMultiplayer', '--quickPlayRealms', '--width', '--height')
$gameArgsRaw = $v.arguments.game
$i = 0
while ($i -lt $gameArgsRaw.Count) {
    $a = $gameArgsRaw[$i]
    if ($a -is [string]) {
        if ($a -in @('--width', '--height', '--quickPlaySingleplayer')) { $i += 2; continue }
        $game.Add((Expand-Arg $a)); $i++; continue
    }
    $vals = @($a.value)
    $skip = $false
    foreach ($x in $vals) {
        foreach ($s in $skipFeatureArgs) { if ($x -eq $s -or $x -like '*quickPlay*') { $skip = $true } }
    }
    if (-not $skip -and (Test-Rules $a.rules)) { foreach ($x in $vals) { $game.Add((Expand-Arg $x)) } }
    $i++
}
$game.Add('--width'); $game.Add("$Width")
$game.Add('--height'); $game.Add("$Height")
if ($World) { $game.Add('--quickPlaySingleplayer'); $game.Add($World) }

$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw "java not found: $java" }

$argList = New-Object System.Collections.Generic.List[string]
foreach ($x in $jvm) { $argList.Add($x) }
$argList.Add($v.mainClass)
foreach ($x in $game) { $argList.Add($x) }

function Quote-Arg([string]$s) {
    if ($s -match '[\s"]') { return '"' + ($s -replace '"', '\"') + '"' }
    return $s
}
$argLine = ($argList | ForEach-Object { Quote-Arg $_ }) -join ' '

if ($PrintOnly) {
    Write-Host "java:    $java"
    Write-Host "version: $VersionId  main: $($v.mainClass)"
    Write-Host "cp:      $($cpEntries.Count) entries"
    Write-Host "world:   $(if ($World) { $World } else { '<none>' })"
    Write-Host "args:    $java $argLine"
    exit 0
}

Write-Host "launching $VersionId (world: $(if ($World) { $World } else { '<none>' }))"
Write-Host "instance: $Instance"
$logDir = Join-Path $Instance '.voxymap-launch'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

# -NoNewWindow: the JVM inherits this console (no pipe) which is what keeps it alive on this setup.
$startedAt = Get-Date
$p = Start-Process -FilePath $java -ArgumentList $argLine -WorkingDirectory $Instance -NoNewWindow -PassThru
Write-Host "pid=$($p.Id)"
if ($TimeoutSec -gt 0) {
    if (-not $p.WaitForExit($TimeoutSec * 1000)) {
        Write-Host "TIMEOUT after ${TimeoutSec}s - killing process tree pid=$($p.Id)"
        try { & taskkill /PID $p.Id /T /F | Out-Null } catch { }
        # taskkill returns before the JVM has released its file handles (mod jars stay locked for a
        # moment); wait for the process object so the caller can swap the mods back immediately.
        if (-not $p.WaitForExit(30000)) { Write-Host 'WARNING: process did not exit within 30s of taskkill' }
        $exitCode = 124
    } else {
        $exitCode = $p.ExitCode
    }
} else {
    $p.WaitForExit()
    $exitCode = $p.ExitCode
}
$elapsed = [int]((Get-Date) - $startedAt).TotalSeconds
Write-Host "exit code: $exitCode  (wall ${elapsed}s)"
exit $exitCode
