param([string]$ToolsRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) 'android-tools'))
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Path $ToolsRoot -Force | Out-Null
# URLs and SHA-1 values were checked against Google's repository2-1.xml.
$packages = @(
    @{ File='platform-35_r02.zip'; Sha1='0bb560a90a7a2cbd0dd8348224d518b638fe7949'; Marker='android-35\android.jar' },
    @{ File='build-tools_r35_windows.zip'; Sha1='af059bb67cf7786f45ee0db85e2d24985df1b4b6'; Marker='android-15\aapt2.exe' }
)
foreach ($package in $packages) {
    $archivePath = Join-Path $ToolsRoot $package.File
    if (-not (Test-Path -LiteralPath $archivePath)) {
        & curl.exe -L --fail --retry 2 --max-time 300 -o $archivePath ('https://dl.google.com/android/repository/' + $package.File)
        if ($LASTEXITCODE -ne 0) { throw ('Download failed: ' + $package.File) }
    }
    if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA1).Hash -ne $package.Sha1) { throw ('Official checksum mismatch: ' + $package.File) }
    if (-not (Test-Path -LiteralPath (Join-Path $ToolsRoot $package.Marker))) {
        Expand-Archive -LiteralPath $archivePath -DestinationPath $ToolsRoot -Force
    }
    Write-Output ($package.File + ': official checksum verified')
}
Write-Output ('SDK tools ready: ' + $ToolsRoot)
