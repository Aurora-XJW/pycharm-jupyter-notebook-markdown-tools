[CmdletBinding()]
param([switch]$PreservePreviewImages)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$taskRoot = $PSScriptRoot
$taskDescriptor = [xml](Get-Content -LiteralPath (Join-Path $taskRoot 'src\main\resources\META-INF\plugin.xml') -Raw)
$taskVersion = [string]$taskDescriptor.'idea-plugin'.version
$taskPluginName = [string]$taskDescriptor.'idea-plugin'.name
$taskPluginSlug = 'pycharm-' + $taskPluginName.ToLowerInvariant().Replace(' ', '-')
if ($taskVersion -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.]+)?$') { throw 'Unsafe plugin version.' }
if ($taskPluginSlug -notmatch '^[a-z0-9]+(?:-[a-z0-9]+)*$') { throw 'The plugin name must produce a safe file name.' }
$taskArtifact = "$taskPluginSlug-$taskVersion"
$taskDocumentName = 'PyCharm_' + $taskPluginName.Replace(' ', '_') + '_' + $taskVersion
$taskDist = Join-Path $taskRoot ('dist\' + $taskVersion)
$taskInstaller = Join-Path $taskDist "$taskArtifact.zip"
$taskReports = Join-Path $taskDist 'reports'
$taskSummaries = @(Get-ChildItem -LiteralPath $taskReports -Filter 'verification-summary-PY-*.txt' -ErrorAction SilentlyContinue)
if (-not (Test-Path -LiteralPath $taskInstaller) -or $taskSummaries.Count -eq 0) {
    throw 'Run build.ps1 and verify.ps1 before packaging a release.'
}
$taskInstallerHash = (Get-FileHash -LiteralPath $taskInstaller -Algorithm SHA256).Hash.ToLowerInvariant()
foreach ($taskSummary in $taskSummaries) {
    if (-not [IO.File]::ReadAllText($taskSummary.FullName).Contains('Plugin SHA256: ' + $taskInstallerHash)) {
        throw ('A verification result is for a different binary. Run verify.ps1 again or move the obsolete summary out of this release directory: ' + $taskSummary.Name)
    }
}
$taskStage = Join-Path $taskRoot ('.build\release-' + [guid]::NewGuid().ToString('N'))
$taskSourceRoot = Join-Path $taskStage "$taskPluginSlug-sources"
$null = New-Item -ItemType Directory -Path $taskSourceRoot -Force
foreach ($taskFile in @('.gitattributes', '.gitignore', 'LICENSE', 'README.md', 'README.zh-CN.md', 'CONTRIBUTING.md', 'EULA.md', 'build.ps1', 'verify.ps1', 'package-release.ps1')) {
    if (Test-Path -LiteralPath (Join-Path $taskRoot $taskFile)) { Copy-Item -LiteralPath (Join-Path $taskRoot $taskFile) -Destination $taskSourceRoot }
}
foreach ($taskDirectory in @('src', 'tools', 'docs', 'assets')) {
    $taskInputDirectory = Join-Path $taskRoot $taskDirectory
    foreach ($taskSourceFile in @(Get-ChildItem -LiteralPath $taskInputDirectory -Recurse -File)) {
        $taskRelative = [IO.Path]::GetRelativePath($taskInputDirectory, $taskSourceFile.FullName)
        if (($taskRelative -split '[\\/]') -contains '__pycache__' -or $taskSourceFile.Extension -in @('.pyc', '.pyo', '.class')) { continue }
        if ($taskDirectory -eq 'tools' -and ($taskRelative -split '[\\/]') -contains 'dependencies' -and $taskSourceFile.Extension -eq '.jar') { continue }
        $taskDestination = Join-Path $taskSourceRoot (Join-Path $taskDirectory $taskRelative)
        $null = New-Item -ItemType Directory -Path (Split-Path -Parent $taskDestination) -Force
        Copy-Item -LiteralPath $taskSourceFile.FullName -Destination $taskDestination
    }
}
$taskOverview = Join-Path $taskRoot "output\html\${taskDocumentName}_Overview.html"
if (Test-Path -LiteralPath $taskOverview) { Copy-Item -LiteralPath $taskOverview -Destination $taskDist -Force }
$taskOverviewPreviews = Join-Path $taskDist 'previews\overview'
$null = New-Item -ItemType Directory -Path $taskOverviewPreviews -Force
$taskOverviewImages = @(
    'image-paste.png', 'text-color-toolbar.png', 'text-color.png', 'text-color-markup.png',
    'insert-menu.png', 'blank-lines.png', 'markdown-table.png'
)
foreach ($taskPreviewName in ($taskOverviewImages + @('preview-sources.txt', 'generated-table-markdown.txt'))) {
    $taskPreview = Join-Path $taskRoot (Join-Path 'docs\overview\assets' $taskPreviewName)
    if (-not (Test-Path -LiteralPath $taskPreview)) { throw "Missing Overview illustration: $taskPreview" }
    if ($PreservePreviewImages -and [IO.Path]::GetExtension($taskPreviewName) -eq '.png') {
        $taskExistingPreview = Join-Path $taskOverviewPreviews $taskPreviewName
        if (-not (Test-Path -LiteralPath $taskExistingPreview)) { throw "Missing preserved Overview image: $taskExistingPreview" }
        if ((Get-FileHash -LiteralPath $taskPreview -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $taskExistingPreview -Algorithm SHA256).Hash) { throw "Preserved Overview image differs from source: $taskPreviewName" }
        continue
    }
    Copy-Item -LiteralPath $taskPreview -Destination $taskOverviewPreviews -Force
}
$taskFullPreviews = Join-Path $taskDist 'previews\overview\full'
$null = New-Item -ItemType Directory -Path $taskFullPreviews -Force
foreach ($taskPreviewName in $taskOverviewImages) {
    $taskFullPreview = Join-Path $taskRoot (Join-Path 'docs\overview\assets\full' $taskPreviewName)
    if (-not (Test-Path -LiteralPath $taskFullPreview)) { throw "Missing full-resolution Overview illustration: $taskFullPreview" }
    if ($PreservePreviewImages) {
        $taskExistingFullPreview = Join-Path $taskFullPreviews $taskPreviewName
        if (-not (Test-Path -LiteralPath $taskExistingFullPreview)) { throw "Missing preserved full-resolution image: $taskExistingFullPreview" }
        if ((Get-FileHash -LiteralPath $taskFullPreview -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $taskExistingFullPreview -Algorithm SHA256).Hash) { throw "Preserved full-resolution image differs from source: $taskPreviewName" }
        continue
    }
    Copy-Item -LiteralPath $taskFullPreview -Destination $taskFullPreviews -Force
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskSourceZip = Join-Path $taskStage "$taskArtifact-sources.zip"
[IO.Compression.ZipFile]::CreateFromDirectory($taskSourceRoot, $taskSourceZip, [IO.Compression.CompressionLevel]::Optimal, $true)
Copy-Item -LiteralPath $taskSourceZip -Destination (Join-Path $taskDist "$taskArtifact-sources.zip") -Force
foreach ($taskFile in @('LICENSE', 'README.md', 'README.zh-CN.md')) {
    if (Test-Path -LiteralPath (Join-Path $taskRoot $taskFile)) { Copy-Item -LiteralPath (Join-Path $taskRoot $taskFile) -Destination $taskDist -Force }
}
$taskHashLines = @(
    Get-ChildItem -LiteralPath $taskDist -Recurse -File | Where-Object { $_.FullName -ne (Join-Path $taskDist 'SHA256SUMS.txt') } | Sort-Object FullName | ForEach-Object {
        $taskRelative = [IO.Path]::GetRelativePath($taskDist, $_.FullName).Replace([IO.Path]::DirectorySeparatorChar, [char]47)
        (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $taskRelative
    }
)
[IO.File]::WriteAllLines((Join-Path $taskDist 'SHA256SUMS.txt'), $taskHashLines, [Text.UTF8Encoding]::new($false))
Get-ChildItem -LiteralPath $taskDist -File | Select-Object Name, Length
Write-Output 'Release packaged. No IDE was started and no plugin was installed. Previous releases preserved.'
