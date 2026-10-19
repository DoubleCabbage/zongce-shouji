param([Parameter(Mandatory = $true)][string]$Serial,
    [string]$AndroidSdk = "$env:USERPROFILE/AppData/Local/Android/Sdk")
$ErrorActionPreference = 'Stop'
$adb = Join-Path $AndroidSdk 'platform-tools/adb.exe'
$project = Split-Path $PSScriptRoot -Parent
if ($Serial -notmatch '^(emulator-\d+|localhost:\d+|127\.0\.0\.1:\d+)$' -or (& $adb -s $Serial shell getprop ro.kernel.qemu).Trim() -ne '1') {
    throw 'Explicitly select an isolated emulator; real phones are not accepted.'
}
$log = Join-Path $project 'build/p1-05-android-checks.log'
Set-Content -LiteralPath $log -Value 'P1-05 isolated Activity rebuild, SIGKILL, independent new-process and force-stop checks' -Encoding utf8
foreach ($apk in @('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
    $installed = & $adb -s $Serial install -r (Join-Path $project $apk) 2>&1
    $installed | Add-Content -LiteralPath $log
    if ($LASTEXITCODE -ne 0 -or ($installed -join "`n") -notmatch 'Success') { throw "APK install failed: $apk" }
}
function Invoke-DraftPhase([string]$Phase, [bool]$Death = $false) {
    $output = & $adb -s $Serial shell am instrument -w -r -e draftPhase $Phase cn.activityscore.app.test/cn.activityscore.app.tests.NumericPersistenceInstrumentation 2>&1
    $output | Add-Content -LiteralPath $log
    $joined = $output -join "`n"
    if ($Death) {
        if ($joined -notmatch 'Process crashed') { throw "Expected SIGKILL in $Phase; see $log" }
        Write-Output "$Phase reached actual process termination"
    } else {
        if ($joined -notmatch "P1-05 Android draft $Phase passed" -or $joined -notmatch 'failures=0') { throw "Phase $Phase failed; see $log" }
        Write-Output ($output | Where-Object { $_ -match 'passed' })
    }
}
Invoke-DraftPhase 'suite'
Invoke-DraftPhase 'ui'
Invoke-DraftPhase 'history-ui'
Invoke-DraftPhase 'package-ui'
Invoke-DraftPhase 'corrupt-ui'
Invoke-DraftPhase 'unknown-new-activity'
Invoke-DraftPhase 'save-recreate'
Invoke-DraftPhase 'annual-target'
Invoke-DraftPhase 'participation-target'
foreach ($kind in @('create','edit','participation','annual','deleted','failure','before','committed')) {
    Invoke-DraftPhase "kill-$kind" $true
    Invoke-DraftPhase "verify-$kind"
}
Invoke-DraftPhase 'seed-create'
& $adb -s $Serial shell am force-stop cn.activityscore.app
Invoke-DraftPhase 'verify-create'
Invoke-DraftPhase 'discard'
Invoke-DraftPhase 'empty-restart'
Invoke-DraftPhase 'seed-create'
Invoke-DraftPhase 'save'
Invoke-DraftPhase 'saved-restart'
Invoke-DraftPhase 'kill-committed' $true
Invoke-DraftPhase 'discard'
Invoke-DraftPhase 'pending-discarded-restart'
