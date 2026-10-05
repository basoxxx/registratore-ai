package it.registratoreai

import com.sun.net.httpserver.HttpServer
import it.registratoreai.transcription.ModelStore
import it.registratoreai.transcription.WhisperModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.random.Random

/** Download dei modelli divisi in pezzi (limite di 2 GiB delle release di GitHub). */
class ModelStoreTest {
    private val data = Random(1).nextBytes(2_500)
    private val partSize = 1_000L
    private var honorRange = true
    private lateinit var server: HttpServer

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val name = ex.requestURI.path.substringAfterLast('/')
            val idx = name.substringAfterLast(".part-a", "").firstOrNull()?.minus('a')
            val body = if (idx == null) data else data.copyOfRange(
                (idx * partSize).toInt(), minOf(data.size, ((idx + 1) * partSize).toInt()),
            )
            val from = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
            if (honorRange && from != null) {
                val slice = body.copyOfRange(from, body.size)
                ex.sendResponseHeaders(206, slice.size.toLong()); ex.responseBody.use { it.write(slice) }
            } else {
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun model(parts: Int) = WhisperModel(
        "m", "M", "", "model.bin", data.size.toLong(), parts,
        baseUrl = "http://127.0.0.1:${server.address.port}",
    )

    @Test
    fun joinsParts() = runBlocking {
        val dir = Files.createTempDirectory("models").toFile()
        val store = ModelStore(dir, listOf(model(3)), partSize)
        store.download(model(3))
        assertArrayEquals(data, File(dir, "model.bin").readBytes())
        assertTrue(store.isInstalled("m"))
    }

    @Test
    fun resumesInTheMiddleOfASecondPart() = runBlocking {
        val dir = Files.createTempDirectory("models").toFile()
        File(dir, "model.bin.part").writeBytes(data.copyOf(1_500)) // primo pezzo + metà del secondo
        ModelStore(dir, listOf(model(3)), partSize).download(model(3))
        assertArrayEquals(data, File(dir, "model.bin").readBytes())
    }

    @Test
    fun serverWithoutRangeRestartsOnlyThePart() = runBlocking {
        honorRange = false
        val dir = Files.createTempDirectory("models").toFile()
        File(dir, "model.bin.part").writeBytes(data.copyOf(1_500))
        ModelStore(dir, listOf(model(3)), partSize).download(model(3))
        assertArrayEquals(data, File(dir, "model.bin").readBytes())
    }

    @Test
    fun singleFileStillWorks() = runBlocking {
        val dir = Files.createTempDirectory("models").toFile()
        ModelStore(dir, listOf(model(1)), partSize).download(model(1))
        assertArrayEquals(data, File(dir, "model.bin").readBytes())
    }
}
