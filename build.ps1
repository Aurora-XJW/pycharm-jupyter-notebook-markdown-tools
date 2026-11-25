[CmdletBinding()]
param(
    [string]$IdeHome = $env:PYCHARM_HOME,
    [string]$RuntimeHome,
    [switch]$SkipTests,
    [switch]$CompileOnly,
    [switch]$TestOnly
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$taskRoot = $PSScriptRoot
if ($TestOnly -and ($SkipTests -or $CompileOnly)) { throw '-TestOnly cannot be combined with -SkipTests or -CompileOnly.' }
$taskDescriptor = [xml](Get-Content -LiteralPath (Join-Path $taskRoot 'src\main\resources\META-INF\plugin.xml') -Raw)
$taskVersion = [string]$taskDescriptor.'idea-plugin'.version
$taskPluginName = [string]$taskDescriptor.'idea-plugin'.name
$taskPluginSlug = 'pycharm-' + $taskPluginName.ToLowerInvariant().Replace(' ', '-')
if ($taskVersion -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?$') { throw 'The plugin descriptor must contain a safe semantic version.' }
if ($taskPluginSlug -notmatch '^[a-z0-9]+(?:-[a-z0-9]+)*$') { throw 'The plugin name must produce a safe file name.' }
$taskArtifact = "$taskPluginSlug-$taskVersion"
if (-not $IdeHome) {
    $taskRegistry = Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*', 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue |
        Where-Object { $_.PSObject.Properties['DisplayName'] -and $_.PSObject.Properties['InstallLocation'] -and $_.DisplayName -eq 'PyCharm 2025.3' -and $_.InstallLocation } |
        Select-Object -First 1
    if ($taskRegistry) { $IdeHome = $taskRegistry.InstallLocation }
}
if (-not $IdeHome) { throw 'Specify -IdeHome with an existing PyCharm SDK directory.' }
$taskIde = (Resolve-Path -LiteralPath $IdeHome).Path
$taskInfo = Get-Content -LiteralPath (Join-Path $taskIde 'product-info.json') -Raw | ConvertFrom-Json
$taskBranch = [int]($taskInfo.buildNumber.Split('.')[0])
if ($taskInfo.productCode -ne 'PY' -or $taskBranch -lt 243 -or $taskBranch -gt 253) {
    throw 'The build SDK must be PyCharm 2024.3 through 2025.3 (243-253). Other branches need separate validation.'
}
if (-not $RuntimeHome) { $RuntimeHome = Join-Path $taskIde 'jbr' }
$taskRuntime = (Resolve-Path -LiteralPath $RuntimeHome).Path
$taskJava = Join-Path $taskRuntime 'bin\java.exe'
$taskJavac = Join-Path $taskRuntime 'bin\javac.exe'
if (-not (Test-Path -LiteralPath $taskJavac)) { throw 'Specify -RuntimeHome with a Java 21+ runtime containing javac.exe.' }
$taskBuild = Join-Path $taskRoot '.build'
$taskRun = Join-Path $taskBuild ('run-' + [guid]::NewGuid().ToString('N'))
$taskClasses = Join-Path $taskRun 'classes'
$taskTestClasses = Join-Path $taskRun 'test-classes'
$taskDist = Join-Path $taskRoot ('dist\' + $taskVersion)
$null = New-Item -ItemType Directory -Path $taskClasses, $taskTestClasses -Force
$taskSdkJars = @(Get-ChildItem -LiteralPath (Join-Path $taskIde 'lib') -Filter '*.jar')
foreach ($taskPlugin in @('notebooks-plugin', 'jupyter-plugin', 'markdown')) {
    $taskPluginLib = Join-Path $taskIde "plugins\$taskPlugin\lib"
    if (-not (Test-Path -LiteralPath $taskPluginLib)) { throw "Missing bundled dependency: $taskPlugin" }
    $taskSdkJars += @(Get-ChildItem -LiteralPath $taskPluginLib -Recurse -Filter '*.jar')
}
$taskClasspath = $taskSdkJars.FullName -join ';'
$taskUtf8 = [Text.UTF8Encoding]::new($false)
function Invoke-JavaCompile([string]$SourceDirectory, [string]$OutputDirectory, [string]$Classpath, [string]$ArgumentFile) {
    $taskSources = @(Get-ChildItem -LiteralPath $SourceDirectory -Recurse -Filter '*.java' | Sort-Object FullName)
    if ($taskSources.Count -eq 0) { throw "No Java sources in $SourceDirectory" }
    $taskLines = @('--release', '21', '-encoding', 'UTF-8', '-proc:none', '-Xlint:unchecked',
        '-classpath', ('"' + $Classpath.Replace('\', '/') + '"'),
        '-d', ('"' + $OutputDirectory.Replace('\', '/') + '"'))
    $taskLines += @($taskSources | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
    [IO.File]::WriteAllLines($ArgumentFile, $taskLines, $taskUtf8)
    & $taskJavac '-J-Dfile.encoding=UTF-8' ("@" + $ArgumentFile)
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
}
Write-Output "Compile plugin $taskVersion against PyCharm $($taskInfo.buildNumber)"
Invoke-JavaCompile (Join-Path $taskRoot 'src\main\java') $taskClasses $taskClasspath (Join-Path $taskRun 'main.args')
foreach ($taskResource in Get-ChildItem -LiteralPath (Join-Path $taskRoot 'src\main\resources')) {
    Copy-Item -LiteralPath $taskResource.FullName -Destination $taskClasses -Recurse
}
Copy-Item -LiteralPath (Join-Path $taskRoot 'LICENSE') -Destination (Join-Path $taskClasses 'META-INF\LICENSE')
$taskCompileRecord = [ordered]@{ version=$taskVersion; target=('PY-' + $taskInfo.buildNumber); classes=$taskClasses; sdk=$taskIde; runtime=$taskRuntime; javaRelease=21; installed=$false }
[IO.File]::WriteAllText((Join-Path $taskRun 'compile-record.json'), ($taskCompileRecord | ConvertTo-Json), $taskUtf8)
if ($CompileOnly) { Write-Output "Compilation passed: $taskClasses"; return }
if (-not $SkipTests) {
    Invoke-JavaCompile (Join-Path $taskRoot 'src\test\java') $taskTestClasses "$taskClasses;$taskClasspath" (Join-Path $taskRun 'test.args')
    $taskTestOutput = Join-Path $taskRun 'test-output'
    & $taskJava '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Djava.awt.headless=true' '-ea' '-cp' "$taskTestClasses;$taskClasses;$taskClasspath" 'dev.local.notebookimages.tests.AllTests' $taskTestOutput $taskIde
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed. No distribution was produced by this run.' }
}
if ($TestOnly) {
    Write-Output "Regression tests passed: $taskTestOutput"
    Write-Output 'The installer and published verification records were not modified.'
    Write-Output 'No plugin was installed. No IDE was launched.'
    return
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskPackage = Join-Path $taskRun 'package'
$taskPackageLib = Join-Path $taskPackage "$taskPluginSlug\lib"
$taskReports = Join-Path $taskDist 'reports'
$taskPreviews = Join-Path $taskDist 'previews'
$null = New-Item -ItemType Directory -Path $taskPackageLib, $taskDist, $taskReports, $taskPreviews -Force
$taskJar = Join-Path $taskPackageLib "$taskArtifact.jar"
[IO.Compression.ZipFile]::CreateFromDirectory($taskClasses, $taskJar, [IO.Compression.CompressionLevel]::Optimal, $false)
$taskZip = Join-Path $taskDist "$taskArtifact.zip"
$taskTemporaryZip = Join-Path $taskRun "$taskArtifact.zip"
[IO.Compression.ZipFile]::CreateFromDirectory($taskPackage, $taskTemporaryZip, [IO.Compression.CompressionLevel]::Optimal, $false)
Copy-Item -LiteralPath $taskTemporaryZip -Destination $taskZip -Force
$taskHash = (Get-FileHash -LiteralPath $taskZip -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $taskDist 'SHA256SUMS.txt'), ($taskHash + "  $taskArtifact.zip" + [Environment]::NewLine), $taskUtf8)
Copy-Item -LiteralPath (Join-Path $taskRun 'compile-record.json') -Destination (Join-Path $taskReports 'build-record.json') -Force
if (-not $SkipTests) {
    foreach ($taskOutput in @(Get-ChildItem -LiteralPath $taskTestOutput -File)) {
        $taskOutputDirectory = if ($taskOutput.Extension -eq '.png') { $taskPreviews } else { $taskReports }
        Copy-Item -LiteralPath $taskOutput.FullName -Destination $taskOutputDirectory -Force
    }
}
Write-Output "Build complete: $taskZip"
Write-Output "SHA256: $taskHash"
Write-Output 'No plugin was installed. No IDE was launched. Previous release archives were preserved.'
