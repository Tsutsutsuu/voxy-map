# Launch a real AxolotlLauncher instance without the launcher GUI.
#
# Why: the port needs an in-game smoke test against the real mod set (Voxy through Roxy, Sodium,
# Connector, ...). The launcher is a Tauri app with no scriptable launch entry point, but its
# per-instance version JSON is a complete vanilla-style launch description, so this script rebuilds
# the java command from it.
#
# Usage:
#   pwsh -File mod/tools/launch-instance.ps1 -PrintOnly                # validate the classpath
#   pwsh -File mod/tools/launch-instance.ps1                            # launch, wait, return exit code
#   pwsh -File mod/tools/launch-instance.ps1 -World "New World"        # quick-play a singleplayer world
#   pwsh -File mod/tools/launch-instance.ps1 -ExtraJvmArgs '-Dvoxymap.selftest=true'
param(
    [string]$Instance = 'D:\Minecraft\AxolotlLauncher\Data\profiles\1.21.1-NeoForge 21.1.265',
    [string]$VersionId = '1.21.1-21.1.265',
    [string]$JavaHome = 'D:\Minecraft\AxolotlLauncher\Data\meta\java_versions\zulu25.36.15-ca-jdk25.0.4-win_x64',
    [string]$World = '',
    [string]$Username = 'Tester',
    [int]$Width = 1280,
    [int]$Height = 720,
    [string[]]$ExtraJvmArgs = @(),
    [switch]$PrintOnly,
    [switch]$NoWait
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Instance)) { throw "instance not found: $Instance" }
$dataDir = Split-Path -Parent (Split-Path -Parent (Resolve-Path -LiteralPath $Instance).Path)
$meta = Join-Path $dataDir 'meta'
if (-not (Test-Path -LiteralPath $meta)) { throw "launcher meta not found: $meta" }
$versionDir = Join-Path $meta "versions\$VersionId"
$versionJsonPath = Join-Path $versionDir "$VersionId.json"
if (-not (Test-Path -LiteralPath $versionJsonPath)) { throw "version json not found: $versionJsonPath" }
$v = Get-Content -LiteralPath $versionJsonPath -Raw | ConvertFrom-Json

# ---- rules: keep this simple but correct for windows -------------------------
function Test-Rules($rules) {
    if ($null -eq $rules -or @($rules).Count -eq 0) { return $true }
    $allowed = $false
    foreach ($r in @($rules)) {
        $osName = if ($r.os) { $r.os.name } else { $null }
        if ($osName -and $osName -ne 'windows') { continue }
        $arch = if ($r.os) { $r.os.arch } else { $null }
        if ($arch -and $arch -ne 'x86_64') { continue }
        if ($r.action -eq 'allow') { $allowed = $true }
        elseif ($r.action -eq 'disallow') { return $false }
    }
    return $allowed
}

$libraries = Join-Path $meta 'libraries'
$cpEntries = New-Object System.Collections.Generic.List[string]
$missing = New-Object System.Collections.Generic.List[string]
$nativeJars = New-Object System.Collections.Generic.List[string]
foreach ($lib in $v.libraries) {
    if (-not (Test-Rules $lib.rules)) { continue }
    $art = $lib.downloads.artifact
    # Modrinth-style launchers flag jars that must stay off the classpath (they live on the module
    # path passed via -p, e.g. the newer ASM jars); honour include_in_classpath.
    $include = -not ($lib.PSObject.Properties.Name -contains 'include_in_classpath') -or $lib.include_in_classpath
    if ($art -and $art.path -and $include) {
        $p = Join-Path $libraries $art.path
        if (Test-Path -LiteralPath $p) { $cpEntries.Add($p) } else { $missing.Add($p) }
    }
    $nat = $lib.downloads.classifiers.'natives-windows'
    if ($nat -and $nat.path) {
        $p = Join-Path $libraries $nat.path
        if (Test-Path -LiteralPath $p) { $nativeJars.Add($p) } else { $missing.Add($p) }
    }
}
$clientJar = Join-Path $versionDir "$VersionId.jar"
if (Test-Path -LiteralPath $clientJar) { $cpEntries.Add($clientJar) } else { $missing.Add($clientJar) }
if ($missing.Count -gt 0) {
    Write-Host 'MISSING libraries:' -ForegroundColor Red
    $missing | ForEach-Object { Write-Host "  $_" }
    throw "$($missing.Count) launch files are missing"
}

# ---- natives ----------------------------------------------------------------
$nativesDir = Join-Path $Instance '.voxymap-launch\natives'
New-Item -ItemType Directory -Force -Path $nativesDir | Out-Null
foreach ($jar in $nativeJars) {
    Push-Location $nativesDir
    try { & (Join-Path $JavaHome 'bin\jar.exe') xf $jar 2>$null } finally { Pop-Location }
}
# NeoForge/1.21.1 also ships lwjgl natives as ordinary classpath jars; the library path covers the
# ones extracted above, and LWJGL falls back to extracting from the classpath itself.

$assetsDir = Join-Path $meta 'assets'
$assetIndex = if ($v.assetIndex) { $v.assetIndex.id } else { $v.assets }
$libDir = $libraries
$repl = @{
    '${natives_directory}'    = $nativesDir
    '${launcher_name}'        = 'voxymap-test'
    '${launcher_version}'     = '1'
    '${classpath}'            = ($cpEntries -join ';')
    '${classpath_separator}'  = ';'
    '${library_directory}'    = $libDir
    '${version_name}'         = $VersionId
    '${auth_player_name}'     = $Username
    '${game_directory}'       = (Resolve-Path -LiteralPath $Instance).Path
    '${assets_root}'          = $assetsDir
    '${assets_index_name}'    = $assetIndex
    '${auth_uuid}'            = '00000000000000000000000000000000'
    '${auth_access_token}'    = '0'
    '${clientid}'             = '0'
    '${auth_xuid}'            = '0'
    '${user_type}'            = 'legacy'
    '${version_type}'         = 'release'
    '${user_properties}'      = '{}'
}
function Expand-Arg([string]$s) {
    foreach ($k in $repl.Keys) { $s = $s.Replace($k, [string]$repl[$k]) }
    return $s
}

$jvm = New-Object System.Collections.Generic.List[string]
foreach ($a in $v.arguments.jvm) {
    if ($a -is [string]) { $jvm.Add((Expand-Arg $a)) ; continue }
    if (Test-Rules $a.rules) { foreach ($val in @($a.value)) { $jvm.Add((Expand-Arg $val)) } }
}
foreach ($extra in $ExtraJvmArgs) { $jvm.Add($extra) }
# sane dev/test defaults: no inventory of the user's real options is touched, but we do want a window
$jvm.Add('-Dvoxymap.launchinstance=true')

$game = New-Object System.Collections.Generic.List[string]
$skipFeatureArgs = @('--demo', '--quickPlayPath', '--quickPlaySingleplayer', '--quickPlayMultiplayer', '--quickPlayRealms', '--width', '--height')
$i = 0
$gameArgsRaw = $v.arguments.game
while ($i -lt $gameArgsRaw.Count) {
    $a = $gameArgsRaw[$i]
    if ($a -is [string]) {
        if ($a -in @('--width', '--height')) { $i += 2; continue }
        $game.Add((Expand-Arg $a)); $i++; continue
    }
    # rule-gated groups: skip quickplay/demo/resolution, keep everything else
    $vals = @($a.value)
    $isQuickPlay = $false
    foreach ($x in $vals) { if ($x -like '*quickPlay*' -or $x -eq '--demo' -or $x -eq '--width' -or $x -eq '--height') { $isQuickPlay = $true } }
    if (-not $isQuickPlay -and (Test-Rules $a.rules)) { foreach ($x in $vals) { $game.Add((Expand-Arg $x)) } }
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

# Start-Process -ArgumentList takes a single command line: quote every argument that contains a
# space, otherwise paths such as the instance directory ("...\1.21.1-NeoForge 21.1.265") are split
# and the JVM reports "could not find or load main class" for the next fragment.
function Quote-Arg([string]$s) {
    if ($s -match '[\s"]') { return '"' + $s + '"' }
    return $s
}
$argLine = ($argList | ForEach-Object { Quote-Arg $_ }) -join ' '

$logFile = Join-Path $Instance '.voxymap-launch\console.log'
New-Item -ItemType Directory -Force -Path (Split-Path $logFile) | Out-Null
if ($PrintOnly) {
    Write-Host "java: $java"
    Write-Host "command: $java $argLine"
    Write-Host "classpath entries: $($cpEntries.Count)  natives: $($nativeJars.Count)  missing: $($missing.Count)"
    Write-Host "log: $logFile"
    Write-Host "main: $($v.mainClass)"
    Write-Host "game args: $($game -join ' ')"
    exit 0
}

Write-Host "launching $VersionId (world: $(if ($World) { $World } else { '<none>' })) ..."
$p = Start-Process -FilePath $java -ArgumentList $argLine -WorkingDirectory $Instance -PassThru `
    -RedirectStandardOutput $logFile -RedirectStandardError "$logFile.err"
if ($NoWait) { Write-Host "pid=$($p.Id)"; exit 0 }
$p.WaitForExit()
Write-Host "exit code: $($p.ExitCode)"
exit $p.ExitCode
