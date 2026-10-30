#!/bin/sh
set -eu
cd "$(dirname "$0")"

sh build.sh
cp build/autolark.apk magisk_module/system/app/AutoLark/AutoLark.apk

python3 - <<'PY'
from pathlib import Path
from stat import S_IFREG
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

root = Path('magisk_module')
output = Path('dist/auto_attendance-magisk-v2.5.0.zip')
members = {
    'module.prop': root / 'module.prop',
    'config.sh': root / 'config.sh',
    'service.sh': root / 'service.sh',
    'customize.sh': root / 'customize.sh',
    'system/app/AutoLark/AutoLark.apk': root / 'system/app/AutoLark/AutoLark.apk',
    'system/app/FakeWifi/FakeWifi.apk': root / 'system/app/FakeWifi/FakeWifi.apk',
}
output.parent.mkdir(parents=True, exist_ok=True)
with ZipFile(output, 'w', compression=ZIP_DEFLATED, compresslevel=9) as archive:
    for member, source in members.items():
        if not source.is_file() or source.stat().st_size == 0:
            raise SystemExit(f'Missing module file: {source}')
        info = ZipInfo(member, date_time=(2020, 1, 1, 0, 0, 0))
        info.create_system = 3
        info.external_attr = (S_IFREG | (0o755 if member == 'service.sh' else 0o644)) << 16
        info.compress_type = ZIP_DEFLATED
        archive.writestr(info, source.read_bytes(), compress_type=ZIP_DEFLATED,
                         compresslevel=9)
print(output.resolve())
PY
