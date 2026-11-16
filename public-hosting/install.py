#!/usr/bin/env python3
"""First installation on Ubuntu 24.04 ARM64/x86_64. Refuses to replace a server."""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile
import urllib.request

HERE = Path(__file__).resolve().parent
TARGET = Path('/opt/ssm')


def public_host(value):
    if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}', value):
        raise ValueError('Supply a public IPv4 address or DNS hostname, without a URL or port.')
    if value.lower() == 'localhost' or value.lower().endswith(('.localhost', '.local')):
        raise ValueError('Players cannot download a pack from a local-only hostname.')
    try:
        address = ipaddress.ip_address(value)
    except ValueError:
        if re.fullmatch(r'[0-9.]+', value) or '.' not in value or any(len(s) > 63 or not re.fullmatch(r'[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?', s) for s in value.split('.')):
            raise ValueError('Invalid public hostname.')
    else:
        if not address.is_global:
            raise ValueError('Supply the VM public address, not its private address.')
    return value.lower()


def verified_archive(archive, expected):
    with archive.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if digest != expected:
        raise ValueError('Server archive checksum mismatch; upload it again.')
    with tarfile.open(archive, 'r:gz') as tar:
        for member in tar:
            parts = Path(member.name).parts
            if not parts or parts[0] != 'server' or '..' in parts or '\\' in member.name or ':' in member.name:
                raise ValueError('Unsafe archive path: ' + member.name)
            if not member.isfile() and not member.isdir():
                raise ValueError('Archive may only contain regular files/directories.')


def run(*args, **kwargs):
    subprocess.run(list(args), check=True, **kwargs)


def install_java():
    env = dict(os.environ, DEBIAN_FRONTEND='noninteractive')
    run('apt-get', 'update', env=env)
    run('apt-get', 'install', '-y', 'ca-certificates', 'wget', 'gpg', 'fontconfig', 'libfreetype6', env=env)
    key = urllib.request.urlopen('https://packages.adoptium.net/artifactory/api/gpg/key/public', timeout=60).read()
    converted = subprocess.run(['gpg', '--dearmor'], input=key, check=True, stdout=subprocess.PIPE).stdout
    Path('/usr/share/keyrings/adoptium.gpg').write_bytes(converted)
    Path('/etc/apt/sources.list.d/adoptium.list').write_text(
        'deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb noble main\n')
    run('apt-get', 'update', env=env)
    run('apt-get', 'install', '-y', 'temurin-25-jdk', env=env)
    candidates = sorted(Path('/usr/lib/jvm').glob('temurin-25-*/bin/java'))
    if len(candidates) != 1:
        raise RuntimeError('Cannot uniquely locate Temurin Java 25: ' + str(candidates))
    run(str(candidates[0]), '-version')
    return candidates[0]


def open_firewall():
    """Oracle's Ubuntu images reject everything except SSH in the VM's own iptables. Allow the two game ports."""
    if not shutil.which('iptables'):
        print('No iptables on this VM; nothing to open here.')
        return
    for port in ('25565', '25566'):
        rule = ['INPUT', '-p', 'tcp', '--dport', port, '-m', 'conntrack', '--ctstate', 'NEW', '-j', 'ACCEPT']
        if subprocess.run(['iptables', '-C'] + rule, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
            run('iptables', '-I', *rule)  # inserted first, so it sits above the image's REJECT rule
    if shutil.which('netfilter-persistent'):
        run('netfilter-persistent', 'save')  # keep the rules after a reboot
    else:
        print('netfilter-persistent is not installed: the rules last until the next reboot.')
    print('VM firewall: TCP 25565 and 25566 are open.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--public-host', required=True, type=public_host)
    parser.add_argument('--start', action='store_true', help='Enable autostart and start after installation.')
    parser.add_argument('--open-firewall', action='store_true', help='Allow TCP 25565/25566 in the VM firewall.')
    args = parser.parse_args()
    if platform.system() != 'Linux' or os.geteuid() != 0:
        raise SystemExit('Run with sudo on the target Ubuntu VM.')
    if args.open_firewall:
        open_firewall()  # first, so re-running on an installed VM can still fix the firewall
    release = dict(line.split('=', 1) for line in Path('/etc/os-release').read_text().splitlines() if '=' in line)
    if release.get('ID', '').strip('"') != 'ubuntu' or release.get('VERSION_ID', '').strip('"') != '24.04':
        raise SystemExit('This installer targets Ubuntu 24.04. Choose that image on Oracle.')
    if TARGET.exists() or Path('/etc/systemd/system/ssm.service').exists():
        raise SystemExit('An installation already exists; no server files were replaced.')
    manifest = json.loads((HERE / 'manifest.json').read_text())
    verified_archive(HERE / 'server.tar.gz', manifest['archive_sha256'])
    java = install_java()
    if subprocess.run(['id', '-u', 'ssm'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        run('useradd', '--system', '--home-dir', '/opt/ssm', '--shell', '/usr/sbin/nologin', 'ssm')
    TARGET.mkdir(mode=0o750)
    with tarfile.open(HERE / 'server.tar.gz', 'r:gz') as tar:
        tar.extractall(TARGET, filter='data')
    for name in ('launcher.py', 'backup.py', 'verify.py'):
        shutil.copyfile(HERE / name, TARGET / name)
    shutil.copyfile(HERE / 'manifest.json', TARGET / 'manifest.json')
    pack = TARGET / 'server/plugins/super-smash-mobs-brawl/resource-pack.properties'
    pack.write_text('enabled=true\nport=25566\nbind=0.0.0.0\npublic-url=http://' + args.public_host + ':25566/ssm-pack.zip\n')
    run('chown', '-R', 'ssm:ssm', str(TARGET / 'server'))
    run('chown', 'root:ssm', str(TARGET))
    Path('/etc/ssm').mkdir(mode=0o750, exist_ok=True)
    settings = Path('/etc/ssm/ssm.env')
    settings.write_text(f'JAVA_BIN={java}\nSSM_XMS=1G\nSSM_XMX=4G\n')
    settings.chmod(0o640)
    shutil.copyfile(HERE / 'ssm.service', '/etc/systemd/system/ssm.service')
    run('systemctl', 'daemon-reload')
    if args.start:
        run('systemctl', 'enable', '--now', 'ssm.service')
    print('Installed to /opt/ssm. Public game port: TCP 25565; pack port: TCP 25566.')
    print('Cloud and OS firewall rules must allow those ports. See README.txt.')
    print('Startup is only verified after verify.py passes and a remote Minecraft client joins.')


if __name__ == '__main__':
    main()
