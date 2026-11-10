param([string]$ToolsRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) 'android-tools'))
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Path $ToolsRoot -Force | Out-Null
$downloads = @(
    @{ File='android-ndk-r30-windows.zip'; Url='https://dl.google.com/android/repository/android-ndk-r30-windows.zip'; Algorithm='SHA1'; Hash='9bf167a1985fa7d4a036186b78f702eab9179408' },
    @{ File='Install_NDI_SDK_v6_Android.tar.gz'; Url='https://downloads.ndi.tv/SDK/NDI_SDK_Android/Install_NDI_SDK_v6_Android.tar.gz'; Algorithm='SHA256'; Hash='895fe323f7329af86df5e0a8ad01f708d30362ff2771ae1179794dc1b4ed8668' }
)
foreach($download in $downloads) {
    $path = Join-Path $ToolsRoot $download.File
    if(-not (Test-Path -LiteralPath $path)) {
        & curl.exe -L --fail --retry 2 --max-time 300 -o $path $download.Url
        if($LASTEXITCODE -ne 0) { throw ('Download failed: ' + $download.File) }
    }
    if((Get-FileHash -LiteralPath $path -Algorithm $download.Algorithm).Hash -ne $download.Hash) {
        throw ('Checksum changed: ' + $download.File + '. Review the official new SDK before updating the recorded checksum.')
    }
}
# Python 3.12+ extracts archive data safely; the shell installer is never executed.
& py (Join-Path $PSScriptRoot 'native\extract-sdk.py') $ToolsRoot
if($LASTEXITCODE -ne 0) { throw 'Native SDK extraction failed' }
Write-Output 'NDK and official Android NDI SDK ready. Read the extracted SDK license before distribution.'
