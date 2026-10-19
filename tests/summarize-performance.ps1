param([Parameter(Mandatory=$true)][string]$Directory,[string]$Output='')
$ErrorActionPreference='Stop'
if(!$Output){$Output=Join-Path $Directory 'summary.json'}
function Stats($Values) {
    $v=@($Values|Sort-Object)
    if(!$v.Count){return $null}
    $middle=[int][Math]::Floor($v.Count/2)
    $median=if($v.Count%2){$v[$middle]}else{($v[$middle-1]+$v[$middle])/2}
    return [ordered]@{n=$v.Count;median=[Math]::Round($median,3);p95=[Math]::Round($v[[Math]::Ceiling($v.Count*0.95)-1],3);max=[Math]::Round($v[-1],3)}
}
$all=@(Get-ChildItem -LiteralPath $Directory -Filter '*.json' | Where-Object{$_.Name -ne 'summary.json'} | ForEach-Object{Get-Content -Raw -LiteralPath $_.FullName | ConvertFrom-Json})
$summary=foreach($scale in @('small','medium','large')) {
    $tests=@($all|Where-Object{$_.size -eq $scale})
    if(!$tests.Count){continue}
    $operations=foreach($g in @($tests.rows|Group-Object op)) {
        # Stage rows emitted by callbacks also contain warm-up invocations; exclude them.
        $skip=if($g.Name -in @('lock.snapshot.wait','lock.snapshot.hold','transaction.save.span','transaction.save.commit')){2}elseif($g.Name -in @('lock.backup.wait_plus_stage_setup','lock.backup.hold_span','transaction.restore.span','transaction.restore.commit')){1}else{0}
        $samples=@($g.Group|Select-Object -Skip $skip)
        [ordered]@{op=$g.Name;ms=(Stats $samples.ms);gcCount=($samples.gcCount|Measure-Object -Sum).Sum;gcMs=($samples.gcMs|Measure-Object -Sum).Sum}
    }
    $lags=foreach($g in @($tests.lags|Group-Object op)){[ordered]@{op=$g.Name;ms=(Stats $g.Group.lagMs)}}
    $frames=foreach($g in @($tests.frames|Group-Object op)){[ordered]@{op=$g.Name;totalMs=(Stats $g.Group.totalMs);above16ms=@($g.Group|Where-Object{$_.totalMs -gt 16.667}).Count;above50ms=@($g.Group|Where-Object{$_.totalMs -gt 50}).Count}}
    $memory=foreach($g in @($tests.memory|Group-Object op)){[ordered]@{op=$g.Name;samples=$g.Count;javaBytes=($g.Group.javaBytes|Measure-Object -Maximum).Maximum;nativeBytes=($g.Group.nativeBytes|Measure-Object -Maximum).Maximum;pssKb=($g.Group.pssKb|Measure-Object -Maximum).Maximum}}
    [ordered]@{size=$scale;dataset=$tests[0].dataset;operations=@($operations);lags=@($lags);frames=@($frames);memory=@($memory)}
}
ConvertTo-Json -InputObject @($summary) -Depth 12 | Set-Content -LiteralPath $Output -Encoding utf8
foreach($s in $summary){foreach($op in $s.operations){'{0} {1}: n={2} median={3} p95={4}' -f $s.size,$op.op,$op.ms.n,$op.ms.median,$op.ms.p95}}
