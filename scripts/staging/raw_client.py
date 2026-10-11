"""Minimal offline loopback client for new protocols, not a gameplay client.

Packet IDs are extracted from the exact patched server's registration bytecode
using javap, never guessed by relabelling an older Minecraft protocol. The
client consumes configuration, acknowledges teleport/keepalive and observes
PLAY login; smoke.py independently requires Paper JOIN and capacity snapshots.
"""
import hashlib
import ipaddress
import io
import json
from pathlib import Path
import re
import socket
import struct
import subprocess
import threading
import zipfile
import zlib


def varint(number):
    result = bytearray()
    while number > 127:
        result.append((number & 127) | 128)
        number >>= 7
    result.append(number)
    return bytes(result)


def read_varint(read):
    value = 0
    for i in range(5):
        byte = read(1)[0]
        value |= (byte & 127) << (7 * i)
        if not byte & 128:
            return value
    raise ValueError('Oversized VarInt')


def text(value):
    encoded = value.encode()
    return varint(len(encoded)) + encoded


def mappings(runtime, java, version):
    jar = runtime / 'versions' / version / ('paper-' + version + '.jar')
    javap = str(Path(java).with_name('javap'))
    result = {}
    for stage, klass in [('login', 'login.LoginProtocols'), ('configuration', 'configuration.ConfigurationProtocols'), ('play', 'game.GameProtocols')]:
        output = subprocess.check_output([javap, '-c', '-p', '-classpath', str(jar),
                                          'net.minecraft.network.protocol.' + klass], text=True)
        for flow in ['CLIENTBOUND', 'SERVERBOUND']:
            fields = re.findall(r'// Field [\w/$]+\.(' + flow + r'_[A-Z_]+):Lnet/minecraft/network/protocol/PacketType;', output)
            if not fields or len(set(fields)) != len(fields):
                raise ValueError('Unsupported packet registration structure: ' + stage + ' ' + flow)
            result[stage + '_' + flow] = {name.removeprefix(flow + '_'): index for index, name in enumerate(fields)}
    for cached in (runtime / 'cache').glob('*.jar'):
        with zipfile.ZipFile(cached) as archive:
            if 'version.json' in archive.namelist():
                metadata = json.loads(archive.read('version.json'))
                if metadata['id'] == version:
                    result['protocol'] = metadata['protocol_version']
                    break
    if 'protocol' not in result:
        raise ValueError('Missing exact server protocol version')
    result['server_sha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
    teleport = subprocess.check_output([javap, '-c', '-p', '-classpath', str(jar),
                                       'net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket'], text=True)
    result['teleport_includes_position'] = 'ByteBufCodecs.DOUBLE' in teleport
    (runtime / 'raw-protocol-map.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


class Client:
    def __init__(self, identifier, name, address, mapping, emit):
        if not ipaddress.ip_address(address).is_loopback or ':' in address:
            raise ValueError('Staging source must be IPv4 loopback')
        if not re.fullmatch(r'[A-Za-z0-9_]{1,16}', name):
            raise ValueError('Invalid player name')
        self.id, self.name, self.address, self.mapping, self.emit = identifier, name, address, mapping, emit
        self.socket = None
        self.compression = False
        self.closed = False
        self.stage = 'login'

    def close(self):
        self.closed = True
        if self.socket:
            try:
                self.socket.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            self.socket.close()

    def read(self, length):
        result = bytearray()
        while len(result) < length:
            part = self.socket.recv(length - len(result))
            if not part:
                raise EOFError('socketClosed')
            result.extend(part)
        return bytes(result)

    def send(self, identifier, data=b''):
        payload = varint(identifier) + data
        if self.compression:
            # Uncompressed framing is legal below the configured compression threshold.
            payload = b'\0' + payload
        self.socket.sendall(varint(len(payload)) + payload)

    def packet(self):
        size = read_varint(self.read)
        if size > 8 * 1024 * 1024:
            raise ValueError('Oversized packet')
        payload = self.read(size)
        if self.compression:
            offset = 0
            def take(n):
                nonlocal offset
                part = payload[offset:offset+n]
                offset += n
                return part
            decompressed = read_varint(take)
            payload = zlib.decompress(payload[offset:]) if decompressed else payload[offset:]
            if decompressed and len(payload) != decompressed:
                raise ValueError('Compression length mismatch')
        offset = 0
        def take(n):
            nonlocal offset
            part = payload[offset:offset+n]
            offset += n
            return part
        identifier = read_varint(take)
        return identifier, payload[offset:]

    def run(self):
        try:
            self.socket = socket.socket()
            self.socket.settimeout(30)
            self.socket.bind((self.address, 0))
            self.socket.connect(('127.0.0.1', 25576))
            self.send(0, varint(self.mapping['protocol']) + text('127.0.0.1') + struct.pack('>H', 25576) + varint(2))
            self.send(0, text(self.name) + bytes(16))
            while not self.closed:
                identifier, data = self.packet()
                incoming = self.mapping[self.stage + '_CLIENTBOUND']
                outgoing = self.mapping[self.stage + '_SERVERBOUND']
                kind = next((name for name, value in incoming.items() if value == identifier), '')
                if kind in ('LOGIN_DISCONNECT', 'DISCONNECT'):
                    self.emit({'id': self.id, 'event': 'kick', 'reason': data.hex()})
                    break
                if self.stage == 'login':
                    if kind == 'LOGIN_COMPRESSION':
                        self.compression = True
                    elif kind == 'LOGIN_FINISHED':
                        self.send(outgoing['LOGIN_ACKNOWLEDGED'])
                        self.stage = 'configuration'
                elif self.stage == 'configuration':
                    if kind == 'SELECT_KNOWN_PACKS':
                        self.send(outgoing['SELECT_KNOWN_PACKS'], b'\0')
                    elif kind == 'FINISH_CONFIGURATION':
                        self.send(outgoing['FINISH_CONFIGURATION'])
                        self.stage = 'play'
                    elif kind == 'CODE_OF_CONDUCT':
                        self.send(outgoing['ACCEPT_CODE_OF_CONDUCT'])
                    elif kind == 'KEEP_ALIVE':
                        self.send(outgoing['KEEP_ALIVE'], data)
                else:
                    if kind == 'LOGIN':
                        self.emit({'id': self.id, 'event': 'play'})
                    elif kind == 'KEEP_ALIVE':
                        self.send(outgoing['KEEP_ALIVE'], data)
                    elif kind == 'PLAYER_POSITION':
                        reader = io.BytesIO(data)
                        teleport = read_varint(reader.read)
                        response = varint(teleport)
                        if self.mapping['teleport_includes_position']:
                            position = reader.read(60)
                            # Initial spawn uses absolute position/rotation. Refuse unimplemented relative movement.
                            if len(position) != 60 or struct.unpack('>I', position[56:60])[0] != 0:
                                raise ValueError('Unsupported relative teleport in staging client')
                            response += position[:24] + position[48:56]
                        self.send(outgoing['ACCEPT_TELEPORTATION'], response)
        except (EOFError, OSError) as error:
            if not self.closed and not isinstance(error, EOFError):
                self.emit({'id': self.id, 'event': 'error', 'error': str(error)})
        except Exception as error:
            self.emit({'id': self.id, 'event': 'error', 'error': str(error)})
        finally:
            self.close()
            self.emit({'id': self.id, 'event': 'end'})
