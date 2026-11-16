#!/usr/bin/env python3
"""Manual consistent backup. Briefly stops SSM and restarts it if it was running."""
from datetime import datetime, timezone
import os
from pathlib import Path
import subprocess
import tarfile

if __name__ == '__main__':
    if os.geteuid() != 0:
        raise SystemExit('Run with sudo. This pauses the server while backing up.')
    root = Path('/opt/ssm/server')
    if not (root / 'paper-26.3-245.jar').is_file():
        raise SystemExit('Expected SSM installation not found.')
    destination = Path('/var/backups/ssm')
    destination.mkdir(mode=0o700, parents=True, exist_ok=True)
    name = destination / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '.tar.gz')
    active = subprocess.run(['systemctl', 'is-active', '--quiet', 'ssm.service']).returncode == 0
    if active:
        subprocess.run(['systemctl', 'stop', 'ssm.service'], check=True)
    try:
        with tarfile.open(name, 'x:gz') as tar:
            for item in root.iterdir():
                if item.name not in ('logs', 'cache', 'libraries', 'versions', '.paper'):
                    tar.add(item, arcname='server/' + item.name)
        print(name)
    finally:
        if active:
            subprocess.run(['systemctl', 'start', 'ssm.service'], check=True)
