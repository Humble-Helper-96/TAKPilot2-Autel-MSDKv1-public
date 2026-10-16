package com.autel.sdksample.tak

import android.content.SharedPreferences

/**
 * A working in-memory [SharedPreferences] for the pure-logic tests.
 *
 * The unit-test source set runs with `returnDefaultValues`, so the real implementation is a stub
 * that returns nothing. A policy whose whole job is to READ and WRITE preferences cannot be
 * tested against that, hence this.
 */
class FakePrefs(private val map: MutableMap<String, Any?> = mutableMapOf()) : SharedPreferences {

    /** The common case: a store that holds only a mode. */
    constructor(mode: AppMode?) : this(
        if (mode == null) mutableMapOf() else mutableMapOf(AppMode.KEY_MODE to mode.name as Any?))

    /** A store holding a mode string that may not be a valid enum name. */
    constructor(raw: String) : this(mutableMapOf(AppMode.KEY_MODE to raw as Any?))

    fun put(key: String, value: Any?): FakePrefs { map[key] = value; return this }

    /** Marks both video slot migrations done. The mirror refuses to run before them, so
     *  any test that expects the plain video keys re-derived needs this. */
    fun slotsMigrated(): FakePrefs = put(TakConnectActivity.KEY_V_SLOTS_MIGRATED, true)
        .put(TakConnectActivity.KEY_V_PROTO_SPLIT_MIGRATED, true)

    override fun getAll(): MutableMap<String, *> = map
    // ⚠ STRICT, LIKE ANDROID — `as`, never `as?`. SharedPreferencesImpl casts the stored value
    // and throws ClassCastException when a key holds a different type than it is read as. A fake
    // that returned the default instead would hide exactly the failure that matters most once
    // the mode is settled at EVERY process start: a drifted key crashing every launch. A test
    // that reads the wrong type must fail here too.
    override fun getString(key: String?, defValue: String?) = (map[key] as String?) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
        @Suppress("UNCHECKED_CAST") ((map[key] as MutableSet<String>?) ?: defValues)
    override fun getInt(key: String?, defValue: Int) = (map[key] as Int?) ?: defValue
    override fun getLong(key: String?, defValue: Long) = (map[key] as Long?) ?: defValue
    override fun getFloat(key: String?, defValue: Float) = (map[key] as Float?) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = (map[key] as Boolean?) ?: defValue
    override fun contains(key: String?) = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Ed(map)
    override fun registerOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private class Ed(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        override fun putString(k: String?, v: String?) = apply { pending[k!!] = v }
        override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { pending[k!!] = v }
        override fun putInt(k: String?, v: Int) = apply { pending[k!!] = v }
        override fun putLong(k: String?, v: Long) = apply { pending[k!!] = v }
        override fun putFloat(k: String?, v: Float) = apply { pending[k!!] = v }
        override fun putBoolean(k: String?, v: Boolean) = apply { pending[k!!] = v }
        override fun remove(k: String?) = apply { removed += k!! }
        override fun clear() = apply { removed += map.keys }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() { removed.forEach { map.remove(it) }; map.putAll(pending) }
    }
}
