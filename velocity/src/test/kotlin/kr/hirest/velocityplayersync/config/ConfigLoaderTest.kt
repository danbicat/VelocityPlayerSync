package kr.hirest.velocityplayersync.config

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigLoaderTest {

    private val logger = LoggerFactory.getLogger("config-test")

    @Test
    fun `default config is copied and parsed`() {
        val dir = Files.createTempDirectory("vps-config")
        val config = ConfigLoader.load(dir, logger)

        assertTrue(Files.exists(dir.resolve("config.yml")))
        assertEquals("proxy-1", config.proxyId)
        assertEquals("redis://localhost:6379/0", config.redis.url)
        assertEquals("vps", config.redis.keyPrefix)
        assertEquals(Duration.ofSeconds(5), config.sync.heartbeatInterval)
        assertEquals(Duration.ofSeconds(15), config.sync.proxyTimeout)
        assertEquals(MaxPlayersMode.KEEP, config.ping.maxPlayersMode)
        assertEquals(DuplicateLoginAction.KICK_OLD, config.duplicateLogin.action)
        assertTrue(config.messages.containsKey("kicked-logged-in-elsewhere"))
        assertTrue(config.messages.containsKey("prefix"))
    }

    @Test
    fun `missing keys are appended to the file and existing text is kept`() {
        val dir = Files.createTempDirectory("vps-config")
        val file = dir.resolve("config.yml")
        val content = "# 내 주석\nproxy-id: \"proxy-b\"\nping:\n  max-players:\n    mode: fixed\n"
        file.writeText(content)

        val config = ConfigLoader.load(dir, logger)
        assertEquals("proxy-b", config.proxyId)
        assertEquals(MaxPlayersMode.FIXED, config.ping.maxPlayersMode)
        assertEquals(500, config.ping.fixedMaxPlayers)
        assertEquals(Duration.ofSeconds(10), config.sync.fullResyncInterval)
        val result = file.readText()
        assertTrue(result.startsWith(content), "사용자 파일(주석 포함)은 그대로 둬야 합니다")
        assertTrue(result.contains("# 같은 계정이 다른 프록시에 이미 접속해 있을 때\nduplicate-login:\n  enabled: true\n"))

        // 한 번 채운 뒤에는 다시 읽어도 파일이 바뀌지 않는다.
        ConfigLoader.load(dir, logger)
        assertEquals(result, file.readText())
    }

    @Test
    fun `missing top-level key is appended with its comment`() {
        val dir = Files.createTempDirectory("vps-config")
        val file = dir.resolve("config.yml")
        val defaults = ConfigLoader::class.java.getResource("/config.yml")!!.readText()
        val withoutDup = defaults.replace(
            Regex("# 같은 계정이 다른 프록시에 이미 접속해 있을 때\nduplicate-login:\n(  .*\n)+\n"), "",
        )
        assertTrue(withoutDup.length < defaults.length)
        file.writeText(withoutDup)

        assertEquals(1, YamlKeyFiller.fill(file, defaults))
        val result = file.readText()
        assertTrue(result.startsWith(withoutDup))
        assertEquals(
            "\n# 같은 계정이 다른 프록시에 이미 접속해 있을 때\nduplicate-login:\n  enabled: true\n" +
                "  # KICK_OLD : 기존 접속을 끊고 새 접속을 받습니다. (바닐라와 같은 동작)\n" +
                "  # DENY_NEW : 새 접속을 거부합니다.\n  action: KICK_OLD\n",
            result.removePrefix(withoutDup),
        )
    }

    @Test
    fun `missing nested key is inserted at the end of its parent`() {
        val dir = Files.createTempDirectory("vps-config")
        val file = dir.resolve("config.yml")
        val defaults = "a:\n  # 설명\n  x: 1\n  y: 2\nb: 1\n"
        val mine = "a:\n  x: 5\n# 메모\nb: 9\n"
        file.writeText(mine)
        assertEquals(1, YamlKeyFiller.fill(file, defaults))
        assertEquals("a:\n  x: 5\ny: 2\n# 메모\nb: 9\n".replace("\ny: 2", "\n  y: 2"), file.readText())
    }

    @Test
    fun `opaque sections are not refilled once they exist`() {
        val dir = Files.createTempDirectory("vps-config")
        val file = dir.resolve("config.yml")
        val defaults = "servers:\n  a: 1\n  b: 2\nname: x\n"
        file.writeText("servers:\n  a: 1\n")
        assertEquals(1, YamlKeyFiller.fill(file, defaults, setOf("servers")))
        assertEquals("servers:\n  a: 1\n\nname: x\n", file.readText())

        file.writeText("name: x\n")
        assertEquals(1, YamlKeyFiller.fill(file, defaults, setOf("servers")))
        assertEquals("name: x\n\nservers:\n  a: 1\n  b: 2\n", file.readText())
    }

    @Test
    fun `complete file is left untouched byte for byte`() {
        val dir = Files.createTempDirectory("vps-config")
        val file = dir.resolve("config.yml")
        val defaults = ConfigLoader::class.java.getResource("/config.yml")!!.readText()
        val mine = defaults.replace("\n", "\r\n").trimEnd() // CRLF, 끝 줄바꿈 없음
        file.writeText(mine)
        val before = Files.getLastModifiedTime(file)

        assertEquals(0, YamlKeyFiller.fill(file, defaults))
        assertEquals(mine, file.readText())
        assertEquals(before, Files.getLastModifiedTime(file))
    }

    @Test
    fun `unparsable file is left untouched`() {
        val dir = Files.createTempDirectory("vps-config")
        val file = dir.resolve("config.yml")
        val broken = "proxy-id: [unclosed\nredis: {a: \n  - x\n"
        file.writeText(broken)
        val defaults = ConfigLoader::class.java.getResource("/config.yml")!!.readText()

        assertEquals(0, YamlKeyFiller.fill(file, defaults))
        assertEquals(broken, file.readText())
        assertFailsWith<ConfigException> { ConfigLoader.load(dir, logger) }
        assertEquals(broken, file.readText())
    }

    @Test
    fun `invalid values are rejected or corrected`() {
        val dir = Files.createTempDirectory("vps-config")
        val file = dir.resolve("config.yml")

        file.writeText("proxy-id: \"has space\"\n")
        assertFailsWith<ConfigException> { ConfigLoader.load(dir, logger) }

        file.writeText("proxy-id: \"ok\"\nredis:\n  url: \"localhost:6379\"\n")
        assertFailsWith<ConfigException> { ConfigLoader.load(dir, logger) }

        file.writeText(
            "proxy-id: \"ok\"\nsync:\n  heartbeat-interval-seconds: 5\n  proxy-timeout-seconds: 3\n" +
                "duplicate-login:\n  action: SOMETHING\n",
        )
        val config = ConfigLoader.load(dir, logger)
        assertEquals(Duration.ofSeconds(10), config.sync.proxyTimeout, "하트비트의 2배로 올려야 합니다")
        assertEquals(DuplicateLoginAction.KICK_OLD, config.duplicateLogin.action)
    }
}
