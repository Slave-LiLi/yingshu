param(
    [string]$ToolsRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) 'android-tools'),
    [string]$NdiSdkRoot = (Join-Path $ToolsRoot 'ndi-sdk\NDI SDK for Android'),
    [string]$NdkRoot = (Join-Path $ToolsRoot 'android-ndk-r30')
)
$ErrorActionPreference = 'Stop'
$clang = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe'
$strip = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe'
$sysroot = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\sysroot'
$headers = Join-Path $NdiSdkRoot 'include'
$runtime = Join-Path $NdiSdkRoot 'lib\arm64-v8a\libndi.so'
foreach($required in @($clang,$runtime,(Join-Path $headers 'Processing.NDI.Lib.h'))) {
    if(-not (Test-Path -LiteralPath $required)) { throw "Native build dependency missing: $required" }
}
$libs = Join-Path $PSScriptRoot 'app\src\main\jniLibs\arm64-v8a'
$licenses = Join-Path $PSScriptRoot 'app\src\main\assets\licenses'
New-Item -ItemType Directory -Force -Path $libs,$licenses | Out-Null
Copy-Item -LiteralPath $runtime -Destination (Join-Path $libs 'libndi.so') -Force
Copy-Item -LiteralPath (Join-Path $NdiSdkRoot 'licenses\libndi_licenses.txt') -Destination (Join-Path $licenses 'NDI-third-party.txt') -Force
Copy-Item -LiteralPath (Join-Path $NdiSdkRoot 'NDI SDK License Agreement.txt') -Destination (Join-Path $licenses 'NDI-SDK-License.txt') -Force
$output = Join-Path $libs 'libyingshu_ndi.so'
& $clang '--target=aarch64-linux-android26' "--sysroot=$sysroot" '-std=c++17' '-O2' '-fPIC' '-shared' '-fvisibility=hidden' '-fno-exceptions' '-fno-rtti' '-nostdlib++' '-Wl,-z,max-page-size=16384' '-Wl,-soname,libyingshu_ndi.so' '-Wl,--no-undefined' '-I' $headers (Join-Path $PSScriptRoot 'native\ndi_bridge.cpp') '-L' $libs '-lndi' '-ljnigraphics' '-llog' '-o' $output
if($LASTEXITCODE -ne 0) { throw 'NDI JNI build failed' }
& $strip '--strip-unneeded' $output
if($LASTEXITCODE -ne 0) { throw 'NDI JNI strip failed' }
Write-Output ('Native NDI bridge ready: ' + $output)
