[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$IdeHome,
    [string]$RuntimeHome,
    [string]$VerifierJar = (Join-Path $PSScriptRoot 'tools\dependencies\verifier-cli-1.410-all.jar')
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$taskRoot = $PSScriptRoot
$taskDescriptor = [xml](Get-Content -LiteralPath (Join-Path $taskRoot 'src\main\resources\META-INF\plugin.xml') -Raw)
$taskVersion = [string]$taskDescriptor.'idea-plugin'.version
$taskPluginName = [string]$taskDescriptor.'idea-plugin'.name
$taskPluginSlug = 'pycharm-' + $taskPluginName.ToLowerInvariant().Replace(' ', '-')
if ($taskVersion -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?$') { throw 'Unsafe plugin version.' }
if ($taskPluginSlug -notmatch '^[a-z0-9]+(?:-[a-z0-9]+)*$') { throw 'The plugin name must produce a safe file name.' }
$taskDist = Join-Path $taskRoot ('dist\' + $taskVersion)
$taskReports = Join-Path $taskDist 'reports'
$taskIde = (Resolve-Path -LiteralPath $IdeHome).Path
$taskInfo = Get-Content -LiteralPath (Join-Path $taskIde 'product-info.json') -Raw | ConvertFrom-Json
$taskTarget = $taskInfo.productCode + '-' + $taskInfo.buildNumber
$taskPlugin = Join-Path $taskDist "$taskPluginSlug-$taskVersion.zip"
if (-not (Test-Path -LiteralPath $taskPlugin)) { throw 'Build the plugin before verifying.' }
if (-not (Test-Path -LiteralPath $VerifierJar)) {
    throw 'Download the official verifier-cli-1.410-all.jar from https://github.com/JetBrains/intellij-plugin-verifier/releases/tag/1.410 into tools/dependencies, or pass -VerifierJar. This script does not download anything.'
}
if (-not $RuntimeHome) { $RuntimeHome = Join-Path $taskIde 'jbr' }
$taskRuntime = (Resolve-Path -LiteralPath $RuntimeHome).Path
$taskVerifier = (Resolve-Path -LiteralPath $VerifierJar).Path
$taskHome = Join-Path $taskRoot ('.build\verifier-home\' + $taskTarget)
$taskReport = Join-Path $taskRoot ('.build\verifier-reports\run-' + [guid]::NewGuid().ToString('N'))
$taskNoSocketDir = Join-Path $taskRoot '.build\no-afunix-sockets\absent'
if (Test-Path -LiteralPath $taskNoSocketDir) { throw 'The JBR fallback expects a nonexistent Unix socket directory.' }
$null = New-Item -ItemType Directory -Path $taskHome, $taskReport, $taskReports -Force
$taskLog = Join-Path $taskHome ('console-' + [guid]::NewGuid().ToString('N') + '.txt')
$taskJava = Join-Path $taskRuntime 'bin\java.exe'
# Only this verification process falls back to TCP loopback. No JDK/IDE configuration is modified.
& $taskJava '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Djava.net.preferIPv4Stack=true' ("-Djdk.net.unixdomain.tmpdir=" + $taskNoSocketDir) ("-Dplugin.verifier.home.dir=" + $taskHome) '-Xmx2048m' -jar $taskVerifier check-plugin $taskPlugin $taskIde -runtime-dir $taskRuntime -verification-reports-dir $taskReport -offline *> $taskLog
$taskVerifierExit = $LASTEXITCODE
Get-Content -LiteralPath $taskLog -Tail 20
if ($taskVerifierExit -ne 0) { throw "Verifier process failed. See $taskLog" }
$taskVerdicts = @(Get-ChildItem -LiteralPath $taskReport -Recurse -Filter 'verification-verdict.txt')
if ($taskVerdicts.Count -ne 1) { throw "No unique verification verdict was produced. See $taskLog" }
$taskVerdict = Get-Content -LiteralPath $taskVerdicts[0].FullName -Raw
if ($taskVerdict -notmatch '(?im)^\s*Compatible\s*$') { throw "Plugin was not reported compatible: $taskVerdict" }
$taskHash = (Get-FileHash -LiteralPath $taskPlugin -Algorithm SHA256).Hash.ToLowerInvariant()
$taskUtf8 = [Text.UTF8Encoding]::new($false)
$taskMode = if (Test-Path -LiteralPath (Join-Path $taskIde 'download-provenance.json')) { 'offline; official SDK subset (core and Notebook/Jupyter/Markdown JARs); see SDK provenance' } else { 'offline; all dependencies read from the existing local PyCharm distribution' }
$taskLines = @(
    "$taskPluginName $taskVersion",
    ('Target IDE: ' + $taskTarget),
    'Verifier: JetBrains IntelliJ Plugin Verifier 1.410',
    'Verdict: Compatible',
    ('Plugin SHA256: ' + $taskHash),
    ('Mode: ' + $taskMode),
    'No IDE was launched. No plugin was installed.',
    'This is a static binary compatibility check; it does not exercise IDE interaction.',
    'SDK-layout/missing unrelated bundled-module warnings may occur; consult the full report.'
)
[IO.File]::WriteAllLines((Join-Path $taskReports "verification-summary-$taskTarget.txt"), $taskLines, $taskUtf8)
Copy-Item -LiteralPath $taskLog -Destination (Join-Path $taskReports "verifier-console-$taskTarget.txt") -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskReportZip = Join-Path $taskReport "compatibility-report-$taskTarget.zip"
$taskIdeReport = Join-Path $taskReport $taskTarget
[IO.Compression.ZipFile]::CreateFromDirectory($taskIdeReport, $taskReportZip, [IO.Compression.CompressionLevel]::Optimal, $false)
Copy-Item -LiteralPath $taskReportZip -Destination (Join-Path $taskReports "compatibility-report-$taskTarget.zip") -Force
Write-Output "Compatible: $taskTarget. No IDE installation performed."
