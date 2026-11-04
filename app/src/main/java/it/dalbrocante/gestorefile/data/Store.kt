package it.dalbrocante.gestorefile.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persistenza semplice in JSON: tag sui file e regole di smistamento. */
class Store(private val dir: File) {

    private val tagsFile = File(dir, "tags.json")
    private val rulesFile = File(dir, "rules.json")

    // path -> tag
    fun loadTags(): Map<String, Set<String>> {
        if (!tagsFile.exists()) return emptyMap()
        val o = JSONObject(tagsFile.readText())
        return o.keys().asSequence().associateWith { k ->
            val a = o.getJSONArray(k)
            (0 until a.length()).map { a.getString(it) }.toSet()
        }
    }

    private fun saveTags(m: Map<String, Set<String>>) {
        val o = JSONObject()
        m.filterValues { it.isNotEmpty() }.forEach { (k, v) -> o.put(k, JSONArray(v.toList())) }
        tagsFile.writeText(o.toString())
    }

    fun addTag(files: Collection<File>, tag: String) {
        val m = loadTags().toMutableMap()
        files.forEach { m[it.absolutePath] = (m[it.absolutePath] ?: emptySet()) + tag }
        saveTags(m)
    }

    fun removeTag(path: String, tag: String) {
        val m = loadTags().toMutableMap()
        m[path] = (m[path] ?: emptySet()) - tag
        saveTags(m)
    }

    fun allTags(): List<String> = loadTags().values.flatten().distinct().sorted()

    fun filesWithTag(tag: String): List<File> =
        loadTags().filterValues { tag in it }.keys.map { File(it) }.filter { it.exists() }

    fun loadRules(): List<AutoRule> {
        if (!rulesFile.exists()) return emptyList()
        val a = JSONArray(rulesFile.readText())
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            AutoRule(
                File(o.getString("watch")),
                o.getString("exts").split(",").map { e -> e.trim().lowercase() }.filter { e -> e.isNotEmpty() }.toSet(),
                File(o.getString("target")),
                o.optBoolean("datePrefix"),
            )
        }
    }

    fun saveRules(rules: List<AutoRule>) {
        val a = JSONArray()
        rules.forEach {
            a.put(
                JSONObject().put("watch", it.watch.absolutePath).put("exts", it.exts.joinToString(","))
                    .put("target", it.target.absolutePath).put("datePrefix", it.datePrefix),
            )
        }
        rulesFile.writeText(a.toString())
    }
}
