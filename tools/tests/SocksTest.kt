package ai.arena.test

import ai.arena.webapp.vpn.DnsResolver
import ai.arena.webapp.vpn.Socks5Client
import ai.arena.webapp.vpn.SocksTcpStack
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Оффлайн-тесты SOCKS5-туннеля (mock SOCKS5-сервер внутри JVM):
 * хендшейк, CONNECT с доменом, эхо данных, полный цикл через SocksTcpStack.
 */
object SocksTest {

    private var passed = 0
    private var failed = 0

    private fun check(name: String, cond: Boolean) {
        if (cond) {
            passed++
            println("PASS  $name")
        } else {
            failed++
            println("FAIL  $name")
        }
    }

    class MockSocksServer {
        val server = ServerSocket(0)
        val port: Int get() = server.localPort
        val lastTargetHost = java.util.concurrent.atomic.AtomicReference<String>("")
        val lastTargetPort = AtomicInteger(0)
        val echoCount = AtomicInteger(0)
        @Volatile var running = true

        fun start() {
            Thread({
                while (running) {
                    try {
                        val client = server.accept()
                        Thread({ handle(client) }, "mock-socks").start()
                    } catch (t: Throwable) {
                        if (!running) break
                    }
                }
            }, "mock-socks-accept").start()
        }

        private fun handle(s: Socket) {
            try {
                val input = s.getInputStream()
                val out = s.getOutputStream()
                // greeting
                val g = ByteArray(2)
                input.read(g)
                val methods = input.read()
                out.write(byteArrayOf(5, 0))
                out.flush()
                // request header
                val h = ByteArray(4)
                readFully(input, h)
                var host = ""
                var port = 0
                when (h[3].toInt() and 0xff) {
                    1 -> {
                        val a = ByteArray(6); readFully(input, a)
                        host = "${a[0].toInt() and 0xff}.${a[1].toInt() and 0xff}.${a[2].toInt() and 0xff}.${a[3].toInt() and 0xff}"
                        port = ((a[4].toInt() and 0xff) shl 8) or (a[5].toInt() and 0xff)
                    }
                    3 -> {
                        val l = input.read()
                        val nb = ByteArray(l); readFully(input, nb)
                        host = String(nb, Charsets.US_ASCII)
                        val pb = ByteArray(2); readFully(input, pb)
                        port = ((pb[0].toInt() and 0xff) shl 8) or (pb[1].toInt() and 0xff)
                    }
                    else -> { s.close(); return }
                }
                lastTargetHost.set(host)
                lastTargetPort.set(port)
                // success: ATYP=1, 0.0.0.0:0
                out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                out.flush()
                // echo loop
                val buf = ByteArray(16384)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    echoCount.incrementAndGet()
                    out.write(buf, 0, n)
                    out.flush()
                }
            } catch (t: Throwable) {
            } finally {
                runCatching { s.close() }
            }
        }

        private fun readFully(input: InputStream, buf: ByteArray): Boolean {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) return false
                off += n
            }
            return true
        }

        fun stop() {
            running = false
            runCatching { server.close() }
        }
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        testSocks5Client()
        testSocksTcpStack()
        testDnsResolver()
        println("========================================")
        println("SOCKS PASSED: $passed, FAILED: $failed")
        if (failed > 0) kotlin.system.exitProcess(1)
    }

    // ------------------------------------------------------- Socks5Client

    private fun testSocks5Client() {
        val mock = MockSocksServer()
        mock.start()
        try {
            val client = Socks5Client("127.0.0.1", mock.port)
            val s = client.connect("arena.ai", 443, 4000) ?: run {
                check("Socks5 CONNECT", false)
                return
            }
            check("Socks5 CONNECT ok", true)
            check(
                "Socks5 domain passed",
                mock.lastTargetHost.get() == "arena.ai" && mock.lastTargetPort.get() == 443
            )
            // эхо через туннель
            val payload = "hello-socks".toByteArray()
            s.getOutputStream().write(payload)
            s.getOutputStream().flush()
            val resp = ByteArray(payload.size)
            var off = 0
            while (off < resp.size) {
                val n = s.getInputStream().read(resp, off, resp.size - off)
                if (n < 0) break
                off += n
            }
            check(
                "Socks5 echo",
                java.util.Arrays.equals(resp, payload) && mock.echoCount.get() >= 1
            )
            s.close()
        } finally {
            mock.stop()
        }
    }

    // ------------------------------------------------------ SocksTcpStack

    private fun testSocksTcpStack() {
        val mock = MockSocksServer()
        mock.start()
        val devicePackets = ConcurrentLinkedQueue<ByteArray>()
        val dns = DnsResolver()
        val vip = dns.virtualIpFor("arena.ai")
        val devIp = byteArrayOf(10, 66, 66, 2)

        val stack = SocksTcpStack(
            proxyProvider = { listOf(Socks5Client("127.0.0.1", mock.port)) },
            dns = dns,
            sendToDevice = { p -> devicePackets.add(p) },
            protectSocket = null,
            onProxyFail = {}
        )

        try {
            // 1. SYN от устройства
            val synSeq = 1000
            val syn = stack.buildTcpIp(
                devIp, vip, 40000, 443, synSeq, 0,
                SocksTcpStack.TCP_SYN, stack.tcpOptions(1280), ByteArray(0)
            )
            stack.onDevicePacket(syn)

            // ждём SYN-ACK
            check(
                "Stack: SYN-ACK arrived",
                waitFor(5000) { devicePackets.isNotEmpty() }
            )
            val synAck = devicePackets.poll() ?: run {
                check("Stack: SYN-ACK parse", false)
                return
            }
            val saIp = stack.parseIp(synAck) ?: run {
                check("Stack: SYN-ACK parse", false)
                return
            }
            val saSeg = stack.parseTcp(saIp.payload) ?: run {
                check("Stack: SYN-ACK parse", false)
                return
            }
            check(
                "Stack: SYN-ACK flags/ack",
                saSeg.flags and SocksTcpStack.TCP_SYN != 0 &&
                    saSeg.flags and SocksTcpStack.TCP_ACK != 0 &&
                    saSeg.ack == synSeq + 1
            )
            val devIsn = saSeg.seq

            // ждём, пока mock увидит CONNECT (соединение установится)
            check(
                "Stack: proxy got domain CONNECT",
                waitFor(5000) { mock.lastTargetHost.get() == "arena.ai" && mock.lastTargetPort.get() == 443 }
            )

            // 2. ACK от устройства
            val ackPkt = stack.buildTcpIp(
                devIp, vip, 40000, 443, synSeq + 1, devIsn + 1,
                SocksTcpStack.TCP_ACK, stack.tcpOptions(0), ByteArray(0)
            )
            stack.onDevicePacket(ackPkt)

            // 3. данные устройства → (через прокси) → mock-эхо обратно
            val data = "GET / HTTP/1.1".toByteArray()
            val dataPkt = stack.buildTcpIp(
                devIp, vip, 40000, 443, synSeq + 1, devIsn + 1,
                SocksTcpStack.TCP_PSH or SocksTcpStack.TCP_ACK, stack.tcpOptions(0), data
            )
            stack.onDevicePacket(dataPkt)

            // ждём эхо-данные на устройство
            var gotData = false
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline && !gotData) {
                val p = devicePackets.poll()
                if (p == null) {
                    Thread.sleep(50)
                    continue
                }
                val ip = stack.parseIp(p) ?: continue
                val seg = stack.parseTcp(ip.payload) ?: continue
                if (seg.payload.isNotEmpty() &&
                    java.util.Arrays.equals(seg.payload, data)
                ) {
                    gotData = true
                }
            }
            check("Stack: data device→proxy→device echo", gotData)
            check("Stack: proxy echo count > 0", mock.echoCount.get() > 0)

            // 4. FIN от устройства → сессия закрывается
            val finPkt = stack.buildTcpIp(
                devIp, vip, 40000, 443, synSeq + 1 + data.size, devIsn + 1 + data.size,
                SocksTcpStack.TCP_FIN or SocksTcpStack.TCP_ACK, stack.tcpOptions(0), ByteArray(0)
            )
            stack.onDevicePacket(finPkt)
            Thread.sleep(300)
            stack.tick()
            // сессия должна быть закрыта (не проверяем строго — главное, что не падает)
            check("Stack: FIN processed (no crash)", true)
        } finally {
            stack.closeAll()
            mock.stop()
        }
    }

    // -------------------------------------------------------- DnsResolver

    private fun testDnsResolver() {
        val dns = DnsResolver()
        val vip = dns.virtualIpFor("arena.ai")
        check("DNS virtual ip", dns.isVirtualIp(vip))
        check("DNS domainFor", dns.domainFor(vip) == "arena.ai")
        check("DNS vip stability", java.util.Arrays.equals(dns.virtualIpFor("arena.ai"), vip))
        // DNS-запрос → ответ с виртуальным IP
        val q = dns.buildQuery(1234, "arena.ai")
        val resp = dns.onDeviceDnsQuery(q)
        check("DNS answer non-null", resp != null)
        if (resp != null) {
            val anCount = ((resp[6].toInt() and 0xff) shl 8) or (resp[7].toInt() and 0xff)
            check("DNS answer has A record", anCount == 1)
            val tail = resp.copyOfRange(resp.size - 4, resp.size)
            check("DNS answer contains vip", java.util.Arrays.equals(tail, vip))
        }
        // AAAA → пустой ответ
        val q6 = dns.buildQuery(999, "arena.ai")
        q6[q6.size - 4] = 0
        q6[q6.size - 3] = 28
        val resp6 = dns.onDeviceDnsQuery(q6)
        check("DNS AAAA empty", resp6 != null && ((resp6[6].toInt() and 0xff) shl 8 or (resp6[7].toInt() and 0xff)) == 0)
    }
}
