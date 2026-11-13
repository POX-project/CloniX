package dev.cloneforge.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

class FakeSharedPreferences : SharedPreferences {
    private val data = ConcurrentHashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(data)

    override fun getString(key: String?, defValue: String?): String? {
        val v = data[key]
        return if (v is String) v else defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val v = data[key]
        return if (v is Set<*>) (v as Set<String>).toMutableSet() else defValues
    }

    override fun getInt(key: String?, defValue: Int): Int {
        val v = data[key]
        return if (v is Number) v.toInt() else defValue
    }

    override fun getLong(key: String?, defValue: Long): Long {
        val v = data[key]
        return if (v is Number) v.toLong() else defValue
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        val v = data[key]
        return if (v is Number) v.toFloat() else defValue
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        val v = data[key]
        return if (v is Boolean) v else defValue
    }

    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(this)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    class FakeEditor(private val parent: FakeSharedPreferences) : SharedPreferences.Editor {
        private val modifications = HashMap<String, Any?>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) modifications[key] = values?.toSet()
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) modifications[key] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            synchronized(parent) {
                if (clearAll) {
                    parent.data.clear()
                }
                for ((k, v) in modifications) {
                    if (v == null) {
                        parent.data.remove(k)
                    } else {
                        parent.data[k] = v
                    }
                }
            }
        }
    }
}

class FakeContext(
    val prefs: FakeSharedPreferences = FakeSharedPreferences()
) : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
    override fun getPackageName(): String = "dev.cloneforge"
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
}
