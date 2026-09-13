package dev.komkov.m2sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.util.Arrays
import java.util.UUID

class M2Error(
    message: String,
) : Exception(message)

data class FoundDevice(
    val name: String,
    val address: String,
    val rssi: Int,
)

data class DeviceFile(
    val name: String,
    val size: Int,
)

/**
 * Cycplus M2 (and related XOSS) bike computer client over Nordic UART Service.
 *
 * The device sends files using YMODEM: commands and responses use the CTL characteristic,
 * the block stream uses TX, and acknowledgements are written to RX. The protocol was tested
 * on M2 firmware V1.4.0: MTU 185, one notification per block, and STATUS responds with 0x04.
 */
@SuppressLint("MissingPermission")
class M2Client(
    private val ctx: Context,
) {
    companion object {
        val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CTL: UUID = UUID.fromString("6e400004-b5a3-f393-e0a9-e50e24dcca9e")
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
        val DEVICE_INFO: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        val FIRMWARE_REV: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val SOH: Byte = 0x01 // 128-byte data block
        private const val STX: Byte = 0x02 // 1024-byte data block
        private val M3_MAGIC0: Byte = 0x55.toByte()
        private val M3_MAGIC1: Byte = 0xAA.toByte()
        private const val ACK: Byte = 0x06
        private const val NAK: Byte = 0x15
        private const val EOT: Byte = 0x04
        private const val CAN: Byte = 0x18
        private const val C: Byte = 0x43 // request transfer start
        private const val M3_FRAME_SIZE: Int = 1031
        private const val M3_PAYLOAD_SIZE: Int = 1024

        private val CMD_STATUS = byteArrayOf(0xFF.toByte(), 0x00, 0xFF.toByte())
        private val CMD_IDLE = byteArrayOf(0x04, 0x00, 0x04)
        private val CMD_DISKSPACE = byteArrayOf(0x09, 0x00, 0x09)
        private const val CMD_FETCH: Byte = 0x05
        private const val RSP_FETCH_OK: Byte = 0x06

        const val FILELIST = "filelist.txt"
    }

    private val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private val ctlIn = Channel<ByteArray>(Channel.UNLIMITED)
    private val txIn = Channel<ByteArray>(Channel.UNLIMITED)
    private val opLock = Mutex()
    private var pendingRx = ByteArrayOutputStream()

    private var onConnected = CompletableDeferred<Unit>()
    private var onServices = CompletableDeferred<Unit>()
    private var onMtu = CompletableDeferred<Int>()
    private var onWrite = CompletableDeferred<Unit>()
    private var onDescriptor = CompletableDeferred<Unit>()
    private var onRead = CompletableDeferred<ByteArray>()

    var mtu: Int = 23
        private set

    // ---------------------------------------------------------------- discovery

    suspend fun scan(
        namePrefix: String,
        timeoutMs: Long = 15_000,
    ): List<FoundDevice> {
        val scanner = adapter?.bluetoothLeScanner ?: throw M2Error("Bluetooth is off")
        val found = LinkedHashMap<String, FoundDevice>()
        val cb =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                    val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                    val isCompatibleCycplus = name.startsWith("CYCPLUS", ignoreCase = true)
                    if (!name.startsWith(namePrefix, ignoreCase = true) && !(namePrefix.equals("M2_", ignoreCase = true) && isCompatibleCycplus)) return
                    if (found.put(name, FoundDevice(name, result.device.address, result.rssi)) == null) {
                        LogBus.i(R.string.log_found, name, result.device.address, result.rssi)
                    }
                }

                override fun onScanFailed(errorCode: Int) = LogBus.e(R.string.log_scan_failed, errorCode)
            }
        val settings =
            ScanSettings
                .Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
        scanner.startScan(emptyList(), settings, cb)
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline && found.isEmpty()) delay(200)
            if (found.isNotEmpty()) delay(1000) // добираем соседей
        } finally {
            scanner.stopScan(cb)
        }
        return found.values.toList()
    }

    // ------------------------------------------------------------ connection

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    if (!onConnected.isCompleted) onConnected.complete(Unit)
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    if (!onConnected.isCompleted) onConnected.completeExceptionally(M2Error("connection lost, status $status"))
                    ctlIn.close(M2Error("device disconnected"))
                    txIn.close(M2Error("device disconnected"))
                }
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    onServices.complete(Unit)
                } else {
                    onServices.completeExceptionally(M2Error("service discovery failed, status $status"))
                }
            }

            override fun onMtuChanged(
                g: BluetoothGatt,
                mtuValue: Int,
                status: Int,
            ) {
                mtu = mtuValue
                if (!onMtu.isCompleted) onMtu.complete(mtuValue)
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (!onWrite.isCompleted) onWrite.complete(Unit)
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                d: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (!onDescriptor.isCompleted) onDescriptor.complete(Unit)
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (!onRead.isCompleted) onRead.complete(value)
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                when (ch.uuid) {
                    CTL -> ctlIn.trySend(value.copyOf())
                    TX -> txIn.trySend(value.copyOf())
                }
            }
        }

    suspend fun connect(address: String) {
        val device: BluetoothDevice =
            adapter?.getRemoteDevice(address)
                ?: throw M2Error("no Bluetooth adapter")
        onConnected = CompletableDeferred()
        onServices = CompletableDeferred()
        onMtu = CompletableDeferred()
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)

        withTimeout(30_000) { onConnected.await() }
        LogBus.i(R.string.log_connected, address)

        withTimeout(10_000) {
            gatt!!.requestMtu(247)
            onMtu.await()
        }
        LogBus.i(R.string.log_mtu, mtu)

        gatt!!.discoverServices()
        withTimeout(20_000) { onServices.await() }

        enableNotify(CTL)
        enableNotify(TX)
        LogBus.i(R.string.log_notifications)
    }

    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    private suspend fun enableNotify(uuid: UUID) {
        val g = gatt ?: throw M2Error("not connected")
        val ch =
            g.getService(SERVICE)?.getCharacteristic(uuid)
                ?: throw M2Error("characteristic $uuid not found")
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD) ?: throw M2Error("no CCCD on $uuid")
        onDescriptor = CompletableDeferred()
        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        withTimeout(5_000) { onDescriptor.await() }
    }

    suspend fun readBattery(): Int? =
        runCatching {
            val ch = gatt?.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL) ?: return null
            onRead = CompletableDeferred()
            gatt!!.readCharacteristic(ch)
            withTimeout(5_000) { onRead.await() }.first().toInt() and 0xFF
        }.getOrNull()

    suspend fun readFirmware(): String? =
        runCatching {
            val ch = gatt?.getService(DEVICE_INFO)?.getCharacteristic(FIRMWARE_REV) ?: return null
            onRead = CompletableDeferred()
            gatt!!.readCharacteristic(ch)
            String(withTimeout(5_000) { onRead.await() }).trim()
        }.getOrNull()

    // -------------------------------------------------------------- commands

    private suspend fun write(
        uuid: UUID,
        value: ByteArray,
    ) = opLock.withLock {
        val g = gatt ?: throw M2Error("not connected")
        val ch =
            g.getService(SERVICE)?.getCharacteristic(uuid)
                ?: throw M2Error("characteristic $uuid not found")
        onWrite = CompletableDeferred()
        val rc = g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        if (rc != BluetoothGatt.GATT_SUCCESS) throw M2Error("write rejected, code $rc")
        runCatching { withTimeout(3_000) { onWrite.await() } }
        Unit
    }

    private suspend fun ctl(value: ByteArray) = write(CTL, value)

    private suspend fun rx(b: Byte) = write(RX, byteArrayOf(b))

    private suspend fun awaitCtl(timeoutMs: Long = 5_000): ByteArray = withTimeout(timeoutMs) { ctlIn.receive() }

    /** Команда вида [код][имя файла][crc8-xor]. */
    private fun command(
        code: Byte,
        name: String,
    ): ByteArray {
        val body = byteArrayOf(code) + name.toByteArray() + byteArrayOf(0)
        body[body.size - 1] = crc8Xor(body)
        return body
    }

    private suspend fun ensureIdle() {
        while (ctlIn.tryReceive().isSuccess) Unit
        ctl(CMD_STATUS)
        val rsp = runCatching { awaitCtl(3_000) }.getOrNull()
        // M2 responds with one byte, 0x04; XOSS G+ responds with three (04 00 04).
        if (rsp != null && (rsp.contentEquals(CMD_IDLE) || (rsp.size == 1 && rsp[0] == EOT))) return
        ctl(CMD_IDLE)
        val again =
            runCatching { awaitCtl(3_000) }.getOrNull()
                ?: throw M2Error("no answer to STATUS")
        if (!(again.contentEquals(CMD_IDLE) || (again.size == 1 && again[0] == EOT))) {
            throw M2Error("device not idle: ${again.toHex()}")
        }
    }

    suspend fun diskSpace(): String? {
        while (ctlIn.tryReceive().isSuccess) Unit
        ctl(CMD_DISKSPACE)
        val rsp = runCatching { awaitCtl(3_000) }.getOrNull() ?: return null
        if (rsp.isEmpty() || rsp[0] != 0x0A.toByte()) return null
        return String(rsp, 1, rsp.size - 2).trim()
    }

    // --------------------------------------------------------- file reception

    private sealed interface Block {
        data class Data(
            val num: Int,
            val payload: ByteArray,
        ) : Block

        data object End : Block
    }

    /** Collects one block from TX notifications.
     * After block 0, M3 sends a custom 55 aa stream with 1024 data bytes and a two-byte trailer,
     * so standard YMODEM parsing must only be used after a standard SOH/STX block is recognized.
     */
    private suspend fun readBlock(timeoutMs: Long = 15_000): Block {
        val buf = ByteArrayOutputStream()
        if (pendingRx.size() > 0) {
            buf.write(pendingRx.toByteArray())
            pendingRx.reset()
        }

        while (true) {
            if (buf.size() >= 2) {
                val head = buf.toByteArray()
                if (head[0] == M3_MAGIC0.toByte() && head[1] == M3_MAGIC1.toByte()) {
                    if (buf.size() < M3_FRAME_SIZE) {
                        val packet = withTimeout(timeoutMs) { txIn.receive() }
                        if (packet.size == 1 && packet[0] == EOT) return Block.End
                        buf.write(packet)
                        continue
                    }
                    val frame = buf.toByteArray()
                    val rest = Arrays.copyOfRange(frame, M3_FRAME_SIZE, frame.size)
                    pendingRx.reset()
                    pendingRx.write(rest)
                    val payload = frame.copyOfRange(5, 5 + M3_PAYLOAD_SIZE)
                    return Block.Data(frame[2].toInt() and 0xFF, payload)
                }
            }

            val packet = withTimeout(timeoutMs) { txIn.receive() }
            if (buf.size() == 0 && packet.size == 1 && packet[0] == EOT) return Block.End
            buf.write(packet)
            if (buf.size() > M3_FRAME_SIZE) throw M2Error("notification longer than expected")
            if (buf.size() < 2) continue

            val head = buf.toByteArray()
            if (head[0] == SOH.toByte() || head[0] == STX.toByte()) {
                val blockSize = if (head[0] == STX.toByte()) 3 + 1024 + 2 else 3 + 128 + 2
                if (buf.size() < blockSize) continue
                if (buf.size() > blockSize) throw M2Error("notification longer than expected")
                val payload = head.copyOfRange(3, 3 + (blockSize - 5))
                val crcGot = ((head[3 + (blockSize - 5)].toInt() and 0xFF) shl 8) or (head[4 + (blockSize - 5)].toInt() and 0xFF)
                if (crcGot != crc16Arc(payload)) throw M2Error("bad block: CRC mismatch")
                val rest = head.copyOfRange(blockSize, head.size)
                pendingRx.reset()
                pendingRx.write(rest)
                return Block.Data(head[1].toInt() and 0xFF, payload)
            }

            if (head[0] == M3_MAGIC0.toByte() && head[1] == M3_MAGIC1.toByte()) {
                if (buf.size() < M3_FRAME_SIZE) continue
                val frame = head
                val rest = Arrays.copyOfRange(frame, M3_FRAME_SIZE, frame.size)
                pendingRx.reset()
                pendingRx.write(rest)
                val payload = frame.copyOfRange(5, 5 + M3_PAYLOAD_SIZE)
                return Block.Data(frame[2].toInt() and 0xFF, payload)
            }
        }
    }

    suspend fun fetchFile(name: String): ByteArray {
        ensureIdle()
        while (txIn.tryReceive().isSuccess) Unit

        ctl(command(CMD_FETCH, name))
        val ok = awaitCtl(5_000)
        if (ok.isEmpty() || ok[0] != RSP_FETCH_OK) throw M2Error("device refused file $name: ${ok.toHex()}")

        rx(C)
        val header = readBlock() as? Block.Data ?: throw M2Error("no file header")
        val headerText = String(header.payload).trimEnd('\u0000').trim()
        val expected =
            headerText.split(" ").getOrNull(1)?.toIntOrNull()
                ?: throw M2Error("could not parse header: $headerText")

        rx(ACK)
        rx(C)

        val out = ByteArrayOutputStream(expected)
        while (true) {
            val block =
                try {
                    readBlock()
                } catch (e: M2Error) {
                    LogBus.e(R.string.log_block_retry, e)
                    rx(NAK)
                    continue
                }
            when (block) {
                is Block.End -> {
                    break
                }

                is Block.Data -> {
                    out.write(block.payload)
                    rx(ACK)
                }
            }
        }

        // Complete the transfer: NAK -> second EOT -> ACK -> idle.
        rx(NAK)
        runCatching { withTimeout(5_000) { txIn.receive() } }
        rx(ACK)
        runCatching { awaitCtl(3_000) }

        val data = out.toByteArray()
        if (data.size < expected) throw M2Error("file shorter than declared: ${data.size} of $expected")
        return data.copyOf(expected)
    }

    suspend fun listFiles(): List<DeviceFile> {
        val raw = String(fetchFile(FILELIST))
        return raw
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(" ")
                val name = parts.getOrNull(0) ?: return@mapNotNull null
                if (!name.endsWith(".fit")) return@mapNotNull null
                DeviceFile(name, parts.getOrNull(1)?.toIntOrNull() ?: 0)
            }.toList()
    }

    // ----------------------------------------------------------------- CRC

    private fun crc8Xor(data: ByteArray): Byte {
        var crc = 0
        for (b in data) crc = crc xor (b.toInt() and 0xFF)
        return (crc and 0xFF).toByte()
    }

    private fun crc16Arc(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc shr 1) xor 0xA001 else crc shr 1
            }
        }
        return crc and 0xFFFF
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }
