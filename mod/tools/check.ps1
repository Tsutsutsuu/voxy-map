# Fast compile check for the NeoForge 1.21.1 port.
#
# Why not gradlew compileJava? Gradle takes a project lock, so parallel agents would serialise.
# This script runs javac directly against the same classpath gradle uses (build/compile-classpath.txt,
# produced by the `dumpCompileClasspath` task) and writes classes to a scratch dir outside the repo.
#
# Usage:
#   pwsh -NoProfile -ExecutionPolicy Bypass -File mod/tools/check.ps1
#   pwsh -NoProfile -ExecutionPolicy Bypass -File mod/tools/check.ps1 -Filter MapData.java,MapScreen.java
#       -> compiles the whole tree but only prints errors mentioning those file names
#
# javac is forced to English diagnostics (-J-Duser.language=en) because the OS locale is zh-CN.
param(
    [string[]]$Filter = @(),
    [int]$MaxErrors = 400
)

$ErrorActionPreference = 'Stop'
# `pwsh -File check.ps1 -Filter a.java,b.java` binds the comma list as ONE string; split it here so
# both -File and -Command invocation styles behave the same.
if ($Filter.Count -eq 1 -and $Filter[0] -like '*,*') { $Filter = $Filter[0] -split ',' }
$Filter = @($Filter | Where-Object { $_ -and $_.Trim() } | ForEach-Object { $_.Trim() })

$modDir = Split-Path -Parent $PSScriptRoot
$cpFile = Join-Path $modDir 'build\compile-classpath.txt'
if (-not (Test-Path $cpFile)) {
    Write-Host "missing $cpFile - run: .\gradlew.bat dumpCompileClasspath" -ForegroundColor Red
    exit 2
}
$javac = 'C:\Users\Tsutsutsu\.jdks\jdk-21.0.12.1+1\bin\javac.exe'
$cp = (Get-Content $cpFile -Raw).Trim()
$srcRoot = Join-Path $modDir 'src\main\java'
$files = Get-ChildItem -Recurse -File -Path $srcRoot -Filter *.java | ForEach-Object { $_.FullName }
$outDir = Join-Path $env:TEMP 'voxymap-javac-out'
Remove-Item -Recurse -Force $outDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$argFile = Join-Path $env:TEMP 'voxymap-javac-args.txt'
# javac argument files: backslash is an escape character, so use forward slashes; quote for spaces.
($files | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' }) | Set-Content -Path $argFile -Encoding ascii

# Force English javac diagnostics through the JVM env var (the OS locale is zh-CN and PowerShell
# mangles -J-Duser.language=en as a native argument).
$env:JAVA_TOOL_OPTIONS = '-Duser.language=en -Duser.country=US'
$raw = & $javac -proc:none -nowarn -Xmaxerrs $MaxErrors `
    -encoding UTF-8 -d $outDir -cp $cp "@$argFile" 2>&1
$exit = $LASTEXITCODE
$lines = @($raw | ForEach-Object { "$_" })
$lines | Set-Content -Path (Join-Path $env:TEMP 'voxymap-javac-last.log') -Encoding utf8
$errors = $lines | Where-Object { $_ -match ': error:' -or $_ -match '^error:' }
$shown = if ($Filter.Count -gt 0) {
    $errors | Where-Object { $l = $_; ($Filter | Where-Object { $l -like "*$_*" }).Count -gt 0 }
} else { $errors }
Write-Host ("javac exit={0}  errors={1}  shown={2}  filter=[{3}]  (full log: {4})" -f $exit, @($errors).Count, @($shown).Count, ($Filter -join ','), (Join-Path $env:TEMP 'voxymap-javac-last.log'))
if ($Filter.Count -gt 0 -and @($errors).Count -gt 0) {
    $other = $errors | Where-Object { $l = $_; ($Filter | Where-Object { $l -like "*$_*" }).Count -eq 0 }
    Write-Host ("  errors outside filter: {0}" -f @($other).Count)
}
$shown | Select-Object -First $MaxErrors | ForEach-Object { Write-Host $_ }
exit $exit
