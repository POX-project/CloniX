package dev.cloneforge.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent record of produced clones: original package -> list of clones
 * ("orig package", "mutated package", display label serialized as JSON).
 *
 * Backed by SharedPreferences named `<applicationId>-prefs` so the state
 * survives process death and device restarts.
 */
class CloneHistoryStore(context: Context) {

    data class CloneEntry(
        val originalPackage: String,
        val clonePackage: String,
        val cloneLabel: String,
        val apkPath: String = ""
    )

    private val prefs = context.applicationContext.getSharedPreferences(
        context.packageName + "-prefs",
        Context.MODE_PRIVATE
    )

    /** Adds a clone to the history; re-cloning the same target overwrites the old row. */
    fun addClone(originalPackage: String, clonePackage: String, cloneLabel: String, apkPath: String = "") {
        if (originalPackage.isBlank() || clonePackage.isBlank()) return
        val existing = clones().filterNot {
            it.originalPackage == originalPackage && it.clonePackage == clonePackage
        }
        val list = mutableListOf(CloneEntry(originalPackage, clonePackage, cloneLabel, apkPath))
        list.addAll(existing)
        persist(list.take(MAX_ENTRIES))
    }

    /** Removes a clone from the history. */
    fun removeClone(clonePackage: String) {
        if (clonePackage.isBlank()) return
        val existing = clones().filterNot { it.clonePackage == clonePackage }
        persist(existing)
    }

    fun clones(): List<CloneEntry> = decode(prefs.getString(KEY_CLONES, null))

    fun clonesOf(originalPackage: String): List<CloneEntry> =
        clones().filter { it.originalPackage == originalPackage }

    private fun persist(entries: List<CloneEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put(KEY_ORIG, e.originalPackage)
                    .put(KEY_PKG, e.clonePackage)
                    .put(KEY_LABEL, e.cloneLabel)
                    .put(KEY_APK_PATH, e.apkPath)
            )
        }
        prefs.edit().putString(KEY_CLONES, arr.toString()).apply()
    }

    private fun decode(raw: String?): List<CloneEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<CloneEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val orig = obj.optString(KEY_ORIG)
                val pkg = obj.optString(KEY_PKG)
                if (orig.isBlank() || pkg.isBlank()) continue
                out += CloneEntry(orig, pkg, obj.optString(KEY_LABEL), obj.optString(KEY_APK_PATH, ""))
            }
            out
        } catch (_: Exception) {
            // Corrupted preference payload -> start over rather than crash the UI.
            emptyList()
        }
    }

    private companion object {
        const val KEY_CLONES = "clones"
        const val KEY_ORIG = "orig"
        const val KEY_PKG = "pkg"
        const val KEY_LABEL = "label"
        const val KEY_APK_PATH = "apk_path"
        const val MAX_ENTRIES = 1024
    }
}
