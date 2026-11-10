param([string]$JdkRoot = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ([string]::IsNullOrWhiteSpace($JdkRoot)) { throw 'Set JAVA_HOME or pass -JdkRoot.' }
$javac = Join-Path $JdkRoot 'bin\javac.exe'
$java = Join-Path $JdkRoot 'bin\java.exe'
foreach ($tool in @($javac,$java)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "JDK tool not found: $tool" }
}
$projectRoot = Split-Path $PSScriptRoot -Parent
$sourceRoot = Join-Path $projectRoot 'app\src\main\java\io\yingshu\framekey'
$classes = Join-Path $projectRoot ('build\local-checks-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classes | Out-Null
$sourceNames = @('M3Protocol','ImageTensor','InferenceRegion','ModelPostprocess',
    'JointOutputValidator','KeyCatalog','KeySearch','RuleBook','RuleGate',
    'RecognitionRuleSelector','RuleTextCodec','UvcDescriptors','UvcPayloadAssembler')
$testNames = @('M3ProtocolTest','ModelPipelineTest','JointDetectorTest',
    'JointOutputValidatorTest','KeySearchTest','RuleGateTest',
    'RecognitionRuleSelectorTest','RuleTextCodecTest','UvcProtocolTest','UvcFuzzTest')
$sourcePaths = @($sourceNames | ForEach-Object { Join-Path $sourceRoot ($_ + '.java') })
$sourcePaths += @($testNames | ForEach-Object { Join-Path $PSScriptRoot ($_ + '.java') })
& $javac '-encoding' 'UTF-8' '--release' '8' '-Xlint:-options' '-d' $classes @sourcePaths
if ($LASTEXITCODE -ne 0) { throw 'Local Java checks failed to compile.' }
foreach ($testName in $testNames) {
    $className = if ($testName.StartsWith('Uvc')) { 'io.yingshu.framekey.' + $testName } else { $testName }
    & $java '-ea' '-cp' $classes $className
    if ($LASTEXITCODE -ne 0) { throw "Local Java check failed: $className" }
}
Write-Output ('All ' + $testNames.Count + ' local Java test programs passed. No phone, network, model inference or hardware output was used.')
