package com.voiceime

import android.content.Context
import android.content.SharedPreferences

/** 参数值类型（决定 SharedPreferences 存取方式与输入解析） */
enum class ParamType { FLOAT, INT, LONG }

/**
 * 数值参数字段声明（key + 标签 + 类型 + 合法范围 + 取值访问器）。
 * 一张表即 readAll / saveAll / reset / 设置页字段列表的单一来源，
 * 消除 putFloat/putInt/putLong 式复制粘贴与"加字段要改三处"。
 */
class ParamField<V>(
    val key: String,
    val labelRes: Int,
    val type: ParamType,
    val min: Double,
    val max: Double,
    /** 从参数值对象取展示文本（readAll / 设置页回显用） */
    val get: (V) -> String,
) {
    /** 是否小数（设置页决定软键盘类型） */
    val decimal: Boolean get() = type == ParamType.FLOAT
}

/** 表驱动的参数读写（[ParamField] 表为唯一事实来源） */
object ParamStore {

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    /** 设置页展示用：全部字段的当前值（字符串形式），顺序与 [fields] 一致 */
    fun <V> readAll(fields: List<ParamField<V>>, values: V): Map<String, String> =
        LinkedHashMap<String, String>(fields.size).apply {
            fields.forEach { put(it.key, it.get(values)) }
        }

    /** 逐字段校验并保存（全部合法才 apply）。返回 null 表示成功，否则返回错误提示（已本地化）。 */
    fun <V> saveAll(
        context: Context,
        fields: List<ParamField<V>>,
        values: Map<String, String>,
    ): String? {
        val editor = prefs(context).edit()
        for (f in fields) {
            val raw = values[f.key]?.trim().orEmpty()
            val label = context.getString(f.labelRes)
            val parsed = when (f.type) {
                ParamType.FLOAT -> raw.toFloatOrNull()?.toDouble()
                ParamType.INT -> raw.toIntOrNull()?.toDouble()
                ParamType.LONG -> raw.toLongOrNull()?.toDouble()
            }
            if (parsed == null) {
                return context.getString(R.string.dbg_err_number, label)
            }
            if (parsed < f.min || parsed > f.max) {
                return context.getString(R.string.dbg_err_range, label, fmt(f.min), fmt(f.max))
            }
            when (f.type) {
                ParamType.FLOAT -> editor.putFloat(f.key, parsed.toFloat())
                ParamType.INT -> editor.putInt(f.key, parsed.toInt())
                ParamType.LONG -> editor.putLong(f.key, parsed.toLong())
            }
        }
        editor.apply()
        return null
    }

    /** 清除全部字段的持久化值（恢复默认） */
    fun reset(context: Context, fields: List<ParamField<*>>) {
        val editor = prefs(context).edit()
        fields.forEach { editor.remove(it.key) }
        editor.apply()
    }

    /** 范围提示文本：整数值不带小数点 */
    private fun fmt(v: Double): String =
        if (v == kotlin.math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
}
