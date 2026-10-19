param([string]$Destination = '')
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
if (!$Destination) { $Destination = Join-Path (Split-Path $project -Parent) 'zongce-shouji-github' }
$Destination = [IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $Destination) { throw 'Destination already exists. Choose a new empty destination; existing files are never overwritten.' }

# Copy an explicit source allowlist, including current uncommitted source changes.
# No Git history, old audit evidence, real workbooks, or local build state is copied.
$files = [Collections.Generic.List[string]]::new()
foreach ($relative in @('.gitignore','README.md','build.gradle.kts','gradle.properties',
    'gradlew','gradlew.bat','settings.gradle.kts','app/build.gradle.kts',
    'gradle/wrapper/gradle-wrapper.jar','gradle/wrapper/gradle-wrapper.properties',
    'docs/PUBLISHING.md','docs/PRIVACY.md')) { $files.Add($relative) }
foreach ($folder in @('app/src','tests','scripts','docs/branding')) {
    foreach ($file in Get-ChildItem (Join-Path $project $folder) -Recurse -File) {
        $relative = [IO.Path]::GetRelativePath($project,$file.FullName)
        $extension = $file.Extension.ToLowerInvariant()
        $allowed = switch ($folder) {
            'app/src' { $extension -in @('.kt','.java','.xml') }
            'tests' { $extension -in @('.kt','.ps1') }
            'scripts' { $extension -eq '.ps1' }
            'docs/branding' { $extension -in @('.md','.svg','.png') }
        }
        if (!$allowed) { throw "Unexpected file type in source allowlist: $relative" }
        $files.Add($relative)
    }
}
foreach ($relative in $files) {
    $source = Join-Path $project $relative
    if (!(Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing source file: $relative" }
}
New-Item -ItemType Directory -Path $Destination | Out-Null
foreach ($relative in $files) {
    $target = Join-Path $Destination $relative
    New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $project $relative) -Destination $target
}
Write-Output "Prepared $($files.Count) source files in $Destination"
