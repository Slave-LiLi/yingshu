"""Build a ZIP with truly stored native libraries before Android zipalign."""
import pathlib
import sys
import zipfile

resource_apk, dex_dir, jni_dir, output = map(pathlib.Path, sys.argv[1:])
with zipfile.ZipFile(output, "w") as destination:
    with zipfile.ZipFile(resource_apk) as resources:
        for info in resources.infolist():
            name = info.filename.replace("\\", "/")
            # Keep the compiled resource table uncompressed for modern Android.
            compression = zipfile.ZIP_STORED if name == "resources.arsc" else zipfile.ZIP_DEFLATED
            destination.writestr(name, resources.read(info), compress_type=compression)
    for dex in sorted(dex_dir.glob("*.dex")):
        destination.write(dex, dex.name, compress_type=zipfile.ZIP_DEFLATED)
    for library in sorted(jni_dir.rglob("*.so")):
        destination.write(library, "lib/" + library.relative_to(jni_dir).as_posix(), compress_type=zipfile.ZIP_STORED)
