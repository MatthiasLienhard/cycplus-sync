#!/usr/bin/env python
"""Command line companion for a Cycplus M2 / XOSS bike computer.

    python m2_cli.py scan                 # list BLE devices nearby
    python m2_cli.py info                 # model, firmware, battery, free space
    python m2_cli.py files                # rides stored on the device
    python m2_cli.py sync [--out DIR]     # download every new .fit
    python m2_cli.py get <name>           # download one file (e.g. Setting.json)
    python m2_cli.py services             # dump the GATT service map

Options: --name PREFIX (default M2_), --timeout SECONDS
"""

from __future__ import annotations

import argparse
import asyncio
import os
import sys
from uuid import UUID

from bleak import BleakClient
from m2 import M2, M2Error, find_device, scan


def _uuid_label(value: str) -> str:
    try:
        return str(UUID(value).int)
    except ValueError:
        return value


def _uuid_name(value: str) -> str:
    try:
        return str(UUID(value))
    except ValueError:
        return value


async def connected(args):
    device = await find_device(args.name, args.timeout)
    if not device:
        print(
            f"No device with a name starting with {args.name!r} found.", file=sys.stderr
        )
        raise SystemExit(1)
    print(f"Found {device.name} — {device.address}")
    return device


async def cmd_scan(args):
    for name, address, rssi in sorted(await scan(args.timeout), key=lambda x: -x[2]):
        mark = (
            "  <- bike computer?"
            if name.startswith(("M2_", "M1_", "M3_")) or "XOSS" in name.upper()
            else ""
        )
        print(f"{name or '(unnamed)':<28} {address}  rssi={rssi}{mark}")


async def cmd_info(args):
    device = await connected(args)
    async with BleakClient(device.address, timeout=60.0) as client:
        m2 = M2(client)
        await m2.start()
        print(f"model:     {await m2.model()}")
        print(f"firmware:  {await m2.firmware()}")
        print(f"battery:   {await m2.battery()}%")
        print(f"free/total:{await m2.disk_space()} KB")
        print(f"MTU:       {client.mtu_size}")
        await m2.stop()


async def cmd_files(args):
    device = await connected(args)
    async with BleakClient(device.address, timeout=60.0) as client:
        m2 = M2(client)
        await m2.start()
        for f in await m2.list_files():
            print(f"{f.name}  {f.size} bytes")
        await m2.stop()


async def cmd_sync(args):
    os.makedirs(args.out, exist_ok=True)
    device = await connected(args)
    async with BleakClient(device.address, timeout=60.0) as client:
        m2 = M2(client)
        await m2.start()
        files = await m2.list_files()
        print(f"rides on the device: {len(files)}")
        for f in files:
            path = os.path.join(args.out, f.name)
            if os.path.exists(path) and os.path.getsize(path) == f.size:
                print(f"already here: {f.name}")
                continue
            print(f"downloading {f.name} ({f.size} bytes)")
            data = await m2.fetch(f.name)
            with open(path, "wb") as fh:
                fh.write(data)
            print(f"saved {path}")
        await m2.stop()


async def cmd_get(args):
    device = await connected(args)
    async with BleakClient(device.address, timeout=60.0) as client:
        m2 = M2(client)
        await m2.start()
        data = await m2.fetch(args.file)
        out = os.path.join(args.out, args.file)
        os.makedirs(args.out, exist_ok=True)
        with open(out, "wb") as fh:
            fh.write(data)
        print(f"saved {out} ({len(data)} bytes)")
        await m2.stop()


async def cmd_services(args):
    device = await connected(args)
    async with BleakClient(device.address, timeout=60.0) as client:
        print(f"MTU {client.mtu_size}\n")
        for service in client.services:
            title = _uuid_label(service.uuid)
            print(f"SERVICE {service.uuid}  {title}")
            for ch in service.characteristics:
                value = ""
                if "read" in ch.properties:
                    try:
                        raw = await client.read_gatt_char(ch)
                        try:
                            value = f"= {raw.decode().strip()!r}"
                        except UnicodeDecodeError:
                            value = f"= {raw.hex(' ')}"
                    except Exception:
                        value = "(read failed)"
                known = _uuid_name(ch.uuid)
                print(
                    f"   CHAR {ch.uuid}  [{','.join(ch.properties)}]  {known} {value}"
                )


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--name", default="M2_", help="BLE name prefix (default: M2_)")
    parser.add_argument(
        "--timeout", type=float, default=30.0, help="scan timeout, seconds"
    )
    parser.add_argument(
        "--out", default="fit", help="output directory (default: ./fit)"
    )
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("scan")
    sub.add_parser("info")
    sub.add_parser("files")
    sub.add_parser("sync")
    sub.add_parser("services")
    get = sub.add_parser("get")
    get.add_argument("file", help="file name on the device, e.g. Setting.json")

    args = parser.parse_args()
    handler = {
        "scan": cmd_scan,
        "info": cmd_info,
        "files": cmd_files,
        "sync": cmd_sync,
        "get": cmd_get,
        "services": cmd_services,
    }[args.command]

    try:
        asyncio.run(handler(args))
    except M2Error as e:
        print(f"error: {e}", file=sys.stderr)
        raise SystemExit(2)


if __name__ == "__main__":
    main()
