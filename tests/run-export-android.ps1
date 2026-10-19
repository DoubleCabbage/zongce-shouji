param([Parameter(Mandatory=$true)][string]$Serial,
    [string]$AndroidSdk = "$env:USERPROFILE/AppData/Local/Android/Sdk",
    [string[]]$Phases = @('suite','ui','grouping-ui'))
$ErrorActionPreference = 'Stop'
$adb = Join-Path $AndroidSdk 'platform-tools/adb.exe'
$project = Split-Path $PSScriptRoot -Parent
if ($Serial -notmatch '^(emulator-\d+|localhost:\d+|127\.0\.0\.1:\d+)$' -or
    (& $adb -s $Serial shell getprop ro.kernel.qemu).Trim() -ne '1') { throw 'Explicit isolated emulator required.' }
foreach ($apk in @('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
    $result = & $adb -s $Serial install -r (Join-Path $project $apk)
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'Success') { throw "Installation failed $apk" }
}
foreach ($phase in $Phases) {
    if ($phase -notin @('suite','ui','grouping-ui')) { throw 'Unknown phase; before is reserved for the baseline APK.' }
    $log = Join-Path $project "build/p1-10a-$phase.log"
    $result = & $adb -s $Serial shell am instrument -w -r -e exportPhase $phase cn.activityscore.app.test/cn.activityscore.app.tests.NumericPersistenceInstrumentation 2>&1
    $result | Set-Content -LiteralPath $log -Encoding utf8
    if (($result -join "`n") -notmatch 'failures=0' -or ($result -join "`n") -notmatch "P1-10A $phase passed") { throw "Export $phase failed; see $log" }
    if ($phase -in @('ui','grouping-ui')) {
        $artifacts = Join-Path $project 'build/p1-10b-ui-artifacts'
        New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
        $files = @('summary.png','saved-ui.xlsx')
        if ($phase -eq 'grouping-ui') { $files += 'split.png' }
        foreach ($name in $files) {
            $encoded = & $adb -s $Serial shell run-as cn.activityscore.app base64 "app_p110a-export-ui/cache/$name"
            if ($LASTEXITCODE -ne 0) { throw "Cannot collect $phase $name" }
            [IO.File]::WriteAllBytes((Join-Path $artifacts "$phase-$name"), [Convert]::FromBase64String(($encoded -join '')))
        }
    }
    Write-Output ($result | Where-Object { $_ -match 'passed' })
}
