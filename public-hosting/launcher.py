#!/usr/bin/env python3
"""systemd entry point; exec keeps Java as the service PID for graceful SIGINT."""
import os
from pathlib import Path


def java_args(env):
    return [env.get('JAVA_BIN', '/usr/bin/java'), '-Xms' + env.get('SSM_XMS', '1G'),
            '-Xmx' + env.get('SSM_XMX', '4G'), '-Djava.awt.headless=true',
            '-XX:+ExitOnOutOfMemoryError', '-jar', 'paper-26.3-245.jar', '--nogui']


if __name__ == '__main__':
    os.chdir(Path(__file__).resolve().parent / 'server')
    settings = Path('plugins/super-smash-mobs-brawl/resource-pack.properties').read_text()
    if 'SET_PUBLIC_HOST' in settings:
        raise SystemExit('Set the public resource-pack URL before starting the server.')
    args = java_args(os.environ)
    os.execv(args[0], args)
