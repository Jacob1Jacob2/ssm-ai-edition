#!/usr/bin/env python3
"""Checks a live game status packet and generated resource pack from any computer."""
import argparse
import hashlib
import io
import json
import socket
import struct
import urllib.request
import zipfile


def varint(value):
    out = bytearray()
    while True:
        part = value & 127
        value >>= 7
        out.append(part | (128 if value else 0))
        if not value:
            return bytes(out)


def read_varint(sock):
    result = 0
    for index in range(5):
        chunk = sock.recv(1)
        if not chunk:
            raise EOFError('Server closed connection')
        result |= (chunk[0] & 127) << (7 * index)
        if chunk[0] < 128:
            return result
    raise ValueError('Invalid VarInt')


def status(host, port):
    with socket.create_connection((host, port), timeout=15) as sock:
        address = host.encode()
        handshake = varint(0) + varint(0) + varint(len(address)) + address + struct.pack('>H', port) + varint(1)
        sock.sendall(varint(len(handshake)) + handshake + b'\x01\x00')
        packet_size = read_varint(sock)
        if not 1 < packet_size < 1024 * 1024 or read_varint(sock) != 0:
            raise ValueError('Not a Minecraft status response')
        remaining = read_varint(sock)
        if remaining > packet_size:
            raise ValueError('Invalid status size')
        data = bytearray()
        while len(data) < remaining:
            chunk = sock.recv(remaining - len(data))
            if not chunk:
                raise EOFError('Incomplete status')
            data.extend(chunk)
        return json.loads(data)


def verify(host, game_port=25565, pack_port=25566):
    info = status(host, game_port)
    # Keep reports readable; the status favicon can be thousands of base64 bytes.
    info.pop('favicon', None)
    with urllib.request.urlopen(f'http://{host}:{pack_port}/ssm-pack.zip', timeout=30) as response:
        pack = response.read()
    with zipfile.ZipFile(io.BytesIO(pack)) as archive:
        names = set(archive.namelist())
        required = {'pack.mcmeta', 'assets/ssm/font/maps.json'}
        if not required <= names:
            raise ValueError('SSM resource pack missing required assets')
        pictures = len([n for n in names if n.startswith('assets/ssm/textures/font/maps/')])
        if pictures != 45:
            raise ValueError(f'Expected 45 map pictures, found {pictures}')
    return {'game_status': info, 'pack_bytes': len(pack), 'pack_sha1': hashlib.sha1(pack).hexdigest(),
            'map_pictures': pictures, 'checks': 'Game status and resource-pack HTTP passed; does not simulate a player login.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('host')
    parser.add_argument('--game-port', type=int, default=25565)
    parser.add_argument('--pack-port', type=int, default=25566)
    args = parser.parse_args()
    print(json.dumps(verify(args.host, args.game_port, args.pack_port), indent=2))
