package burp

import java.io.PrintWriter
import java.io.StringWriter
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.*
import kotlin.concurrent.thread

open class ThreadedRequestEngine(url: String, val threads: Int, maxQueueSize: Int, val readFreq: Int, val requestsPerConnection: Int, override val maxRetriesPerRequest: Int, override val callback: (Request, Boolean) -> Boolean, val timeout: Int): RequestEngine() {

    private val connectedLatch = CountDownLatch(threads)
    private val target = URL(url)
    private val threadPool = ArrayList<Thread>()

    init {

        if (maxQueueSize > 0) {
            requestQueue = LinkedBlockingQueue<Request>(maxQueueSize)
        }
        else {
            requestQueue = LinkedBlockingQueue<Request>()
        }

        completedLatch = CountDownLatch(threads)
        val retryQueue = LinkedBlockingQueue<Request>();
        val ipAddress = InetAddress.getByName(target.host)
        val port = if (target.port == -1) { target.defaultPort } else { target.port }

        val trustingSslSocketFactory = createSSLSocketFactory()

        Utils.err("Warming up...")
        for(j in 1..threads) {
            threadPool.add(
                thread {
                    sendRequests(target, trustingSslSocketFactory, ipAddress, port, retryQueue, completedLatch, readFreq, requestsPerConnection, connectedLatch)
                }
            )
        }

    }

    fun createSSLSocketFactory(): SSLSocketFactory {
        val trustingSslContext = SSLContext.getInstance("TLS")
        trustingSslContext.init(null, arrayOf<TrustManager>(TrustingTrustManager()), null)
        val trustingSslSocketFactory = trustingSslContext.socketFactory
        return trustingSslSocketFactory
    }

    override fun start(timeout: Int) {
        connectedLatch.await(timeout.toLong(), TimeUnit.SECONDS)
        attackState.set(1)
        start = System.nanoTime()
    }

    override fun buildRequest(template: String, payload: String?, learnBoring: Int?): Request {
        return Request(template.replace("Connection: close", "Connection: keep-alive"), payload, learnBoring ?: 0)
    }

    private fun sendRequests(url: URL, trustingSslSocketFactory: SSLSocketFactory, ipAddress: InetAddress?, port: Int, retryQueue: LinkedBlockingQueue<Request>, completedLatch: CountDownLatch, baseReadFreq: Int, baseRequestsPerConnection: Int, connectedLatch: CountDownLatch) {
        var readFreq = baseReadFreq
        val inflight = ArrayDeque<Request>()
        var requestsPerConnection = baseRequestsPerConnection
        var connected = false
        var reqWithResponse: Request? = null
        var answeredRequests = 0
        val badWords = HashSet<String>()
        var consecutiveFailedConnections = 0
        var reuseSSL = true

        while (!Utils.unloaded) {
            try {

                if(attackState.get() == 3) {
                    return
                }

                val socket: Socket?
                try {
                    socket = if (url.protocol.equals("https")) {
                        if (reuseSSL) {
                            trustingSslSocketFactory.createSocket(ipAddress, port)
                        } else {
                            createSSLSocketFactory().createSocket(ipAddress, port)
                        }
                    } else {
                        SocketFactory.getDefault().createSocket(ipAddress, port)
                    }
                }
                catch (ex: Exception) {
                    Utils.out("Thread failed to connect")
                    retries.getAndIncrement()
                    val stackTrace = StringWriter()
                    ex.printStackTrace(PrintWriter(stackTrace))
                    Utils.err(stackTrace.toString())
                    consecutiveFailedConnections += 1
                    val sleep = Math.pow(2.0, consecutiveFailedConnections.toDouble())
                    Thread.sleep(sleep.toLong() * 200)
                    continue
                }
                connections.incrementAndGet()
                //(socket as SSLSocket).session.peerCertificates
                socket!!.soTimeout = timeout * 1000
                socket.tcpNoDelay = true
                // todo tweak other TCP options for max performance

                if(!connected) {
                    connected = true
                    connectedLatch.countDown()
                    while(attackState.get() == 0) {
                        Thread.sleep(10)
                    }
                }

                consecutiveFailedConnections = 0

                var requestsSent = 0
                answeredRequests = 0
                while (requestsSent < requestsPerConnection) {

                    if(attackState.get() == 3) {
                        return
                    }

                    var readCount = 0
                    for (j in 1..readFreq) {
                        if (requestsSent >= requestsPerConnection) {
                            break
                        }

                        var req = retryQueue.poll()
                        while (req == null) {
                            req = requestQueue.poll(100, TimeUnit.MILLISECONDS);

                            if (req == null) {
                                if (readCount > 0) {
                                    break
                                }
                                if(attackState.get() == 2) {
                                    completedLatch.countDown()
                                    return
                                }
                            }
                        }

                        if (req == null) break

                        inflight.addLast(req)
                        socket.getOutputStream().write(req.getRawRequest())
                        readCount++
                        requestsSent++

                    }

                    val read = ByteArray(1024)
                    var buffer = ""

                    for (k in 1..readCount) {

                        var bodyStart = buffer.indexOf("\r\n\r\n")
                        while (bodyStart == -1) {
                            val len = socket.getInputStream().read(read)
                            if(len == -1) {
                                break
                            }
                            buffer += String(read.copyOfRange(0, len), Charsets.ISO_8859_1)
                            bodyStart = buffer.indexOf("\r\n\r\n")
                        }

                        val contentLength = getContentLength(buffer)
                        val shouldGzip = shouldGzip(buffer)

                        if (buffer.isEmpty()) {
                            throw ConnectException("No response")
                        } else if (bodyStart == -1) {
                            throw ConnectException("Unterminated response")
                        }

                        val headers = buffer.substring(0, bodyStart+4)
                        var body = ""

                        if (bodyStart+4 == buffer.length) {
                            // no need to read the body
                        }
                        else if (contentLength != -1) {
                            val responseLength = bodyStart + contentLength + 4

                            while (buffer.length < responseLength) {
                                val len = socket.getInputStream().read(read)
                                buffer += String(read.copyOfRange(0, len), Charsets.ISO_8859_1)
                            }

                            body = buffer.substring(bodyStart + 4, responseLength)
                            buffer = buffer.substring(responseLength)
                        }
                        else if (headers.toLowerCase().contains("transfer-encoding: chunked")) {
                            buffer = buffer.substring(bodyStart + 4)

                            while (true) {
                                var chunk = getNextChunkLength(buffer)
                                while (chunk.length == -1 || buffer.length < (chunk.length+2)) {
                                    val len = socket.getInputStream().read(read)
                                    if (len == -1) {
                                        throw RuntimeException("Chunked response finished unexpectedly")
                                    }
                                    buffer += String(read.copyOfRange(0, len), Charsets.ISO_8859_1)
                                    chunk = getNextChunkLength(buffer)
                                }

                                body += buffer.substring(chunk.skip, chunk.length)
                                buffer = buffer.substring(chunk.length + 2)

                                if (chunk.length == chunk.skip) {
                                    break
                                }
                            }
                        }
                        else {
                            while (true) {
                                val len = socket.getInputStream().read(read)
                                if (len == -1) {
                                    break
                                }
                                buffer = String(read.copyOfRange(0, len), Charsets.ISO_8859_1)
                                body += buffer
                            }
                        }


                        if (!headers.startsWith("HTTP")) {
                            throw Exception("no http")
                        }

                        var msg = headers
                        if(shouldGzip) {
                            msg += decompress(body.toByteArray(Charsets.ISO_8859_1))
                        }
                        else {
                            msg += body
                        }

                        reqWithResponse = inflight.removeFirst()
                        successfulRequests.getAndIncrement()
                        reqWithResponse.response = msg

                        answeredRequests += 1
                        val interesting = processResponse(reqWithResponse, (reqWithResponse.response as String).toByteArray(Charsets.ISO_8859_1))

                        invokeCallback(reqWithResponse, interesting)

                    }
                    badWords.clear()
                }
            } catch (ex: Exception) {

                if (ex is SSLHandshakeException && reuseSSL) {
                    reuseSSL = false
                }
                else {
                    // todo distinguish couldn't send vs couldn't read
                    val activeRequest = inflight.peek()
                    if (activeRequest != null) {
                        val activeWord = activeRequest.word ?: "(null)"
                        if (shouldRetry(activeRequest)) {
                            if (reqWithResponse != null) {
                                Utils.out("Autorecovering error after " + answeredRequests + " answered requests. After '" + reqWithResponse.word + "' during '" + activeWord + "'")
                            } else {
                                Utils.out("Autorecovering first-request error during '" + activeWord + "'")
                            }
                        } else {
                            ex.printStackTrace()
                            val badReq = inflight.pop()
                            badReq.response = "null"
                            invokeCallback(badReq, true)
                        }
                    } else {
                        Utils.out("Autorecovering error with empty queue: " + ex.message)
                        ex.printStackTrace()
                    }
                }

                // do callback here (allow user code change
                //readFreq = max(1, readFreq / 2)
                //requestsPerConnection = max(1, requestsPerConnection/2)
                //println("Lost ${inflight.size} requests. Changing requestsPerConnection to $requestsPerConnection and readFreq to $readFreq")
                retryQueue.addAll(inflight)
                inflight.clear()
            }
        }
    }

    fun getContentLength(buf: String): Int {
        val cstart = buf.indexOf("Content-Length: ")+16
        if (cstart == 15) {
            return -1
        }

        val cend = buf.indexOf("\r", cstart)
        try {
            return buf.substring(cstart, cend).toInt()
        } catch (e: NumberFormatException) {
            throw RuntimeException("Can't parse content length in "+buf)
        }
    }

    fun shouldGzip(buf: String): Boolean {
        return buf.indexOf("Content-Encoding: gzip") != -1
    }

    data class Result(val skip: Int, val length: Int)

    fun getNextChunkLength(buf: String): Result {
        if (buf.length == 0) {
            return Result(-1, -1)
        }

        val chunkLengthStart = 0
        val chunkLengthEnd = buf.indexOf("\r\n")
        if(chunkLengthEnd == -1) {
            throw RuntimeException("Coulnd't find the chunk length. Response size may be unspecified - try Burp request engine instead?")
        }

        try {
            val skip = 2+chunkLengthEnd-chunkLengthStart
            return Result(skip, Integer.parseInt(buf.substring(chunkLengthStart, chunkLengthEnd), 16)+skip)
        } catch (e: NumberFormatException) {
            throw RuntimeException("Can't parse followup chunk length '"+buf.substring(chunkLengthStart, chunkLengthEnd)+"' in "+buf)
        }
    }

    private class TrustingTrustManager : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate>? {
            return null
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
    }
}