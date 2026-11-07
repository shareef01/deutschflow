"""Collect process-cold ActivityManager launch timings; never clears app data."""
import argparse
import json
import statistics
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True, help='Explicit target device')
    parser.add_argument('--runs', type=int, default=10)
    parser.add_argument('--package', default='com.aus.deutschflow')
    parser.add_argument('--build-label', default='unspecified', help='Build/commit measured')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.runs <= 100:
        parser.error('--runs must be between 1 and 100')

    def adb(*command):
        return subprocess.run([args.adb, '-s', args.serial, *command],
                              check=True, capture_output=True, text=True,
                              timeout=60).stdout.strip()

    rows = []
    for _ in range(args.runs):
        # -S force-stops the process; -W waits for activity launch completion.
        raw = adb('shell', 'am', 'start', '-S', '-W', '-n',
                  args.package + '/com.aus.deutschflow.MainActivity')
        fields = dict(line.split(': ', 1) for line in raw.splitlines() if ': ' in line)
        if fields.get('Status') != 'ok' or 'TotalTime' not in fields:
            raise RuntimeError('Launch failed or timing missing: ' + raw)
        rows.append({key: int(fields[key]) for key in
                     ('ThisTime', 'TotalTime', 'WaitTime') if key in fields})
    result = {
        'recordedUtc': datetime.now(timezone.utc).isoformat(),
        'model': adb('shell', 'getprop', 'ro.product.model'),
        'sdk': adb('shell', 'getprop', 'ro.build.version.sdk'),
        'applicationId': args.package,
        'buildLabel': args.build_label,
        'package': adb('shell', 'dumpsys', 'package', args.package).splitlines(),
        'method': 'am start -S -W; process-cold, installed build, not Macrobenchmark/TTFD',
        'samplesMs': rows,
        'medianTotalMs': statistics.median(row['TotalTime'] for row in rows),
    }
    # Package metadata is local build information, not application content.
    result['package'] = [line.strip() for line in result['package']
                         if 'versionCode=' in line or 'versionName=' in line]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(f"{len(rows)} launches; median TotalTime {result['medianTotalMs']} ms")


if __name__ == '__main__':
    main()
