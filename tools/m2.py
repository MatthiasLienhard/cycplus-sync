"""Cycplus M2 / XOSS bike computer client over BLE (Nordic UART + YMODEM).

Standalone implementation — no vendor cloud, no companion app.
Verified against a Cycplus M2 running firmware V1.4.0.

Protocol notes live in docs/PROTOCOL.md.
"""

from __future__ import annotations

import asyncio
import contextlib
from dataclasses import dataclass

from bleak import BleakClient, BleakScanner

SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  # we write ACK/NAK/'C' here
TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  # file blocks arrive here
CTL = "6e400004-b5a3-f393-e0a9-e50e24dcca9e"  # commands and their replies

BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"
FIRMWARE_REV = "00002a26-0000-1000-8000-00805f9b34fb"
MODEL_NUMBER = "00002a24-0000-1000-8000-00805f9b34fb"

SOH, STX = 0x01, 0x02
ACK, NAK, EOT, CAN, C = 0x06, 0x15, 0x04, 0x18, 0x43

CMD_STATUS = bytes([0xFF, 0x00, 0xFF])
CMD_IDLE = bytes([0x04, 0x00, 0x04])
CMD_DISKSPACE = bytes([0x09, 0x00, 0x09])
CMD_FETCH = 0x05
RSP_FETCH_OK = 0x06

FILELIST = "filelist.txt"


class M2Error(Exception):
    pass


@dataclass
class DeviceFile:
    name: str
    size: int


def crc8_xor(data: bytes) -> int:
    crc = 0
    for b in data:
        crc ^= b
    return crc & 0xFF


def crc16_arc(data: bytes) -> int:
    crc = 0
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc & 0xFFFF


def command(code: int, name: str) -> bytes:
    body = bytes([code]) + name.encode() + b"\x00"
    return body[:-1] + bytes([crc8_xor(body)])


async def find_device(prefix: str = "M2_", timeout: float = 30.0):
    """Find the first advertising bike computer whose name starts with `prefix`."""
    return await BleakScanner.find_device_by_filter(
        lambda d, ad: (d.name or ad.local_name or "").startswith(prefix),
        timeout=timeout,
    )


async def scan(timeout: float = 10.0) -> list[tuple[str, str, int]]:
    """Return (name, address, rssi) for every BLE device seen."""
    seen: dict[str, tuple[str, str, int]] = {}
    async with BleakScanner() as scanner:
        try:
            async with asyncio.timeout(timeout):
                async for bd, ad in scanner.advertisement_data():
                    name = bd.name or ad.local_name or ""
                    seen[bd.address] = (name, bd.address, ad.rssi)
        except TimeoutError:
            pass
    return list(seen.values())


class M2:
    """Talks to the device: file list, file download, battery, firmware, free space."""

    def _describe_block(self, block: bytes, block_size: int | None = None) -> str:
        kind = "?"
        if len(block) >= 1:
            if block[0] == SOH:
                kind = "SOH"
            elif block[0] == STX:
                kind = "STX"
            else:
                kind = f"0x{block[0]:02x}"
        size = block_size if block_size is not None else len(block)
        return (
            f"kind={kind} total_len={len(block)} parsed_len={size} "
            f"header={bytes(block[:min(12, len(block))]).hex(' ')} "
            f"tail={bytes(block[-min(16, len(block)):]).hex(' ')}"
        )

    def _log_m3_frame(self, frame: bytes) -> None:
        if len(frame) < 7 or frame[:2] != b"\x55\xaa":
            return
        seq = frame[2] if len(frame) > 2 else None
        flags = frame[3:5].hex(" ") if len(frame) >= 5 else ""
        trailer = frame[-2:].hex(" ") if len(frame) >= 2 else ""
        payload = frame[5:-2] if len(frame) >= 7 else b""
        preview = bytes(frame[:min(24, len(frame))]).hex(" ")
        ascii_preview = "".join(chr(b) if 32 <= b < 127 else "." for b in payload[:16])
        crc_be = crc16_arc(payload)
        crc_le = ((crc_be & 0xFF) << 8) | (crc_be >> 8)
        self._log(
            "  M3 frame: "
            f"seq={seq} flags={flags} payload_len={len(payload)} "
            f"trailer={trailer} crc_arc_be=0x{crc_be:04x} "
            f"crc_arc_le=0x{crc_le:04x} preview={preview} ascii={ascii_preview!r}"
        )

    def __init__(self, client: BleakClient, verbose: bool = True):
        self.client = client
        self.verbose = verbose
        self._ctl: asyncio.Queue[bytes] = asyncio.Queue()
        self._tx: asyncio.Queue[bytes] = asyncio.Queue()
        self._rx_buffer = bytearray()

    def _log(self, message: str) -> None:
        if self.verbose:
            print(message, flush=True)

    async def start(self) -> None:
        await self.client.start_notify(
            CTL, lambda _, data: self._ctl.put_nowait(bytes(data))
        )
        await self.client.start_notify(
            TX, lambda _, data: self._tx.put_nowait(bytes(data))
        )

    async def stop(self) -> None:
        for uuid in (CTL, TX):
            # На закрытии устройство уже могло отвалиться — отписка best-effort.
            with contextlib.suppress(Exception):
                await self.client.stop_notify(uuid)

    async def _write(self, uuid: str, value: bytes) -> None:
        await self.client.write_gatt_char(uuid, value, False)

    async def _ctl_reply(self, timeout: float = 5.0) -> bytes:
        return await asyncio.wait_for(self._ctl.get(), timeout)

    def _drain(self) -> None:
        for queue in (self._ctl, self._tx):
            while not queue.empty():
                queue.get_nowait()

    async def ensure_idle(self) -> None:
        self._drain()
        await self._write(CTL, CMD_STATUS)
        try:
            reply = await self._ctl_reply(3.0)
        except asyncio.TimeoutError:
            await self._write(CTL, CMD_IDLE)
            reply = await self._ctl_reply(3.0)
        # M2 answers with a bare 0x04; XOSS G+ answers with 04 00 04.
        if reply not in (CMD_IDLE, bytes([EOT])):
            raise M2Error(f"device not idle: {reply.hex(' ')}")

    async def battery(self) -> int | None:
        try:
            return (await self.client.read_gatt_char(BATTERY_LEVEL))[0]
        except Exception:
            return None

    async def firmware(self) -> str | None:
        try:
            return (await self.client.read_gatt_char(FIRMWARE_REV)).decode().strip()
        except Exception:
            return None

    async def model(self) -> str | None:
        try:
            return (await self.client.read_gatt_char(MODEL_NUMBER)).decode().strip()
        except Exception:
            return None

    async def disk_space(self) -> str | None:
        self._drain()
        await self._write(CTL, CMD_DISKSPACE)
        try:
            reply = await self._ctl_reply(3.0)
        except asyncio.TimeoutError:
            return None
        if not reply or reply[0] != 0x0A:
            return None
        return reply[1:-1].decode().strip()

    async def _read_block(self, timeout: float = 15.0) -> bytes | None:
        """Collect one block from either the legacy YMODEM path or the M3 custom frame path."""
        buf = self._rx_buffer
        self._rx_buffer = bytearray()
        packet_count = 0

        while True:
            if buf.startswith(b"\x55\xaa"):
                if len(buf) < 1031:
                    packet = await asyncio.wait_for(self._tx.get(), timeout)
                    packet_count += 1
                    if packet_count <= 12:
                        self._log(
                            f"  raw TX[{packet_count}] len={len(packet)} "
                            f"head={bytes(packet[:min(16, len(packet))]).hex(' ')}"
                        )
                    if packet == bytes([EOT]):
                        self._rx_buffer = bytearray()
                        return None
                    if packet:
                        buf.extend(packet)
                    continue

                frame = bytes(buf[:1031])
                self._log_m3_frame(frame)
                payload = frame[5:1029]
                self._rx_buffer = bytearray(buf[1031:])
                return payload

            if buf:
                start = buf.find(bytes([SOH]))
                if start < 0:
                    start = buf.find(bytes([STX]))
                if start >= 0:
                    if start > 0:
                        self._log(
                            "  discarding noise before block: "
                            f"len={start} head={bytes(buf[:min(start, 16)]).hex(' ')}"
                        )
                    del buf[:start]
                else:
                    if len(buf) >= 1031 and buf.startswith(b"\x55\xaa"):
                        continue
                    buf.clear()
                    packet = await asyncio.wait_for(self._tx.get(), timeout)
                    packet_count += 1
                    if packet_count <= 12:
                        self._log(
                            f"  raw TX[{packet_count}] len={len(packet)} "
                            f"head={bytes(packet[:min(16, len(packet))]).hex(' ')}"
                        )
                    if packet == bytes([EOT]):
                        self._rx_buffer = bytearray()
                        return None
                    if any(b in packet for b in (SOH, STX, 0x55, 0xAA)):
                        buf.extend(packet)
                    continue

            if not buf:
                packet = await asyncio.wait_for(self._tx.get(), timeout)
                packet_count += 1
                if packet_count <= 12:
                    self._log(
                        f"  raw TX[{packet_count}] len={len(packet)} "
                        f"head={bytes(packet[:min(16, len(packet))]).hex(' ')}"
                    )
                if packet == bytes([EOT]):
                    self._rx_buffer = bytearray()
                    return None
                if any(b in packet for b in (SOH, STX, 0x55, 0xAA)):
                    buf.extend(packet)
                continue

            block_size = 3 + (1024 if buf[0] == STX else 128) + 2
            if len(buf) < block_size:
                try:
                    packet = await asyncio.wait_for(self._tx.get(), timeout)
                except asyncio.TimeoutError:
                    raise
                packet_count += 1
                if packet_count <= 12:
                    self._log(
                        f"  raw TX[{packet_count}] len={len(packet)} "
                        f"head={bytes(packet[:min(16, len(packet))]).hex(' ')}"
                    )
                if packet == bytes([EOT]):
                    if not buf:
                        return None
                    break
                buf.extend(packet)
                continue

            payload = bytes(buf[3 : block_size - 2])
            crc = (buf[block_size - 2] << 8) | buf[block_size - 1]
            calculated = crc16_arc(payload)
            if buf[1] != (0xFF ^ buf[2]):
                self._log(
                    "  bad block header: "
                    f"num={buf[1]} inv={buf[2]} "
                    f"head={bytes(buf[:8]).hex(' ')}"
                )
                del buf[0]
                continue
            if crc != calculated:
                self._log(
                    "  CRC mismatch: "
                    f"received={crc:04x} calculated={calculated:04x} "
                    f"payload_len={len(payload)} "
                    f"{self._describe_block(bytes(buf[:block_size]), block_size)}"
                )
                raise M2Error("bad block: CRC mismatch")

            self._rx_buffer = bytearray(buf[block_size:])
            return payload

    async def fetch(self, name: str) -> bytes:
        await self.ensure_idle()
        self._drain()

        await self._write(CTL, command(CMD_FETCH, name))
        reply = await self._ctl_reply(5.0)
        if not reply or reply[0] != RSP_FETCH_OK:
            raise M2Error(f"device refused file {name}: {reply.hex(' ')}")

        await self._write(RX, bytes([C]))
        header = await self._read_block()
        if header is None:
            raise M2Error("no file header")
        expected = int(header.rstrip(b"\x00").decode().split()[1])

        await self._write(RX, bytes([ACK]))
        await self._write(RX, bytes([C]))

        data = bytearray()
        packet_index = 0
        while True:
            try:
                block = await self._read_block()
            except M2Error:
                self._log("  retrying a block")
                await self._write(RX, bytes([NAK]))
                continue
            if block is None:
                break
            packet_index += 1
            if packet_index <= 3:
                self._log(
                    f"  payload block #{packet_index}: "
                    f"len={len(block)} first16={bytes(block[:16]).hex(' ')}"
                )
            data += block
            await self._write(RX, bytes([ACK]))

        # End of transfer: NAK -> second EOT -> ACK -> idle
        await self._write(RX, bytes([NAK]))
        try:
            await asyncio.wait_for(self._tx.get(), 5.0)
        except asyncio.TimeoutError:
            pass
        await self._write(RX, bytes([ACK]))
        try:
            await self._ctl_reply(3.0)
        except asyncio.TimeoutError:
            pass

        if len(data) < expected:
            raise M2Error(f"file shorter than declared: {len(data)} of {expected}")
        return bytes(data[:expected])

    async def list_files(self) -> list[DeviceFile]:
        raw = (await self.fetch(FILELIST)).decode(errors="replace")
        files = []
        for line in raw.splitlines():
            parts = line.split()
            if parts and parts[0].endswith(".fit"):
                size = int(parts[1]) if len(parts) > 1 and parts[1].isdigit() else 0
                files.append(DeviceFile(parts[0], size))
        return files
