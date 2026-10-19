param([Parameter(Mandatory=$true)][string]$Serial,
    [string]$Label='baseline', [int]$AdbPort=5037,
    [string[]]$Sizes=@('small','medium','large'),
    [string[]]$Phases=@('seed','data','drafts','locks','ui','swipe','save-ui','input','cold'), [int]$ColdRounds=5,
    [switch]$SkipInstall)
$ErrorActionPreference='Stop'
if($Serial -notmatch '^(emulator-\d+|localhost:\d+|127\.0\.0\.1:\d+)$') { throw 'Explicit isolated emulator required' }
if($Label -notmatch '^[a-zA-Z0-9-]+$') { throw 'Invalid label' }
$adb="$env:USERPROFILE/AppData/Local/Android/Sdk/platform-tools/adb.exe"
$project=Split-Path $PSScriptRoot -Parent
function Adb { & $adb -P $AdbPort -s $Serial @args }
if((Adb shell getprop ro.kernel.qemu).Trim() -ne '1') { throw 'Real devices are not accepted' }
$output=Join-Path $project "build/p1-07-results/$Label"
New-Item -ItemType Directory -Force -Path $output | Out-Null
Adb shell getprop | Set-Content -LiteralPath (Join-Path $output 'device.txt') -Encoding utf8
Adb shell cat /proc/meminfo | Add-Content -LiteralPath (Join-Path $output 'device.txt')
Adb shell cat /proc/cpuinfo | Add-Content -LiteralPath (Join-Path $output 'device.txt')
Get-CimInstance Win32_ComputerSystem | Select-Object TotalPhysicalMemory,NumberOfLogicalProcessors | Out-String | Set-Content -LiteralPath (Join-Path $output 'host.txt')
Get-CimInstance Win32_Processor | Select-Object Name,LoadPercentage | Out-String | Add-Content -LiteralPath (Join-Path $output 'host.txt')
git -C $project rev-parse HEAD | Add-Content -LiteralPath (Join-Path $output 'host.txt')
if(!$SkipInstall) {
    foreach($apk in @('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
        $result=Adb install -r (Join-Path $project $apk)
        if($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'Success') { throw "Install failed $apk" }
    }
}
foreach($size in $Sizes) {
    if($size -notin @('small','medium','large')) { throw 'Invalid scale' }
    foreach($phase in $Phases) {
        if($phase -notin @('seed','data','drafts','locks','ui','swipe','save-ui','input','concurrency','concurrency-diagnostic','export-control','mapping-main','mapping-io','mapping-draft','cache-cleanup','cold')) { throw 'Invalid phase' }
        $rounds=if($phase -eq 'cold'){$ColdRounds}else{1}
        for($i=0;$i -lt $rounds;$i++) {
            $name="$size-$phase-$i"
            Adb logcat -c
            $result=Adb shell am instrument -w -r -e perfSize $size -e perfPhase $phase cn.activityscore.app.test/cn.activityscore.app.tests.NumericPersistenceInstrumentation
            $result | Set-Content -LiteralPath (Join-Path $output "$name.log") -Encoding utf8
            Adb logcat -d -v threadtime | Set-Content -LiteralPath (Join-Path $output "$name-logcat.txt") -Encoding utf8
            if(($result -join "`n") -notmatch 'failures=0' -or ($result -join "`n") -notmatch "P1-07 $size .*passed|P1-07 seed $size passed") { throw "Performance phase failed $name; see $output" }
            if($phase -ne 'seed') {
                $base="app_p107-performance-$size/cache"
                Adb shell run-as cn.activityscore.app cat "$base/result-$phase.json" | Set-Content -LiteralPath (Join-Path $output "$name.json") -Encoding utf8
                Adb shell run-as cn.activityscore.app cat "$base/stacks-$phase.txt" | Set-Content -LiteralPath (Join-Path $output "$name-stacks.txt") -Encoding utf8
                Adb shell dumpsys gfxinfo cn.activityscore.app framestats | Set-Content -LiteralPath (Join-Path $output "$name-gfx.txt") -Encoding utf8
            }
            Write-Output "$Label $name passed"
        }
    }
}

