package dev.komkov.m2sync

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothAdapter
import org.robolectric.shadows.ShadowBluetoothDevice
import org.robolectric.shadows.ShadowBluetoothGatt
import org.robolectric.shadows.ShadowBluetoothLeScanner
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.UUID

private const val ADDRESS = "AA:BB:CC:DD:EE:01"

private const val SOH: Byte = 0x01
private const val STX: Byte = 0x02
private const val ACK: Byte = 0x06
private const val NAK: Byte = 0x15
private const val EOT: Byte = 0x04
private const val REQ: Byte = 0x43
private const val FETCH: Byte = 0x05
private const val FETCH_OK: Byte = 0x06
private const val ERROR: Byte = 0x15

/** CRC16/ARC — тот же полином, что и в прошивке; считаем независимо от клиента. */
private fun crc16(data: ByteArray): Int {
    var crc = 0
    for (b in data) {
        crc = crc xor (b.toInt() and 0xFF)
        repeat(8) { crc = if (crc and 1 != 0) (crc shr 1) xor 0xA001 else crc shr 1 }
    }
    return crc and 0xFFFF
}

private fun xor8(data: ByteArray): Byte {
    var crc = 0
    for (b in data) crc = crc xor (b.toInt() and 0xFF)
    return crc.toByte()
}

/**
 * Модель велокомпьютера поверх теней Robolectric.
 *
 * Тень BluetoothGatt отдаёт записанные байты только через
 * `BluetoothGattCharacteristic.setValue`, поэтому шов ответчика сделан там.
 * Ответы возвращаются нотификациями через колбэк самого клиента — ровно так же,
 * как их приносит настоящий стек Bluetooth. Всё синхронно: команда клиента
 * успевает получить ответ до того, как он начнёт его ждать.
 */
private class FakeM2(
    private val ctx: Context,
    private val client: M2Client,
) {
    // --- что отвечает устройство ---

    /** M2 отвечает на STATUS одним байтом, XOSS G+ — тремя. */
    var statusReply: ByteArray = byteArrayOf(EOT)
    var idleReply: ByteArray = byteArrayOf(EOT)
    var diskReply: ByteArray? = byteArrayOf(0x0A) + "15752/16384".toByteArray() + byteArrayOf(0)
    var files: Map<String, ByteArray> = emptyMap()

    /** Размер, объявленный в нулевом блоке; по умолчанию — настоящий. */
    var declaredSize: Int? = null
    var headerText: String? = null
    var blockPayload = 128
    var notifyChunk = 244
    var corruptFirstBlock = false
    var oversizedHeader = false
    var headerIsEot = false
    var writableCtl = true
    var withCccd = true
    var withCtl = true
    var battery: ByteArray? = byteArrayOf(87)
    var firmware: ByteArray? = "V1.4.0 ".toByteArray()

    /** Сколько раз клиент попросил повторить блок. */
    var resends = 0
        private set

    private val cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private lateinit var gatt: BluetoothGatt

    private val callback: BluetoothGattCallback by lazy {
        val f = M2Client::class.java.getDeclaredField("callback")
        f.isAccessible = true
        f.get(client) as BluetoothGattCallback
    }

    // --- дерево GATT ---

    private inner class Writable(
        id: UUID,
        writable: Boolean,
    ) : BluetoothGattCharacteristic(
            id,
            if (writable) {
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY
            } else {
                BluetoothGattCharacteristic.PROPERTY_NOTIFY
            },
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        ) {
        override fun setValue(value: ByteArray?): Boolean {
            val ok = super.setValue(value)
            if (value != null) onWritten(uuid, value)
            return ok
        }
    }

    /**
     * Robolectric не подменяет `BluetoothGatt.readCharacteristic`: до тени вызов
     * не доходит, а настоящий метод спрашивает у характеристики только свойства
     * и молча возвращает false. За этот единственный вызов и цепляемся.
     */
    private inner class Readable(
        id: UUID,
        private val value: ByteArray,
    ) : BluetoothGattCharacteristic(
            id,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ) {
        override fun getProperties(): Int {
            callback.onCharacteristicRead(gatt, this, value, BluetoothGatt.GATT_SUCCESS)
            return super.getProperties()
        }
    }

    private val ctlChar by lazy { Writable(M2Client.CTL, writableCtl) }
    private val rxChar by lazy { Writable(M2Client.RX, true) }
    private val txChar by lazy { Writable(M2Client.TX, false) }

    private lateinit var uartService: BluetoothGattService

    private fun uart(): BluetoothGattService {
        val service = BluetoothGattService(M2Client.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(rxChar)
        service.addCharacteristic(txChar)
        if (withCtl) service.addCharacteristic(ctlChar)
        for (ch in listOf(txChar, ctlChar)) {
            if (withCccd) ch.addDescriptor(BluetoothGattDescriptor(cccd, BluetoothGattDescriptor.PERMISSION_WRITE))
        }
        uartService = service
        return service
    }

    /** Устройство перепрошилось на ходу: характеристика из сервиса исчезла. */
    fun dropCtl() {
        uartService.characteristics.remove(ctlChar)
    }

    private fun single(
        service: UUID,
        char: UUID,
        value: ByteArray,
    ): BluetoothGattService =
        BluetoothGattService(service, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            .apply { addCharacteristic(Readable(char, value)) }

    /** Вешает устройство на адрес: клиент получит из кэша адаптера тот же экземпляр. */
    fun attach(address: String = ADDRESS) {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device = adapter.getRemoteDevice(address)
        Shadow.extract<ShadowBluetoothDevice>(device).setGattConnectionInterceptor { g -> onNewGatt(g) }
    }

    private fun onNewGatt(g: BluetoothGatt) {
        gatt = g
        val shadow = Shadow.extract<ShadowBluetoothGatt>(g)
        shadow.addDiscoverableService(uart())
        battery?.let { shadow.addDiscoverableService(single(M2Client.BATTERY_SERVICE, M2Client.BATTERY_LEVEL, it)) }
        firmware?.let { shadow.addDiscoverableService(single(M2Client.DEVICE_INFO, M2Client.FIRMWARE_REV, it)) }
        // ShadowBluetoothGatt.connect() снаружи не вызвать — он protected,
        // поэтому о соединении сообщаем колбэку клиента напрямую.
        callback.onConnectionStateChange(g, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    }

    // --- ответчик ---

    private var pending: ByteArray? = null
    private var pendingName = ""
    private var offset = 0
    private var number = 1
    private var stage = 0

    private fun onWritten(
        id: UUID,
        value: ByteArray,
    ) {
        when (id) {
            M2Client.CTL -> onCommand(value)
            M2Client.RX -> onYmodem(value[0])
        }
    }

    private fun onCommand(cmd: ByteArray) {
        when {
            cmd.contentEquals(byteArrayOf(0xFF.toByte(), 0x00, 0xFF.toByte())) -> notifyOn(ctlChar, statusReply)
            cmd.contentEquals(byteArrayOf(0x04, 0x00, 0x04)) -> notifyOn(ctlChar, idleReply)
            cmd.contentEquals(byteArrayOf(0x09, 0x00, 0x09)) -> diskReply?.let { notifyOn(ctlChar, it) }
            cmd[0] == FETCH -> onFetch(String(cmd, 1, cmd.size - 2))
        }
    }

    private fun onFetch(name: String) {
        val body = files[name]
        if (body == null) {
            notifyOn(ctlChar, byteArrayOf(ERROR))
            return
        }
        pending = body
        pendingName = name
        offset = 0
        number = 1
        stage = 0
        resends = 0
        notifyOn(ctlChar, byteArrayOf(FETCH_OK) + name.toByteArray() + byteArrayOf(xor8(byteArrayOf(FETCH_OK) + name.toByteArray())))
    }

    private fun onYmodem(b: Byte) {
        when (stage) {
            0 -> {
                if (b == REQ) sendHeader()
            }

            1 -> {
                if (b == ACK) stage = 2
            }

            2 -> {
                if (b == REQ) sendData()
            }

            3 -> {
                onAnswer(b)
            }

            4 -> {
                if (b == NAK) {
                    notifyOn(txChar, byteArrayOf(EOT))
                    stage = 5
                }
            }

            5 -> {
                if (b == ACK) {
                    notifyOn(ctlChar, idleReply)
                    stage = 0
                }
            }
        }
    }

    private fun onAnswer(b: Byte) {
        when (b) {
            ACK -> {
                advance()
            }

            NAK -> {
                resends++
                sendData()
            }
        }
    }

    private fun sendHeader() {
        val body = pending ?: return
        stage = 1
        when {
            headerIsEot -> {
                notifyOn(txChar, byteArrayOf(EOT))
            }

            oversizedHeader -> {
                notifyOn(txChar, ByteArray(1100))
            }

            else -> {
                // Нулевой блок YMODEM: "имя размер", добитое до 128 байт.
                val payload = ByteArray(128) { ' '.code.toByte() }
                (headerText ?: "$pendingName ${declaredSize ?: body.size}").toByteArray().copyInto(payload)
                notifyOn(txChar, block(0, payload))
            }
        }
    }

    private fun sendData() {
        val body = pending ?: return
        // Хвост последнего блока YMODEM набивает 0x1A.
        val payload = ByteArray(blockPayload) { 0x1A }
        body.copyInto(payload, 0, offset, minOf(offset + blockPayload, body.size))
        notifyOn(txChar, block(number, payload))
        stage = 3
    }

    private fun advance() {
        val body = pending ?: return
        offset += blockPayload
        if (offset >= body.size) {
            notifyOn(txChar, byteArrayOf(EOT))
            stage = 4
        } else {
            number = (number + 1) and 0xFF
            sendData()
        }
    }

    private fun block(
        num: Int,
        payload: ByteArray,
    ): ByteArray {
        val head = byteArrayOf(if (payload.size > 128) STX else SOH, num.toByte(), num.inv().toByte())
        val crc = if (corruptFirstBlock && num == 1 && resends == 0) crc16(payload) xor 0xFFFF else crc16(payload)
        return head + payload + byteArrayOf((crc shr 8).toByte(), crc.toByte())
    }

    /** Нотификация ограничена MTU, поэтому длинный блок приходит по кускам. */
    private fun notifyOn(
        ch: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ) {
        if (bytes.isEmpty()) {
            callback.onCharacteristicChanged(gatt, ch, bytes)
            return
        }
        var from = 0
        while (from < bytes.size) {
            val to = minOf(from + notifyChunk, bytes.size)
            callback.onCharacteristicChanged(gatt, ch, bytes.copyOfRange(from, to))
            from = to
        }
    }
}

/**
 * Клиент M2 целиком: соединение, команды и приём файлов по YMODEM гоняются
 * против поддельного устройства на тенях Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class M2ClientTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var client: M2Client
    private lateinit var device: FakeM2

    @Before
    fun setUp() {
        // Журнал общий на всю JVM, поэтому чистим его и за собой (см. tearDown).
        LogBus.lines.value = emptyList()
        client = M2Client(ctx)
        device = FakeM2(ctx, client)
    }

    @After
    fun tearDown() {
        LogBus.lines.value = emptyList()
    }

    private fun connect(address: String = ADDRESS) {
        device.attach(address)
        runBlocking { client.connect(address) }
    }

    private fun freshDevice(
        address: String,
        configure: FakeM2.() -> Unit,
    ): M2Client {
        val other = M2Client(ctx)
        FakeM2(ctx, other).apply(configure).attach(address)
        return other
    }

    private fun message(body: suspend () -> Unit): String = assertThrows(M2Error::class.java) { runBlocking { body() } }.message.orEmpty()

    // ------------------------------------------------------------ соединение

    @Test
    fun `connect negotiates the mtu and turns notifications on`() {
        assertEquals(23, client.mtu)
        connect()
        assertEquals(247, client.mtu)
        // Последним при соединении пишется CCCD второй характеристики.
        val gatt = fieldOf(client, "gatt") as BluetoothGatt
        assertArrayEquals(
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            Shadow.extract<ShadowBluetoothGatt>(gatt).latestWrittenBytes,
        )
    }

    @Test
    fun `close disconnects the gatt and survives a second call`() {
        connect()
        val gatt = fieldOf(client, "gatt") as BluetoothGatt
        client.close()
        client.close()
        assertTrue(Shadow.extract<ShadowBluetoothGatt>(gatt).isClosed)
        assertNull(fieldOf(client, "gatt"))
    }

    @Test
    fun `a device without the uart characteristics cannot be connected`() {
        val headless = freshDevice("AA:BB:CC:DD:EE:02") { withCtl = false }
        assertTrue(message { headless.connect("AA:BB:CC:DD:EE:02") }.contains("not found"))

        val noCccd = freshDevice("AA:BB:CC:DD:EE:03") { withCccd = false }
        assertTrue(message { noCccd.connect("AA:BB:CC:DD:EE:03") }.contains("no CCCD"))
    }

    @Test
    fun `a connection lost before the handshake fails the wait`() {
        val gatt = ShadowBluetoothGatt.newInstance(adapter().getRemoteDevice("AA:BB:CC:DD:EE:09"))
        callbackOf(client).onConnectionStateChange(gatt, 133, BluetoothProfile.STATE_DISCONNECTED)
        val waiting = fieldOf(client, "onConnected") as CompletableDeferred<*>
        val error = runBlocking { runCatching { waiting.await() }.exceptionOrNull() }
        assertTrue(error is M2Error)
        assertTrue(error!!.message!!.contains("status 133"))
    }

    @Test
    fun `a failed service discovery is reported`() {
        val gatt = ShadowBluetoothGatt.newInstance(adapter().getRemoteDevice("AA:BB:CC:DD:EE:09"))
        callbackOf(client).onServicesDiscovered(gatt, 129)
        val waiting = fieldOf(client, "onServices") as CompletableDeferred<*>
        val error = runBlocking { runCatching { waiting.await() }.exceptionOrNull() }
        assertTrue(error is M2Error)
        assertTrue(error!!.message!!.contains("129"))
    }

    @Test
    fun `notifications from a foreign characteristic are dropped`() {
        connect()
        val gatt = fieldOf(client, "gatt") as BluetoothGatt
        val alien =
            BluetoothGattCharacteristic(
                UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"),
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        callbackOf(client).onCharacteristicChanged(gatt, alien, byteArrayOf(1, 2, 3))
        // Мусор в каналы не попал: свободного места запросить всё ещё можно.
        assertEquals("15752/16384", runBlocking { client.diskSpace() })
    }

    // -------------------------------------------------------------- команды

    @Test
    fun `disk space is read out of the reply`() {
        connect()
        assertEquals("15752/16384", runBlocking { client.diskSpace() })
    }

    @Test
    fun `an unexpected answer about disk space is ignored`() {
        connect()
        device.diskReply = byteArrayOf(ERROR, 0x01)
        assertNull(runBlocking { client.diskSpace() })
        device.diskReply = ByteArray(0)
        assertNull(runBlocking { client.diskSpace() })
    }

    @Test
    fun `commands need a connection`() {
        assertTrue(message { client.diskSpace() }.contains("not connected"))
    }

    @Test
    fun `a phone without a bluetooth adapter cannot connect`() {
        ShadowBluetoothAdapter.setIsBluetoothSupported(false)
        val orphan = M2Client(ctx)
        assertTrue(message { orphan.connect(ADDRESS) }.contains("no Bluetooth adapter"))
    }

    @Test
    fun `a command to a characteristic that is gone is an error`() {
        connect()
        device.dropCtl()
        assertTrue(message { client.diskSpace() }.contains("not found"))
    }

    @Test
    fun `a write the device rejects is an error`() {
        val readOnly = freshDevice("AA:BB:CC:DD:EE:04") { writableCtl = false }
        runBlocking { readOnly.connect("AA:BB:CC:DD:EE:04") }
        assertTrue(message { readOnly.diskSpace() }.contains("write rejected"))
    }

    @Test
    fun `a lost connection breaks the commands that follow`() {
        connect()
        val gatt = fieldOf(client, "gatt") as BluetoothGatt
        callbackOf(client).onConnectionStateChange(gatt, 19, BluetoothProfile.STATE_DISCONNECTED)
        assertTrue(message { client.fetchFile("ride.fit") }.contains("no answer to STATUS"))
    }

    // ------------------------------------------------------------- ожидание

    @Test
    fun `a three-byte idle reply is accepted too`() {
        connect()
        device.statusReply = byteArrayOf(0x04, 0x00, 0x04)
        device.files = mapOf("ride.fit" to ByteArray(50) { it.toByte() })
        assertEquals(50, runBlocking { client.fetchFile("ride.fit") }.size)
    }

    @Test
    fun `a device that goes idle only on the second try still works`() {
        connect()
        device.statusReply = byteArrayOf(ERROR)
        device.files = mapOf("ride.fit" to ByteArray(50) { it.toByte() })
        assertEquals(50, runBlocking { client.fetchFile("ride.fit") }.size)
    }

    @Test
    fun `a device that refuses to go idle is an error`() {
        connect()
        device.statusReply = byteArrayOf(ERROR)
        device.idleReply = byteArrayOf(ERROR)
        assertTrue(message { client.fetchFile("ride.fit") }.contains("device not idle"))
    }

    // ---------------------------------------------------------- приём файла

    private val ride = ByteArray(250) { (it * 7).toByte() }

    @Test
    fun `a file comes back byte for byte out of 128-byte blocks`() {
        connect()
        device.files = mapOf("ride.fit" to ride)
        // MTU 23: блок 133 байта приезжает несколькими нотификациями.
        device.notifyChunk = 20
        assertArrayEquals(ride, runBlocking { client.fetchFile("ride.fit") })
    }

    @Test
    fun `a file comes back out of 1024-byte blocks`() {
        connect()
        val big = ByteArray(2500) { (it % 251).toByte() }
        device.files = mapOf("ride.fit" to big)
        device.blockPayload = 1024
        assertArrayEquals(big, runBlocking { client.fetchFile("ride.fit") })
    }

    @Test
    fun `a refused file is an error`() {
        connect()
        assertTrue(message { client.fetchFile("nothing.fit") }.contains("refused file"))
    }

    @Test
    fun `a block with a broken checksum is asked for again`() {
        connect()
        device.files = mapOf("ride.fit" to ride)
        device.corruptFirstBlock = true
        assertArrayEquals(ride, runBlocking { client.fetchFile("ride.fit") })
        assertEquals(1, device.resends)
    }

    @Test
    fun `a file shorter than declared is an error`() {
        connect()
        device.files = mapOf("ride.fit" to ByteArray(100))
        device.declaredSize = 500
        assertTrue(message { client.fetchFile("ride.fit") }.contains("shorter than declared"))
    }

    @Test
    fun `a header without a size is an error`() {
        connect()
        device.files = mapOf("ride.fit" to ride)
        device.headerText = "ride.fit"
        assertTrue(message { client.fetchFile("ride.fit") }.contains("could not parse header"))
    }

    @Test
    fun `a transfer that ends instead of starting is an error`() {
        connect()
        device.files = mapOf("ride.fit" to ride)
        device.headerIsEot = true
        assertTrue(message { client.fetchFile("ride.fit") }.contains("no file header"))
    }

    @Test
    fun `a notification longer than any block is an error`() {
        connect()
        device.files = mapOf("ride.fit" to ride)
        device.oversizedHeader = true
        device.notifyChunk = 1100
        assertTrue(message { client.fetchFile("ride.fit") }.contains("longer than expected"))
    }

    @Test
    fun `the index yields only fit files with their sizes`() {
        connect()
        val index = "20260725102049.fit 228000\nSetting.json 21\n20260726083000.fit\n\n"
        device.files = mapOf(M2Client.FILELIST to index.toByteArray())
        val files = runBlocking { client.listFiles() }
        assertEquals(2, files.size)
        assertEquals(DeviceFile("20260725102049.fit", 228_000), files[0])
        assertEquals(DeviceFile("20260726083000.fit", 0), files[1])
    }

    // ------------------------------------------------------- чтение свойств

    @Test
    fun `battery level and firmware are read from the standard services`() {
        connect()
        assertEquals(87, runBlocking { client.readBattery() })
        // Пробел на конце строки прошивки клиент обязан срезать.
        assertEquals("V1.4.0", runBlocking { client.readFirmware() })
    }

    @Test
    fun `a device without those services yields nothing`() {
        assertNull(runBlocking { client.readBattery() })
        assertNull(runBlocking { client.readFirmware() })
    }

    // ----------------------------------------------------------------- CRC

    @Test
    fun `crc16 arc matches the reference check value`() {
        val crc = callPrivate<Int>(client, "crc16Arc", "123456789".toByteArray())
        assertEquals(0xBB3D, crc)
        assertEquals(0, callPrivate<Int>(client, "crc16Arc", ByteArray(0)))
    }

    /** Байты из docs/PROTOCOL.md: `05 66 69 6c 65 6c 69 73 74 2e 74 78 74 57`. */
    @Test
    fun `a fetch command is built the way the device expects`() {
        val cmd = callPrivateCommand(client, FETCH, M2Client.FILELIST)
        assertEquals("05 66 69 6c 65 6c 69 73 74 2e 74 78 74 57", cmd.toHex())
    }

    @Test
    fun `hex dumps keep the leading zeroes`() {
        assertEquals("00 0f ff", byteArrayOf(0, 0x0F, 0xFF.toByte()).toHex())
        assertEquals("", ByteArray(0).toHex())
    }

    // ---------------------------------------------------------------- поиск

    /**
     * The scanner shadow only emits prepared results for a non-empty filter list,
     * while the client scans without filters, so invoke the callback ourselves
     * after scanning starts.
     */
    private fun scanning(
        prefix: String,
        timeoutMs: Long? = null,
        deliver: (ScanCallback) -> Unit,
    ): List<FoundDevice> =
        runBlocking {
            val scanner = Shadow.extract<ShadowBluetoothLeScanner>(adapter().bluetoothLeScanner)
            val driver =
                launch(Dispatchers.Default) {
                    while (scanner.activeScans.isEmpty()) delay(5)
                    deliver(scanner.activeScans.first().scanCallback()!!)
                }
            val result = if (timeoutMs == null) client.scan(prefix) else client.scan(prefix, timeoutMs)
            driver.join()
            result
        }

    @Test
    fun `a scan reports every matching name once`() {
        val wanted = adapter().getRemoteDevice("AA:BB:CC:DD:EE:11")
        Shadow.extract<ShadowBluetoothDevice>(wanted).setName("M2_1234")
        val alien = adapter().getRemoteDevice("AA:BB:CC:DD:EE:12")
        Shadow.extract<ShadowBluetoothDevice>(alien).setName("Mi Scale")
        val nameless = adapter().getRemoteDevice("AA:BB:CC:DD:EE:13")

        val found =
            scanning("m2_") { cb ->
                cb.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, ScanResult(wanted, null, -55, 0))
                cb.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, ScanResult(wanted, null, -70, 1))
                cb.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, ScanResult(alien, null, -40, 2))
                // The name may exist only in the advertisement, not on the device.
                cb.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, ScanResult(nameless, localName("M2_9999"), -60, 3))
            }

        assertEquals(setOf("M2_1234", "M2_9999"), found.map { it.name }.toSet())
        assertEquals("AA:BB:CC:DD:EE:11", found.first { it.name == "M2_1234" }.address)
        assertEquals(-60, found.first { it.name == "M2_9999" }.rssi)
    }

    @Test
    fun `the default M2 prefix also finds CYCPLUS devices`() {
        val wanted = adapter().getRemoteDevice("AA:BB:CC:DD:EE:14")
        Shadow.extract<ShadowBluetoothDevice>(wanted).setName("CYCPLUS M3")

        val found =
            scanning("M2_") { cb ->
                cb.onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, ScanResult(wanted, null, -48, 0))
            }

        assertEquals("CYCPLUS M3", found.single().name)
    }

    @Test
    fun `a scan that finds nothing gives up on the timeout`() {
        assertTrue(runBlocking { client.scan("M2", 300) }.isEmpty())
    }

    @Test
    fun `a scan needs the radio to be on`() {
        val shadow = Shadow.extract<ShadowBluetoothAdapter>(adapter())
        shadow.setEnabled(false)
        shadow.setBleScanAlwaysAvailable(false)
        assertTrue(message { client.scan("M2", 300) }.contains("Bluetooth is off"))
    }

    @Test
    fun `a scan failure lands in the log`() {
        val found = scanning("M2", 400) { cb -> cb.onScanFailed(2) }
        assertTrue(found.isEmpty())
        assertTrue(
            LogBus.lines.value
                .last()
                .contains("2"),
        )
    }

    private fun adapter() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun localName(name: String): ScanRecord {
        val bytes = name.toByteArray()
        return ReflectionHelpers.callStaticMethod(
            ScanRecord::class.java,
            "parseFromBytes",
            ClassParameter.from(ByteArray::class.java, byteArrayOf((bytes.size + 1).toByte(), 0x09) + bytes),
        )
    }
}

// --- доступ к внутренностям клиента: швов для подмены GATT в нём нет ---

private fun fieldOf(
    client: M2Client,
    name: String,
): Any? {
    val f = M2Client::class.java.getDeclaredField(name)
    f.isAccessible = true
    return f.get(client)
}

private fun callbackOf(client: M2Client): BluetoothGattCallback = fieldOf(client, "callback") as BluetoothGattCallback

private fun <T> callPrivate(
    client: M2Client,
    name: String,
    arg: ByteArray,
): T {
    val m = M2Client::class.java.getDeclaredMethod(name, ByteArray::class.java)
    m.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return m.invoke(client, arg) as T
}

private fun callPrivateCommand(
    client: M2Client,
    code: Byte,
    name: String,
): ByteArray {
    val m = M2Client::class.java.getDeclaredMethod("command", Byte::class.java, String::class.java)
    m.isAccessible = true
    return m.invoke(client, code, name) as ByteArray
}
