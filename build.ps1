param(
    [string]$ToolsRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) 'android-tools'),
    [string]$JdkRoot = $env:JAVA_HOME,
    [string]$VersionName = '0.3.14-trial',
    [int]$VersionCode = 18,
    [string]$PythonExe = 'py',
    [string]$SigningKeysRoot = (Join-Path $PSScriptRoot 'keys'),
    [string]$KeystoreName = 'yingshu-local.jks',
    [string]$KeyAlias = 'yingshu-local',
    [string]$StorePasswordEnvironment = 'YINGSHU_KEYSTORE_PASSWORD',
    [string]$KeyPasswordEnvironment = 'YINGSHU_KEY_PASSWORD'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ([string]::IsNullOrWhiteSpace($JdkRoot)) { throw 'Set JAVA_HOME or pass -JdkRoot for your JDK installation.' }
if ([string]::IsNullOrWhiteSpace($KeyAlias)) { throw 'The signing key alias must not be empty.' }
foreach ($passwordEnvironment in @($StorePasswordEnvironment,$KeyPasswordEnvironment)) {
    if ($passwordEnvironment -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') { throw 'Use a valid environment-variable name for each signing password.' }
    if ([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($passwordEnvironment, 'Process'))) {
        throw "Set the process environment variable $passwordEnvironment before signing; see docs/BUILD.md."
    }
}
$keystore = Join-Path $SigningKeysRoot $KeystoreName
if (-not (Test-Path -LiteralPath $keystore -PathType Leaf)) { throw 'Signing keystore is missing. Create your own key explicitly as documented in docs/BUILD.md; this script never creates or replaces a key.' }

# A standalone build using only Google's SDK and a JDK. No original app files.
$projectRoot = $PSScriptRoot
$entrySource = Join-Path $projectRoot 'app\src\main\java\io\yingshu\framekey\MainActivity.java'
if (-not (Test-Path -LiteralPath $entrySource)) { throw 'MainActivity.java is not ready; refusing to produce an incomplete APK.' }
foreach ($modelAsset in @('joint.onnx','panel-mask.bin','manifest.json')) {
    $modelAssetPath = Join-Path $projectRoot ('app\src\main\assets\models\' + $modelAsset)
    if (-not (Test-Path -LiteralPath $modelAssetPath)) { throw "Required trial model asset missing: $modelAsset" }
}
& $PythonExe (Join-Path $projectRoot 'native\verify_joint_assets.py') (Join-Path $projectRoot 'app\src\main\assets\models')
if ($LASTEXITCODE -ne 0) { throw 'Joint model contract/hash verification failed.' }
$nativeBuild = Join-Path $projectRoot 'build-native.ps1'
$nativeCompiler = Join-Path $ToolsRoot 'android-ndk-r30\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe'
if ((Test-Path -LiteralPath $nativeBuild) -and (Test-Path -LiteralPath $nativeCompiler)) {
    & $nativeBuild -ToolsRoot $ToolsRoot
}
$jniRoot = Join-Path $projectRoot 'app\src\main\jniLibs'
foreach ($nativeRequired in @('arm64-v8a\libndi.so','arm64-v8a\libyingshu_ndi.so')) {
    if (-not (Test-Path -LiteralPath (Join-Path $jniRoot $nativeRequired))) { throw "NDI native library missing: $nativeRequired" }
}
$platformRoot = Join-Path $ToolsRoot 'android-35'
$buildToolsRoot = Join-Path $ToolsRoot 'android-15'
if (-not (Test-Path -LiteralPath (Join-Path $platformRoot 'android.jar'))) {
    $platformRoot = (Get-ChildItem -LiteralPath $ToolsRoot -Filter android.jar -File -Recurse | Select-Object -First 1).Directory.FullName
}
if (-not (Test-Path -LiteralPath (Join-Path $buildToolsRoot 'aapt2.exe'))) {
    $buildToolsRoot = (Get-ChildItem -LiteralPath $ToolsRoot -Filter aapt2.exe -File -Recurse | Select-Object -First 1).Directory.FullName
}
$androidJar = Join-Path $platformRoot 'android.jar'
$aapt = Join-Path $buildToolsRoot 'aapt2.exe'
$zipalign = Join-Path $buildToolsRoot 'zipalign.exe'
$d8Jar = Join-Path $buildToolsRoot 'lib\d8.jar'
$signerJar = Join-Path $buildToolsRoot 'lib\apksigner.jar'
$java = Join-Path $JdkRoot 'bin\java.exe'
$javac = Join-Path $JdkRoot 'bin\javac.exe'
$jar = Join-Path $JdkRoot 'bin\jar.exe'
foreach ($required in @($androidJar,$aapt,$zipalign,$d8Jar,$signerJar,$java,$javac,$jar)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Required build tool not found: $required" }
}

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build command failed ($LASTEXITCODE): $Executable" }
}

$buildRoot = Join-Path $projectRoot 'build'
$intermediate = Join-Path $buildRoot ('intermediate-' + [Guid]::NewGuid().ToString('N'))
$generated = Join-Path $intermediate 'generated'
$classes = Join-Path $intermediate 'classes'
$dexDir = Join-Path $intermediate 'dex'
foreach ($directory in @($buildRoot,$intermediate,$generated,$classes,$dexDir)) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}

$resRoot = Join-Path $projectRoot 'app\src\main\res'
$manifest = Join-Path $projectRoot 'app\src\main\AndroidManifest.xml'
$resourcesZip = Join-Path $intermediate 'compiled-res.zip'
$resourceApk = Join-Path $intermediate 'resources.apk'
Invoke-Checked $aapt @('compile','--dir',$resRoot,'-o',$resourcesZip)
$linkArguments = @('link','-o',$resourceApk,'-I',$androidJar,'--manifest',$manifest,'--java',$generated,
    '--min-sdk-version','26','--target-sdk-version','35','--version-code',([string]$VersionCode),'--version-name',$VersionName,
    '--auto-add-overlay','-R',$resourcesZip)
$assets = Join-Path $projectRoot 'app\src\main\assets'
if (Test-Path -LiteralPath $assets) { $linkArguments += @('-A',$assets) }
Invoke-Checked $aapt $linkArguments

$sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'app\src\main\java'),$generated -Filter '*.java' -File -Recurse | ForEach-Object FullName)
if ($sourceFiles.Count -eq 0) { throw 'No Java source files found.' }
$sourceList = Join-Path $intermediate 'sources.txt'
$sourceText = ($sourceFiles | ForEach-Object { '"' + $_.Replace('\','/') + '"' }) -join "`r`n"
[IO.File]::WriteAllText($sourceList,$sourceText,[Text.UTF8Encoding]::new($false))
$runtimeJar = Join-Path $projectRoot 'libs\onnxruntime-android.jar'
if (-not (Test-Path -LiteralPath $runtimeJar)) { throw 'ONNX Runtime Java library is missing.' }
Invoke-Checked $javac @('-J-Duser.language=en','-J-Duser.country=US','-encoding','UTF-8','--release','8','-Xlint:-options','-classpath',($androidJar + ';' + $runtimeJar),'-d',$classes,('@' + $sourceList))
$classesJar = Join-Path $intermediate 'classes.jar'
Invoke-Checked $jar @('--create','--file',$classesJar,'-C',$classes,'.')
Invoke-Checked $java @('-cp',$d8Jar,'com.android.tools.r8.D8','--min-api','26','--lib',$androidJar,'--output',$dexDir,$classesJar,$runtimeJar)

$unsigned = Join-Path $intermediate 'unsigned.apk'
Invoke-Checked $PythonExe @((Join-Path $projectRoot 'native\package_apk.py'),$resourceApk,$dexDir,$jniRoot,$unsigned)

$aligned = Join-Path $intermediate 'aligned.apk'
Invoke-Checked $zipalign @('-f','-P','16','4',$unsigned,$aligned)
$outputApk = Join-Path $buildRoot ('yingshu-hardware-' + $VersionName + '.apk')
Invoke-Checked $java @('-jar',$signerJar,'sign','--ks',$keystore,'--ks-key-alias',$KeyAlias,
    '--ks-pass',('env:' + $StorePasswordEnvironment),'--key-pass',('env:' + $KeyPasswordEnvironment),
    '--v1-signing-enabled','true','--v2-signing-enabled','true','--v3-signing-enabled','true',
    '--out',$outputApk,$aligned)
Invoke-Checked $java @('-jar',$signerJar,'verify','--verbose','--print-certs',$outputApk)
Invoke-Checked $zipalign @('-c','-P','16','-v','4',$outputApk)
$apkHash = Get-FileHash -LiteralPath $outputApk -Algorithm SHA256
$buildInfo = [ordered]@{
    apk = $outputApk
    package = 'io.yingshu.framekey'
    versionName = $VersionName
    versionCode = $VersionCode
    minSdk = 26
    targetSdk = 35
    sha256 = $apkHash.Hash.ToLowerInvariant()
    bytes = (Get-Item -LiteralPath $outputApk).Length
    builtAt = [DateTimeOffset]::Now.ToString('o')
    signing = 'Caller-provided signing certificate; verify the printed certificate before distributing.'
}
$buildInfo | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $buildRoot 'build-info.json') -Encoding UTF8
Write-Output ('APK ready: ' + $outputApk)
