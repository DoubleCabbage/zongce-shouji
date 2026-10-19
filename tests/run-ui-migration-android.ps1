param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [int]$AdbPort=5038,
    [string[]]$Phases=@('suite'),
    [switch]$SkipInstall
)
$ErrorActionPreference='Stop'
$adb="$env:USERPROFILE/AppData/Local/Android/Sdk/platform-tools/adb.exe"
$project=Split-Path $PSScriptRoot -Parent
function Adb { & $adb -P $AdbPort -s $Serial @args }
if ($Serial -notmatch '^(emulator-\d+|localhost:\d+|127\.0\.0\.1:\d+)$' -or
    (Adb shell getprop ro.kernel.qemu).Trim() -ne '1') { throw 'Explicit isolated emulator required.' }
if (!$SkipInstall) {
    foreach($apk in @('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
        $installed=Adb install -r (Join-Path $project $apk)
        if ($LASTEXITCODE -ne 0 -or ($installed -join "`n") -notmatch 'Success') { throw "Install failed $apk" }
    }
}
foreach($phase in $Phases) {
    if($phase -notin @('suite','capture','capture-small','capture-dark','capture-tablet')) { throw 'Invalid migration phase.' }
    $log=Join-Path $project "build/ui-migration-$phase.log"
    $result=Adb shell am instrument -w -r -e migrationPhase $phase cn.activityscore.app.test/cn.activityscore.app.tests.NumericPersistenceInstrumentation
    $result | Set-Content -LiteralPath $log -Encoding utf8
    $result | Where-Object { $_ -match 'passed|failed|failures=' } | Write-Output
    if(($result -join "`n") -notmatch 'failures=0' -or ($result -join "`n") -notmatch "Compose migration $phase passed") { throw "Migration $phase failed; see $log" }
    $directory=Join-Path $project "docs/audit/ui-compose-results/$phase"
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $names=@('home','activities','detail','organize')
    if($phase -eq 'suite') { $names+=@('participation-sheet','final-state') }
    foreach($name in $names) {
        $encoded=Adb shell run-as cn.activityscore.app base64 "app_ui-compose-migration/cache/$name.png"
        if($LASTEXITCODE -ne 0) { throw "Cannot collect $phase/$name" }
        [IO.File]::WriteAllBytes((Join-Path $directory "$name.png"),[Convert]::FromBase64String(($encoded -join '')))
    }
}
