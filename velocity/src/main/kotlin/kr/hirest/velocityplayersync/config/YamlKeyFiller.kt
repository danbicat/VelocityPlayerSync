package kr.hirest.velocityplayersync.config

import org.spongepowered.configurate.ConfigurationNode
import org.spongepowered.configurate.yaml.YamlConfigurationLoader
import java.io.BufferedReader
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 서버의 yml 파일에 없는 항목을 jar 안의 기본 yml 에서 찾아 파일 끝(또는 부모 항목의 끝)에 텍스트로 덧붙인다.
 * 관리자가 쓴 기존 내용과 주석은 한 글자도 바꾸지 않는다.
 * 파일이나 기본 yml 을 읽을 수 없거나, 결과를 검증하지 못하면 파일을 건드리지 않는다.
 */
internal object YamlKeyFiller {

    private val KEY = Regex("""^( *)("[^"]*"|'[^']*'|[^\s#\-?"'\[{&*!|>%@`][^:]*?)\s*:(\s.*)?$""")

    private class Entry(val key: String, val comment: Int, val line: Int, val end: Int, val block: Boolean)

    private class Insert(val pos: Int, val lines: List<String>)

    /**
     * @param file        서버의 yml 파일
     * @param defaultText jar 안에 들어 있는 기본 yml 의 내용
     * @param opaque      항목 이름을 관리자가 정하는 섹션의 경로(예: `servers`). 이 섹션이 이미 있으면 그 아래에는 아무것도 추가하지 않는다.
     * @return 추가한 항목 수. 파일을 바꾸지 않았으면 0
     */
    fun fill(file: Path, defaultText: String, opaque: Collection<String> = emptySet()): Int = try {
        fillOrThrow(file, defaultText, opaque.toSet())
    } catch (e: Exception) {
        0
    }

    private fun fillOrThrow(file: Path, defaultText: String, opaque: Set<String>): Int {
        val original = Files.readString(file, StandardCharsets.UTF_8)
        if (original.startsWith("﻿")) return 0
        val oldRoot = parse(original)
        parse(defaultText)

        val crlf = original.contains("\r\n")
        val user = original.split("\n").toMutableList()
        if (user.last().isEmpty()) user.removeAt(user.size - 1)
        val def = defaultText.split("\n").map(::stripCr).toMutableList()
        if (def.isNotEmpty() && def.last().isEmpty()) def.removeAt(def.size - 1)

        val inserts = mutableListOf<Insert>()
        val added = mutableListOf<List<String>>()
        merge(user, 0, user.size, def, 0, def.size, emptyList(), opaque, crlf, inserts, added)
        if (added.isEmpty()) return 0

        // 같은 위치에 여러 번 넣을 때는 나중에 만든 것(부모)을 먼저 넣어 자식 항목이 앞에 오게 한다.
        val ordered = inserts.sortedBy { it.pos }
        for (insert in ordered.asReversed()) user.addAll(insert.pos, insert.lines)
        val result = user.joinToString("\n") + "\n"

        val newRoot = parse(result)
        if (!retained(oldRoot, newRoot)) return 0
        if (added.any { newRoot.node(*it.toTypedArray()).virtual() }) return 0
        write(file, result)
        return added.size
    }

    private fun merge(
        user: List<String>, uFrom: Int, uTo: Int,
        def: List<String>, dFrom: Int, dTo: Int,
        path: List<String>, opaque: Set<String>, crlf: Boolean,
        inserts: MutableList<Insert>, added: MutableList<List<String>>,
    ) {
        val top = path.isEmpty()
        val ue = entries(user, uFrom, uTo)
        val de = entries(def, dFrom, dTo)
        if (de.isEmpty() || (ue.isEmpty() && !top)) return

        val uIndent = if (ue.isEmpty()) 0 else indent(user[ue[0].line])
        val delta = uIndent - indent(def[de[0].line])
        val byKey = HashMap<String, Entry>()
        for (e in ue) byKey.putIfAbsent(e.key, e)
        val eol = if (crlf) "\r" else ""

        val block = mutableListOf<String>()
        val added0 = added.size
        for (d in de) {
            val childPath = path + d.key
            val u = byKey[d.key]
            if (u == null) {
                if (top && block.isNotEmpty()) block.add(eol)
                for (i in d.comment until d.end) block.add(shift(def[i], delta) + eol)
                added.add(childPath)
                continue
            }
            if (childPath.joinToString(".") in opaque) continue
            if (d.block && u.block) {
                merge(user, u.line + 1, u.end, def, d.line + 1, d.end, childPath, opaque, crlf, inserts, added)
            }
        }
        if (added.size == added0 || block.isEmpty()) return

        var pos = uTo
        if (top) {
            if (ue.isNotEmpty() && uTo > 0 && !isBlank(user[uTo - 1])) block.add(0, eol)
        } else {
            while (pos > uFrom && (isBlank(user[pos - 1]) || isComment(user[pos - 1]))) pos--
        }
        inserts.add(Insert(pos, block))
    }

    /** `[from, to)` 범위 안에서 가장 얕은 들여쓰기에 있는 키들을, 위에 붙은 주석과 아래 내용 범위까지 묶어 돌려준다. */
    private fun entries(lines: List<String>, from: Int, to: Int): List<Entry> {
        var indent = -1
        for (i in from until to) {
            val l = stripCr(lines[i])
            if (isBlank(l) || isComment(l) || l.startsWith("---") || l.startsWith("...")) continue
            indent = indent(l)
            break
        }
        if (indent < 0) return emptyList()

        val keyLines = mutableListOf<Int>()
        val keys = mutableListOf<String>()
        val blocks = mutableListOf<Boolean>()
        for (i in from until to) {
            val l = stripCr(lines[i])
            if (indent(l) != indent || isBlank(l) || isComment(l)) continue
            val m = KEY.matchEntire(l) ?: continue
            if (m.groupValues[1].length != indent) continue
            var key = m.groupValues[2]
            if (key.length >= 2 && (key[0] == '"' || key[0] == '\'')) key = key.substring(1, key.length - 1)
            val rest = m.groupValues[3].trim()
            keyLines.add(i)
            keys.add(key)
            blocks.add(rest.isEmpty() || rest.startsWith("#"))
        }
        val comment = IntArray(keyLines.size)
        for (k in keyLines.indices) {
            var c = keyLines[k]
            val floor = if (k == 0) from else keyLines[k - 1] + 1
            while (c > floor) {
                val prev = stripCr(lines[c - 1])
                if (isComment(prev) && indent(prev) == indent) c-- else break
            }
            comment[k] = c
        }
        return keyLines.indices.map { k ->
            var end = if (k + 1 < keyLines.size) comment[k + 1] else to
            while (end > keyLines[k] + 1 && isBlank(lines[end - 1])) end--
            Entry(keys[k], comment[k], keyLines[k], end, blocks[k])
        }
    }

    /** 이전 내용이 그대로 남아 있는지(새로 추가된 항목은 허용) 확인한다. */
    private fun retained(before: ConfigurationNode, after: ConfigurationNode): Boolean {
        if (before.isMap) {
            if (!after.isMap) return false
            return before.childrenMap().all { (key, child) -> retained(child, after.node(key)) }
        }
        if (before.isList) {
            val a = before.childrenList()
            val b = after.childrenList()
            if (!after.isList || a.size != b.size) return false
            return a.indices.all { retained(a[it], b[it]) }
        }
        return before.raw() == after.raw()
    }

    private fun parse(text: String): ConfigurationNode =
        YamlConfigurationLoader.builder().source { BufferedReader(StringReader(text)) }.build().load()

    private fun write(file: Path, content: String) {
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, content, StandardCharsets.UTF_8)
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun shift(line: String, delta: Int): String = when {
        delta == 0 || isBlank(line) -> line
        delta > 0 -> " ".repeat(delta) + line
        else -> line.substring(minOf(-delta, indent(line)))
    }

    private fun stripCr(s: String) = s.removeSuffix("\r")

    private fun indent(s: String) = s.takeWhile { it == ' ' }.length

    private fun isBlank(s: String) = s.isBlank()

    private fun isComment(s: String) = s.trimStart().startsWith("#")
}
