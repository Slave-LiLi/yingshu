"""Extract official build dependencies as data; do not execute installers."""
import io
import pathlib
import sys
import tarfile
import zipfile

root = pathlib.Path(sys.argv[1]).resolve()
with zipfile.ZipFile(root / "android-ndk-r30-windows.zip") as archive:
    marker = root / "android-ndk-r30/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe"
    if not marker.exists():
        names = [n for n in archive.namelist() if "/toolchains/llvm/prebuilt/windows-x86_64/" in n or n.endswith("/source.properties") or n.endswith("/NOTICE")]
        archive.extractall(root, names)
with tarfile.open(root / "Install_NDI_SDK_v6_Android.tar.gz") as archive:
    member = archive.getmember("Install_NDI_SDK_v6_Android.sh")
    script = archive.extractfile(member).read()
prefix, payload = script.split(b"\n__NDI_ARCHIVE_BEGIN__\n", 1)
with tarfile.open(fileobj=io.BytesIO(payload)) as archive:
    archive.extractall(root / "ndi-sdk", filter="data")
print("Official SDK archives extracted under", root)
