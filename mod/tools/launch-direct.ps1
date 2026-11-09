# Launch the game in the foreground of the current PowerShell process (used by the harness in a
# background job). Start-Process + redirected pipes was killing the JVM with CTRL_C on this setup,
# so this variant uses the call operator and lets PowerShell own the console handles.
param(
    [string]$Instance = 'D:\Minecraft\AxolotlLauncher\Data\profiles\1.21.1-NeoForge 21.1.265',
    [string]$VersionId = '1.21.1-21.1.265',
    [string]$JavaHome = 'D:\Minecraft\AxolotlLauncher\Data\meta\java_versions\zulu25.36.15-ca-jdk25.0.4-win_x64',
    [string]$World = '',
    [string]$Username = 'Tester',
    [int]$Width = 1280,
    [int]$Height = 720,
    [string[]]$ExtraJvmArgs = @()
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandArgumentPassing = 'Standard'
if (-not (Test-Path -LiteralPath $Instance)) { throw "instance not found: $Instance" }
$dataDir = Split-Path -Parent (Split-Path -Parent (Resolve-Path -LiteralPath $Instance).Path)
$meta = Join-Path $dataDir 'meta'
$versionDir = Join-Path $meta "versions\$VersionId"
$v = Get-Content -LiteralPath (Join-Path $versionDir "$VersionId.json") -Raw | ConvertFrom-Json

function Test-Rules($rules) {
    if ($null -eq $rules -or @($rules).Count -eq 0) { return $true }
    $allowed = $false
    foreach ($r in @($rules)) {
        if ($r.os.name -and $r.os.name -ne 'windows') { continue }
        if ($r.os.arch -and $r.os.arch -ne 'x86_64') { continue }
        if ($r.action -eq 'allow') { $allowed = $true }
        elseif ($r.action -eq 'disallow') { return $false }
    }
    return $allowed
}

$libraries = Join-Path $meta 'libraries'
$cp = New-Object System.Collections.Generic.List[string]
foreach ($lib in $v.libraries) {
    if (-not (Test-Rules $lib.rules)) { continue }
    $art = $lib.downloads.artifact
    $include = -not ($lib.PSObject.Properties.Name -contains 'include_in_classpath') -or $lib.include_in_classpath
    if ($art -and $art.path -and $include) { $cp.Add((Join-Path $libraries $art.path)) }
}
$cp.Add((Join-Path $versionDir "$VersionId.jar"))
$nativesDir = Join-Path $Instance '.voxymap-launch\natives'
New-Item -ItemType Directory -Force -Path $nativesDir | Out-Null
$assetsDir = Join-Path $meta 'assets'

$repl = @{
    '${natives_directory}' = $nativesDir
    '${launcher_name}' = 'voxymap-test'
    '${launcher_version}' = '1'
    '${classpath}' = ($cp -join ';')
    '${classpath_separator}' = ';'
    '${library_directory}' = $libraries
    '${version_name}' = $VersionId
}
function Expand-Arg([string]$s) {
    foreach ($k in $repl.Keys) { $s = $s.Replace($k, [string]$repl[$k]) }
    return $s
}

$jvm = New-Object System.Collections.Generic.List[string]
foreach ($a in $v.arguments.jvm) {
    if ($a -is [string]) { $jvm.Add((Expand-Arg $a)); continue }
    if (Test-Rules $a.rules) { foreach ($x in @($a.value)) { $jvm.Add((Expand-Arg $x)) } }
}
foreach ($extra in $ExtraJvmArgs) { $jvm.Add($extra) }

$game = New-Object System.Collections.Generic.List[string]
$game.Add('--username'); $game.Add($Username)
$game.Add('--version'); $game.Add($VersionId)
$game.Add('--gameDir'); $game.Add((Resolve-Path -LiteralPath $Instance).Path)
$game.Add('--assetsDir'); $game.Add($assetsDir)
$game.Add('--assetIndex'); $game.Add([string]$v.assetIndex.id)
$game.Add('--uuid'); $game.Add('00000000000000000000000000000000')
$game.Add('--accessToken'); $game.Add('0')
$game.Add('--clientId'); $game.Add('0')
$game.Add('--xuid'); $game.Add('0')
$game.Add('--userType'); $game.Add('legacy')
$game.Add('--versionType'); $game.Add('release')
$game.Add('--width'); $game.Add("$Width")
$game.Add('--height'); $game.Add("$Height")
if ($World) { $game.Add('--quickPlaySingleplayer'); $game.Add($World) }
# NeoForge launch-target args live in the json's rule-gated groups; append the constants directly.
$game.Add('--fml.neoForgeVersion'); $game.Add('21.1.265')
$game.Add('--fml.fmlVersion'); $game.Add('4.0.45')
$game.Add('--fml.mcVersion'); $game.Add('1.21.1')
$game.Add('--fml.neoFormVersion'); $game.Add('20240808.144430')
$game.Add('--launchTarget'); $game.Add('forgeclient')

$all = @()
$all += $jvm.ToArray()
$all += $v.mainClass
$all += $game.ToArray()

$java = Join-Path $JavaHome 'bin\java.exe'
Write-Host "launching with $($all.Count) args (world: $(if ($World) { $World } else { '<none>' }))"
# Run with the instance as the JVM working directory: several mods and the log appender resolve
# relative paths (logs/, config/, cache/) against user.dir, not against --gameDir.
Push-Location -LiteralPath $Instance
try { & $java @all } finally { Pop-Location }
Write-Host "exit code: $LASTEXITCODE"
exit $LASTEXITCODE
