package it.dalbrocante.gestorefile.shared

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Protocollo telefono <-> orologio (Wearable Data Layer). */
object Proto {
    const val CAP_WATCH = "gestorefile_watch"
    const val CAP_PHONE = "gestorefile_phone"
    const val P_LIST = "/fm/list"
    const val P_OP = "/fm/op"
    const val P_PULL = "/fm/pull"
    const val P_INFO = "/fm/info"
    const val CH_FILE = "/fm/file"
}

data class Entry(val name: String, val path: String, val isDir: Boolean, val size: Long, val modified: Long)

data class ListResult(val path: String, val parent: String?, val entries: List<Entry>, val free: Long, val error: String?)

fun listToJson(path: String, parent: String?, entries: List<Entry>, free: Long): ByteArray {
    val arr = JSONArray()
    entries.forEach { arr.put(JSONObject().put("n", it.name).put("p", it.path).put("d", it.isDir).put("s", it.size).put("m", it.modified)) }
    return JSONObject().put("path", path).put("parent", parent ?: JSONObject.NULL).put("free", free).put("e", arr).toString().toByteArray()
}

fun errorJson(msg: String): ByteArray = JSONObject().put("error", msg).toString().toByteArray()

fun parseList(bytes: ByteArray): ListResult {
    val o = JSONObject(String(bytes))
    if (o.has("error")) return ListResult("", null, emptyList(), 0, o.getString("error"))
    val arr = o.getJSONArray("e")
    val list = (0 until arr.length()).map {
        val e = arr.getJSONObject(it)
        Entry(e.getString("n"), e.getString("p"), e.getBoolean("d"), e.getLong("s"), e.getLong("m"))
    }
    return ListResult(o.getString("path"), if (o.isNull("parent")) null else o.getString("parent"), list, o.optLong("free"), null)
}

fun opJson(op: String, paths: List<String> = emptyList(), dst: String = "", name: String = ""): ByteArray =
    JSONObject().put("op", op).put("paths", JSONArray(paths)).put("dst", dst).put("name", name).toString().toByteArray()

fun pullJson(paths: List<String>, dstDir: String): ByteArray =
    JSONObject().put("paths", JSONArray(paths)).put("dst", dstDir).toString().toByteArray()

fun okJson(n: Int = 0): ByteArray = JSONObject().put("ok", true).put("n", n).toString().toByteArray()

/** Risposta generica: null = ok, altrimenti messaggio di errore. */
fun parseResult(bytes: ByteArray): String? {
    val o = JSONObject(String(bytes))
    return if (o.optBoolean("ok")) null else o.optString("error", "Errore")
}

data class Header(val dir: String, val rel: String, val size: Long)

fun writeHeader(out: OutputStream, dir: String, rel: String, size: Long) {
    val b = JSONObject().put("dir", dir).put("rel", rel).put("size", size).toString().toByteArray()
    val d = DataOutputStream(out)
    d.writeInt(b.size); d.write(b); d.flush()
}

fun readHeader(input: InputStream): Header {
    val d = DataInputStream(input)
    val n = d.readInt()
    require(n in 1..65536) { "Header non valido" }
    val b = ByteArray(n); d.readFully(b)
    val o = JSONObject(String(b))
    return Header(o.getString("dir"), o.getString("rel"), o.getLong("size"))
}

/** Risolve base/rel garantendo che il risultato resti dentro [root]. */
fun safeTarget(base: File, rel: String, root: File): File? {
    if (rel.isBlank()) return null
    val t = File(base, rel)
    val canon = runCatching { t.canonicalFile }.getOrNull() ?: return null
    val r = runCatching { root.canonicalFile }.getOrNull() ?: return null
    return if (canon.path == r.path || canon.path.startsWith(r.path + File.separator)) canon else null
}

fun uniqueFile(f: File): File {
    if (!f.exists()) return f
    val base = f.nameWithoutExtension; val ext = f.extension.let { if (it.isEmpty()) "" else ".$it" }
    var i = 1
    while (true) {
        val c = File(f.parentFile, "$base ($i)$ext")
        if (!c.exists()) return c
        i++
    }
}

fun listReqJson(path: String, hidden: Boolean): ByteArray =
    JSONObject().put("path", path).put("hidden", hidden).toString().toByteArray()

data class WatchInfo(val root: String, val recv: String, val free: Long, val build: Int)

fun infoJson(root: String, recv: String, free: Long, build: Int): ByteArray =
    JSONObject().put("root", root).put("recv", recv).put("free", free).put("build", build).toString().toByteArray()

fun parseInfo(b: ByteArray): WatchInfo {
    val o = JSONObject(String(b))
    return WatchInfo(o.getString("root"), o.getString("recv"), o.optLong("free"), o.optInt("build"))
}
