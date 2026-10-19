param([Parameter(Mandatory = $true)][string]$Serial,
    [string]$AndroidSdk = "$env:USERPROFILE/AppData/Local/Android/Sdk")
$ErrorActionPreference = 'Stop'
$adb = Join-Path $AndroidSdk 'platform-tools/adb.exe'
$project = Split-Path $PSScriptRoot -Parent
if ($Serial -notmatch '^(emulator-\d+|localhost:\d+|127\.0\.0\.1:\d+)$') { throw 'Explicitly select an isolated emulator.' }
if ((& $adb -s $Serial shell getprop ro.kernel.qemu).Trim() -ne '1') { throw 'Real devices are not accepted.' }
$log = Join-Path $project 'build/p1-09-android-checks.log'
Set-Content -LiteralPath $log -Value 'P1-09 isolated data operations and separate-process persistence checks' -Encoding utf8
foreach ($apk in @('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
    $result = & $adb -s $Serial install -r (Join-Path $project $apk) 2>&1
    $result | Add-Content -LiteralPath $log
    if ($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'Success') { throw "Installation failed: $apk" }
}
function Invoke-DataPhase([string]$Phase, [bool]$Death = $false) {
    $result = & $adb -s $Serial shell am instrument -w -r -e dataPhase $Phase cn.activityscore.app.test/cn.activityscore.app.tests.NumericPersistenceInstrumentation 2>&1
    $result | Add-Content -LiteralPath $log
    $joined = $result -join "`n"
    if ($Death) {
        if ($joined -notmatch 'Process crashed') { throw "Expected actual termination in $Phase; see $log" }
        Write-Output "${Phase}: actual process termination reached"
    } else {
        if ($joined -notmatch "P1-09 Android data $Phase passed" -or $joined -notmatch 'failures=0') { throw "Failed phase $Phase; see $log" }
        Write-Output ($result | Where-Object { $_ -match 'passed' })
    }
}
Invoke-DataPhase 'suite'
Invoke-DataPhase 'backup-native-commit'
Invoke-DataPhase 'same-state'
Invoke-DataPhase 'same-state-restart'
Invoke-DataPhase 'file-guard'
Invoke-DataPhase 'file-guard-restart'
Invoke-DataPhase 'ui'
foreach ($stage in @('before','transaction','committed')) {
    Invoke-DataPhase "kill-$stage" $true
    Invoke-DataPhase "verify-$stage"
}
