param(
    [string]$AndroidSdk = "$env:USERPROFILE/AppData/Local/Android/Sdk",
    [string]$OutputDirectory = ""
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path $PSScriptRoot -Parent
if (!$OutputDirectory) { $OutputDirectory = Join-Path $projectDirectory 'build/standalone-checks' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
$java = "$env:JAVA_HOME/bin/java.exe"
if (!(Test-Path -LiteralPath $java)) { $java = 'C:/Program Files/Android/Android Studio/jbr/bin/java.exe' }
if (!(Test-Path -LiteralPath $java)) { throw 'Set JAVA_HOME to a Java 17+ runtime.' }
$androidJar = Join-Path $AndroidSdk 'platforms/android-35/android.jar'
if (!(Test-Path -LiteralPath $androidJar)) { throw 'Android SDK platform 35 is required for compilation.' }
$cacheDirectory = "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1"
$groups = @('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21',
    'org.jetbrains.kotlin/kotlin-stdlib/2.0.21', 'org.jetbrains.kotlin/kotlin-script-runtime/2.0.21',
    'org.jetbrains.kotlin/kotlin-reflect/2.0.21', 'org.jetbrains.intellij.deps/trove4j',
    'org.jetbrains/annotations', 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm', 'net.sf.kxml/kxml2/2.3.0')
$jars = foreach ($group in $groups) {
    Get-ChildItem (Join-Path $cacheDirectory $group) -Recurse -Filter '*.jar' |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -ExpandProperty FullName
}
$stdlib = $jars | Where-Object { $_ -match 'kotlin-stdlib-2.0.21.jar$' } | Select-Object -First 1
$kxml = $jars | Where-Object { $_ -match 'kxml2-2.3.0.jar$' } | Select-Object -First 1
if (!$kxml) { throw 'Cached kxml2 2.3.0 is required for the JVM XLSX reader checks.' }
$sourceDirectory = Join-Path $projectDirectory 'app/src/main/java/cn/activityscore/app'
$sources = Get-ChildItem (Join-Path $sourceDirectory 'data'), (Join-Path $sourceDirectory 'domain'), $PSScriptRoot -Filter '*.kt' |
    Select-Object -ExpandProperty FullName
$jar = Join-Path $OutputDirectory 'checks.jar'
& $java -cp ($jars -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$stdlib;$kxml;$androidJar" -d $jar @sources
if ($LASTEXITCODE -ne 0) { throw 'Standalone checks compilation failed.' }
Push-Location -LiteralPath $OutputDirectory
try {
    foreach ($entry in @('cn.activityscore.app.tests.WorkbookImportCheckKt', 'cn.activityscore.app.tests.HistoryCoreCheckKt',
        'cn.activityscore.app.domain.DuplicateReviewCheckKt', 'cn.activityscore.app.domain.AnnualScoreCheckKt',
        'cn.activityscore.app.data.ExportSmokeKt', 'cn.activityscore.app.data.AnnualExportCheckKt',
        'cn.activityscore.app.tests.NumericConsistencyCheckKt', 'cn.activityscore.app.tests.EvidencePolicyCheckKt',
        'cn.activityscore.app.data.ExportGroupingCheckKt')) {
        & $java -cp "$jar;$stdlib;$kxml;$androidJar" $entry
        if ($LASTEXITCODE -ne 0) { throw "Check failed: $entry" }
    }
} finally { Pop-Location }
