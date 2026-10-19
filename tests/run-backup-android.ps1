param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$AndroidSdk = "$env:USERPROFILE/AppData/Local/Android/Sdk"
)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $AndroidSdk 'platform-tools/adb.exe'
$project = Split-Path $PSScriptRoot -Parent
if ($Serial -notmatch '^(emulator-\d+|localhost:\d+|127\.0\.0\.1:\d+)$') { throw 'This regression script only runs on an explicitly selected emulator.' }
$qemu = & $adb -s $Serial shell getprop ro.kernel.qemu
if ($qemu.Trim() -ne '1') { throw 'Expected an Android emulator.' }
$log = Join-Path $project 'build/p1-08-android-checks.log'
Set-Content -LiteralPath $log -Value 'P1-08 isolated Android backup regressions' -Encoding utf8
foreach ($apk in @('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
    $installed = & $adb -s $Serial install -r (Join-Path $project $apk) 2>&1
    $installed | Add-Content -LiteralPath $log
    if ($LASTEXITCODE -ne 0 -or ($installed -join "`n") -notmatch 'Success') { throw "APK installation failed: $apk" }
}
$runner = 'cn.activityscore.app.test/cn.activityscore.app.tests.NumericPersistenceInstrumentation'
function Invoke-BackupPhase([string]$Phase, [bool]$ExpectedDeath = $false) {
    $output = & $adb -s $Serial shell am instrument -w -r -e backupPhase $Phase $runner 2>&1
    $output | Add-Content -LiteralPath $log
    $joined = $output -join "`n"
    if ($ExpectedDeath) {
        if ($joined -notmatch 'Process crashed') { throw "Expected real process death in $Phase; see $log" }
        Write-Output "$Phase terminated at the injected interruption point"
    } else {
        if ($joined -notmatch "P1-08 Android backup $Phase passed" -or $joined -notmatch 'failures=0') { throw "Failed phase $Phase; see $log" }
        Write-Output ($output | Where-Object { $_ -match 'passed' })
    }
}
Invoke-BackupPhase 'suite'
foreach ($pair in @(@('seed-files', 'verify-original'), @('seed-transaction', 'verify-original'), @('seed-committed', 'verify-restored'))) {
    Invoke-BackupPhase $pair[0]
    & $adb -s $Serial shell am force-stop cn.activityscore.app
    Invoke-BackupPhase $pair[1]
}
foreach ($pair in @(@('kill-files', 'verify-original'), @('kill-transaction', 'verify-original'), @('kill-committed', 'verify-restored'))) {
    Invoke-BackupPhase $pair[0] $true
    Invoke-BackupPhase $pair[1]
}
Invoke-BackupPhase 'seed-committed'
& $adb -s $Serial shell am force-stop cn.activityscore.app
Invoke-BackupPhase 'verify-damaged-committed'
Invoke-BackupPhase 'verify-restored'
$numeric = & $adb -s $Serial shell am instrument -w -r $runner 2>&1
$numeric | Add-Content -LiteralPath $log
if (($numeric -join "`n") -notmatch 'Numeric Android persistence/package regressions passed' -or ($numeric -join "`n") -notmatch 'failures=0') { throw "Existing numeric regression failed; see $log" }
Write-Output 'P1-08 interruption/restart and existing numeric Android regressions passed'
