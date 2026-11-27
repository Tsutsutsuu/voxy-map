# Offline compile (and optionally pack) helper for the NeoForge 1.21.1 port.
#
# Why: the Gradle build needs net.neoforged/maven artifacts that may be unreachable on this network
# (maven.neoforged.net handshake fails / is very slow), and a missing Gradle 9.2.1 distribution blocks
# the wrapper. This script compiles mod/src against the jars that ARE on disk:
#   - mod/build/moddev/artifacts/neoforge-<ver>-minecraft.jar  (patched MC + neoforge classes)
#   - launcher runtime libraries (D:\Minecraft\...\Data\meta\libraries) resolved from the installed
#     NeoForge version JSON, with version substitution for the few compile-time jars Gradle had cached
#   - mod/libs/rocksdbjni + the zstd-jni jar from the Gradle cache
# and can then produce a runnable test jar by replacing com/voxymap/*.class inside the last built jar
# (resources, META-INF and nested jarJar payloads are kept from that base jar).
#
# Usage:
#   pwsh -File mod/tools/build-offline.ps1                 # compile only
#   pwsh -File mod/tools/build-offline.ps1 -PackJar        # also write build/libs/<name>-offline.jar
#   pwsh -File mod/tools/build-offline.ps1 -Filter LiveTileStore,MapData
param(
    [switch]$PackJar,
    [string]$BaseJar = '',
    [string[]]$Filter = @()
)

$ErrorActionPreference = 'Stop'
if ($Filter.Count -eq 1 -and $Filter[0] -like '*,*') { $Filter = $Filter[0] -split ',' }

$modDir = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$srcRoot = Join-Path $modDir 'src\main\java'
$resRoot = Join-Path $modDir 'src\main\resources'
$buildDir = Join-Path $modDir 'build'
$classesDir = Join-Path $buildDir 'offline-classes'
$cpFile = Join-Path $buildDir 'offline-classpath.txt'
$metaDir = 'D:\Minecraft\AxolotlLauncher\Data\meta'
$librariesDir = Join-Path $metaDir 'libraries'
$versionId = '1.21.1-21.1.265'
$jdk = 'D:\Minecraft\AxolotlLauncher\Data\meta\java_versions\zulu25.36.15-ca-jdk25.0.4-win_x64'
$javac = Join-Path $jdk 'bin\javac.exe'
$jarTool = Join-Path $jdk 'bin\jar.exe'
if (-not (Test-Path $javac)) { throw "javac not found: $javac" }

# ---- 1. launcher libraries from the installed version json (and its parent) -------------------
$cp = New-Object System.Collections.Generic.List[string]
$missing = New-Object System.Collections.Generic.List[string]
$jsonPath = Join-Path $metaDir "versions\$versionId\$versionId.json"
$v = Get-Content -LiteralPath $jsonPath -Raw | ConvertFrom-Json
$jsons = @($v)
if ($v.inheritsFrom) {
    $parentPath = Join-Path $metaDir "versions\$($v.inheritsFrom)\$($v.inheritsFrom).json"
    if (Test-Path -LiteralPath $parentPath) { $jsons += (Get-Content -LiteralPath $parentPath -Raw | ConvertFrom-Json) }
}
foreach ($j in $jsons) {
    foreach ($lib in @($j.libraries)) {
        $path = $lib.downloads.artifact.path
        if (-not $path -or $path -notlike '*.jar') { continue }
        $full = Join-Path $librariesDir $path
        if (Test-Path -LiteralPath $full) { $cp.Add($full) } else { $missing.Add($path) }
    }
}

# ---- 2. compile-time jars that Gradle had cached: substitute from the launcher libraries ---------
function Find-Substitute([string]$originalPath) {
    # .../<hash>/<artifact>-<version>.jar  ->  artifact + version
    $file = Split-Path -Leaf $originalPath
    $noExt = $file -replace '\.jar$', ''
    $m = [regex]::Match($noExt, '^(?<name>.+?)-(?<ver>\d+(?:[.\-][0-9A-Za-z]+)*)$')
    if (-not $m.Success) { return $null }
    $name = $m.Groups['name'].Value
    $cand = @(Get-ChildItem -LiteralPath $librariesDir -Recurse -Filter "$name-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "[\\/]$([regex]::Escape($name))[\\/]" })
    if ($cand.Count -eq 0) { return $null }
    # pick the lexicographically highest version (good enough for 21.1.x patch bumps)
    return ($cand | Sort-Object { [regex]::Match($_.Name, '-(?<v>\d+(?:[.\-][0-9A-Za-z]+)*)\.jar$').Groups['v'].Value } |
        Select-Object -Last 1).FullName
}

$oldCpFile = Join-Path $buildDir 'compile-classpath.txt'
if (Test-Path -LiteralPath $oldCpFile) {
    foreach ($entry in ((Get-Content -LiteralPath $oldCpFile -Raw).Trim() -split ';')) {
        if (-not $entry) { continue }
        if (Test-Path -LiteralPath $entry) { if (-not $cp.Contains($entry)) { $cp.Add($entry) }; continue }
        $sub = Find-Substitute $entry
        if ($sub) {
            if (-not $cp.Contains($sub)) { $cp.Add($sub) }
        } else {
            Write-Host "  (no substitute) $(Split-Path -Leaf $entry)"
        }
    }
}

# ---- 3. patched MC/neoforge classes + mod-local libs -----------------------------------------
$moddevArtifacts = Join-Path $buildDir 'moddev\artifacts'
$minecraftJar = @(Get-ChildItem -LiteralPath $moddevArtifacts -Filter 'neoforge-*-minecraft.jar' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
if (-not $minecraftJar) { throw "patched MC jar not found in $moddevArtifacts" }
if (-not $cp.Contains($minecraftJar)) { $cp.Add($minecraftJar) }
$rocksdb = Join-Path $modDir 'libs\rocksdbjni-10.2.1.jar'
if (Test-Path $rocksdb) { $cp.Add($rocksdb) }
$zstd = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\com.github.luben\zstd-jni') -Recurse -Filter 'zstd-jni-*.jar' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($zstd) { $cp.Add($zstd.FullName) } else { Write-Host '  WARNING: zstd-jni jar not found' }

$cpText = ($cp -join ';')
Set-Content -LiteralPath $cpFile -Value $cpText -Encoding ASCII
Write-Host ("classpath: {0} jars ({1} missing paths had no substitute)" -f $cp.Count, $missing.Count)

# ---- 4. compile -------------------------------------------------------------------------------
$files = Get-ChildItem -LiteralPath $srcRoot -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
$argFile = Join-Path $env:TEMP 'voxymap-neoforge-javac-args.txt'
($files | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' }) | Set-Content -Path $argFile -Encoding ASCII
Remove-Item -LiteralPath $classesDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Duser.country=US'
$raw = & $javac -proc:none -nowarn -Xmaxerrs 300 -encoding UTF-8 -d $classesDir -cp $cpText "@$argFile" 2>&1
$exit = $LASTEXITCODE
$lines = @($raw | ForEach-Object { "$_" })
$lines | Set-Content -Path (Join-Path $env:TEMP 'voxymap-neoforge-javac-last.log') -Encoding utf8
$errors = @($lines | Where-Object { $_ -match ': error:' -or $_ -match '^error:' })
$shown = if ($Filter.Count -gt 0) { @($errors | Where-Object { $l = $_; ($Filter | Where-Object { $l -like "*$_*" }).Count -gt 0 }) } else { $errors }
Write-Host ("javac exit={0} errors={1} shown={2} (full log: {3})" -f $exit, $errors.Count, $shown.Count, (Join-Path $env:TEMP 'voxymap-neoforge-javac-last.log'))
$shown | Select-Object -First 80 | ForEach-Object { Write-Host $_ }
if ($exit -ne 0) { exit $exit }

# ---- 5. optional pack: replace com/voxymap classes inside the last built jar -------------------
if ($PackJar) {
    if (-not $BaseJar) {
        $BaseJar = @(Get-ChildItem -LiteralPath (Join-Path $buildDir 'libs') -Filter 'voxy-map-*.jar' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
    }
    if (-not $BaseJar -or -not (Test-Path -LiteralPath $BaseJar)) { throw 'no base jar for -PackJar' }
    $name = [System.IO.Path]::GetFileNameWithoutExtension($BaseJar) + '-offline.jar'
    $outJar = Join-Path $buildDir "libs\$name"
    Copy-Item -LiteralPath $BaseJar -Destination $outJar -Force
    # jar uf replaces entries whose names already exist; resources/META-INF/nested jars stay as-is.
    & $jarTool uf $outJar -C $classesDir .
    if ($LASTEXITCODE -ne 0) { throw "jar update failed (exit $LASTEXITCODE)" }
    Write-Host "packed: $outJar"
}
exit 0
