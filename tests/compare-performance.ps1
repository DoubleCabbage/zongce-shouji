param([string]$Baseline='docs/audit/performance-results/baseline-summary.json',
    [string]$After='build/p1-07-results/optimized/summary.json',
    [string]$Output='docs/audit/performance-results/comparison.md')
$ErrorActionPreference='Stop'
$before=Get-Content -Raw -LiteralPath $Baseline|ConvertFrom-Json
$afterData=Get-Content -Raw -LiteralPath $After|ConvertFrom-Json
$names=[ordered]@{
 'cold.activity_to_home'='冷 Activity 到首页'; 'cold.process_to_home'='冷进程到首页（含插桩）'; 'warm.home'='同进程 Activity 重开';
 'snapshot'='完整快照'; 'home.derive'='年度计算单次'; 'history.tree'='历史树'; 'history.match'='单次模糊匹配';
 'ui.records_roundtrip'='记录页首次进入/回首页'; 'ui.detail'='详情打开/关闭'; 'ui.input.confirmed'='输入文本可见';
 'ui.year_roundtrip'='学年切换往返'; 'ui.year_during_scan'='扫描期间学年切换往返';
 'ui.save.refresh'='点击保存到详情刷新';
 'save.refresh'='数据库保存+快照读取'; 'draft.serialize'='草稿序列化'; 'draft.atomic'='AtomicFile 写入';
 'draft.setter.short'='短备注字段更新'; 'draft.setter.long'='长文本字段更新'; 'draft.setter.courses'='200 门课程字段更新';
 'draft.saved_instance'='SavedInstance 序列化'; 'draft.barrier'='草稿保存屏障';
 'image.validate.small'='小图校验'; 'image.validate.12mp'='12 MP 校验'; 'image.save.single'='单图保存+清理'; 'image.save.multi'='三图保存+清理';
 'image.convert.webp'='WebP 转 PNG'; 'evidence.scan'='全量证明检查'; 'excel.snapshot'='学年 Excel 快照'; 'excel.write'='Excel 最终写出';
 'backup.capture'='完整备份生成'; 'backup.verify'='完整备份验证'; 'backup.restore'='完整恢复';
 'lock.snapshot.hold'='快照锁持有'; 'lock.backup.hold_span'='备份锁窗口'; 'transaction.restore.span'='恢复事务窗口'; 'transaction.save.span'='活动更新事务窗口'
}
$out=@('| 操作 | 规模 | 前 中位/P95 ms | 后 中位/P95 ms | n 前/后 | 中位变化 | 解释/正确性 |',
 '|---|---|---:|---:|---:|---:|---|')
foreach($size in @('small','medium','large')) {
 $b=$before|Where-Object size -eq $size; $a=$afterData|Where-Object size -eq $size
 foreach($op in $names.Keys) {
  $bv=$b.operations|Where-Object op -eq $op; $av=$a.operations|Where-Object op -eq $op
  if(!$bv -or !$av){continue}
  $ratio=if($bv.ms.median){100*(1-$av.ms.median/$bv.ms.median)}else{0}
  $interpret=if($op -like 'ui.*' -or $op -like 'cold.*' -or $op -eq 'warm.home'){'UI 路径优化；详见正文限制'}else{'原数据层/协议未改；变化不作为收益'}
  $out+='| {0} | {1} | {2:N3}/{3:N3} | {4:N3}/{5:N3} | {6}/{7} | {8:N1}% | {9} |' -f $names[$op],$size,$bv.ms.median,$bv.ms.p95,$av.ms.median,$av.ms.p95,$bv.ms.n,$av.ms.n,$ratio,$interpret
 }
}
$out|Set-Content -LiteralPath $Output -Encoding utf8
