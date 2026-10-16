"""Package the four AGP outputs with release notes and checksum manifests."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


source = Path("app/build/outputs/apk/release")
metadata = json.loads((source / "output-metadata.json").read_text())
expected = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64"}
outputs = {}
for item in metadata["elements"]:
    abi = next(f["value"] for f in item["filters"] if f["filterType"] == "ABI")
    if abi not in expected or abi in outputs:
        raise ValueError(f"Unexpected or duplicate ABI: {abi}")
    outputs[abi] = item
if set(outputs) != expected:
    raise ValueError(f"Expected all four ABIs, got {sorted(outputs)}")
versions = {(item["versionName"], item["versionCode"]) for item in outputs.values()}
if len(versions) != 1:
    raise ValueError("APK version information differs")
version_name, version_code = versions.pop()
commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
subject = subprocess.check_output(["git", "log", "-1", "--format=%s"], text=True).strip()
repository = os.environ.get("GITHUB_REPOSITORY", "sinkclose/BaraFrame")
server = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
run_id = os.environ.get("GITHUB_RUN_ID")
dest = Path("dist")
dest.mkdir(exist_ok=True)
assets = []
for abi, item in sorted(outputs.items()):
    apk = source / item["outputFile"]
    name = f"BaraFrame-{version_name}-{version_code}-{abi}.apk"
    shutil.copyfile(apk, dest / name)
    data = (dest / name).read_bytes()
    assets.append({"name": name, "abi": abi, "size_bytes": len(data),
                   "sha256": hashlib.sha256(data).hexdigest(),
                   "sha512": hashlib.sha512(data).hexdigest()})
for algorithm in ("sha256", "sha512"):
    (dest / f"{algorithm.upper()}SUMS").write_text("".join(
        f"{asset[algorithm]}  {asset['name']}\n" for asset in assets))
(dest / "manifest.json").write_text(json.dumps({
    "commit": commit, "version_name": version_name,
    "version_code": version_code, "assets": assets,
}, indent=2) + "\n")
notes = [f"Commit: [`{commit}`]({server}/{repository}/commit/{commit})", "",
         f"> {subject}", "", f"Version: **{version_name}** (`{version_code}`)", ""]
if run_id:
    notes.extend([f"[Build log]({server}/{repository}/actions/runs/{run_id})", ""])
notes.extend(["| APK | Size | SHA-256 |", "| --- | ---: | --- |"])
for asset in assets:
    size = f"{asset['size_bytes']:,} bytes ({asset['size_bytes'] / 1048576:.2f} MiB)"
    notes.append(f"| `{asset['name']}` | {size} | `{asset['sha256']}` |")
notes.extend(["", "SHA-256 and SHA-512 checksums are included as downloadable files.", "",
              "Download the APK for your device's architecture. All four builds have the same features.", "",
              "Verify a download with `sha256sum --ignore-missing -c SHA256SUMS`.", ""])
(dest / "RELEASE.md").write_text("\n".join(notes))
