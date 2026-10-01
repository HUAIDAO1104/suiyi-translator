#!/usr/bin/env python3
"""Package a signed Gradle APK and its update metadata; never accepts signing keys."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys


def package(project: Path, destination: Path):
    output = project / "app/build/outputs/apk/release"
    metadata = json.loads((output / "output-metadata.json").read_text())
    elements = metadata["elements"]
    if len(elements) != 1:
        raise ValueError("Expected one universal APK")
    element = elements[0]
    apk = output / element["outputFile"]
    if "unsigned" in apk.name:
        raise ValueError("Release APK is unsigned; configure signing secrets first")
    notes = os.environ.get("RELEASE_NOTES", "首版：中英泰双向翻译、手机听写、朗读、收藏及手机更新。")
    if len(notes) > 5000:
        raise ValueError("Release notes must be under 5000 characters")
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copy2(apk, destination / "suiyi.apk")
    manifest = {
        "applicationId": metadata["applicationId"],
        "versionCode": element["versionCode"],
        "versionName": element["versionName"],
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "size": apk.stat().st_size,
        "notes": notes,
    }
    (destination / "update.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    (destination / "release-notes.txt").write_text(notes + "\n\n安装 suiyi.apk；以后可在手机设置中检查更新。\n")
    print(f"Packaged {manifest['versionName']} ({manifest['versionCode']})")


if __name__ == "__main__":
    package(Path(__file__).resolve().parents[1], Path(sys.argv[1]))
