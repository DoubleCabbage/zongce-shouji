param([Parameter(Mandatory = $true)][string]$Serial,
    [string]$AndroidSdk = "$env:USERPROFILE/AppData/Local/Android/Sdk")
$ErrorActionPreference = 'Stop'
$adb = Join-Path $AndroidSdk 'platform-tools/adb.exe'
$project = Split-Path $PSScriptRoot -Parent
if ($Serial -notmatch '^(emulator-\d+|localhost:\d+|127\.0\.0\.1:\d+)$') { throw 'Explicitly choose a test emulator.' }
if ((& $adb -s $Serial shell getprop ro.kernel.qemu).Trim() -ne '1') { throw 'Real devices are not accepted.' }
$log = Join-Path $project 'build/p1-06-android-checks.log'
Set-Content -LiteralPath $log -Value 'P1-06 isolated evidence/annual export/package regressions' -Encoding utf8
foreach ($apk in @('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
    $installed = & $adb -s $Serial install -r (Join-Path $project $apk) 2>&1
    $installed | Add-Content -LiteralPath $log
    if ($LASTEXITCODE -ne 0 -or ($installed -join "`n") -notmatch 'Success') { throw "Installation failed: $apk" }
}
$result = & $adb -s $Serial shell am instrument -w -r -e evidencePhase suite cn.activityscore.app.test/cn.activityscore.app.tests.NumericPersistenceInstrumentation 2>&1
$result | Add-Content -LiteralPath $log
if (($result -join "`n") -notmatch 'P1-06 Android evidence passed' -or ($result -join "`n") -notmatch 'failures=0') { throw "Evidence regressions failed; see $log" }
Write-Output ($result | Where-Object { $_ -match 'passed' })
$death = & $adb -s $Serial shell am instrument -w -r -e evidencePhase kill-copy cn.activityscore.app.test/cn.activityscore.app.tests.NumericPersistenceInstrumentation 2>&1
$death | Add-Content -LiteralPath $log
if (($death -join "`n") -notmatch 'Process crashed') { throw 'Expected actual process death during attachment copy.' }
Write-Output 'kill-copy terminated while a partial staging file existed'
$restarted = & $adb -s $Serial shell am instrument -w -r -e evidencePhase verify-copy cn.activityscore.app.test/cn.activityscore.app.tests.NumericPersistenceInstrumentation 2>&1
$restarted | Add-Content -LiteralPath $log
if (($restarted -join "`n") -notmatch 'P1-06 Android evidence verify-copy passed' -or ($restarted -join "`n") -notmatch 'failures=0') { throw 'Attachment restart verification failed.' }
Write-Output ($restarted | Where-Object { $_ -match 'passed' })
