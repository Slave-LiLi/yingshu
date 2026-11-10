param([string]$PythonExe = 'py')
$ErrorActionPreference = 'Stop'
$dependencyRoot = Join-Path $PSScriptRoot 'dependencies'
New-Item -ItemType Directory -Path $dependencyRoot -Force | Out-Null
$archive = Join-Path $dependencyRoot 'onnxruntime-android-1.23.2.aar'
$sourceUrl = 'https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.23.2/onnxruntime-android-1.23.2.aar'
$expectedHash = '82048d1f462218adae4ba76477089ab0ba76093d84f733540066db1a8ba6b827'
if (-not (Test-Path -LiteralPath $archive)) { Invoke-WebRequest -Uri $sourceUrl -OutFile $archive }
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) { throw 'ONNX Runtime archive SHA256 mismatch.' }
$libraryDirectory = Join-Path $PSScriptRoot 'libs'
$nativeDirectory = Join-Path $PSScriptRoot 'app\src\main\jniLibs\arm64-v8a'
New-Item -ItemType Directory -Path $libraryDirectory,$nativeDirectory -Force | Out-Null
$extractScript = @'
import sys, zipfile
from pathlib import Path
archive, java_dir, native_dir = map(Path, sys.argv[1:])
with zipfile.ZipFile(archive) as z:
    (java_dir / 'onnxruntime-android.jar').write_bytes(z.read('classes.jar'))
    for name in ['libonnxruntime.so', 'libonnxruntime4j_jni.so']:
        (native_dir / name).write_bytes(z.read('jni/arm64-v8a/' + name))
'@
$extractScript | & $PythonExe - $archive $libraryDirectory $nativeDirectory
if ($LASTEXITCODE -ne 0) { throw 'ONNX Runtime extraction failed.' }
Write-Output 'ONNX Runtime 1.23.2 Java and ARM64 libraries are ready.'
