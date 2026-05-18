package lilmuff1.bsml.modpatch

import org.json.JSONArray
import org.json.JSONObject

object CsvPatchApplier {
    fun apply(table: CsvTable, patch: JSONObject) {
        val keys = patch.keysList().toMutableList()
        keys.sortWith { left, right ->
            val leftIndex = patch.getJSONObject(left).optInt("@index", 0)
            val rightIndex = patch.getJSONObject(right).optInt("@index", 0)
            if (leftIndex != rightIndex) {
                leftIndex.compareTo(rightIndex)
            } else {
                left.compareTo(right)
            }
        }

        keys.forEach { name ->
            val data = patch.getJSONObject(name)
            val rowIndex = table.names[name] ?: run {
                val requestedIndex = if (data.has("@index")) data.getInt("@index") else table.rows.size
                val inserted = table.insertDataRow(requestedIndex)
                if (table.hasNames()) {
                    inserted.row[0] = name
                    table.names[name] = inserted.index
                }
                inserted.index
            }

            var rowCount = table.countRowsOfName(name)
            data.keysList().forEach { columnName ->
                if (columnName.startsWith("@")) return@forEach
                val array = data.optJSONArray(columnName) ?: return@forEach
                while (rowCount < array.length()) {
                    table.insertDataRow(rowIndex + rowCount)
                    rowCount++
                }
            }

            data.keysList().forEach { columnName ->
                if (columnName.startsWith("@")) return@forEach
                val column = table.columns.indexOf(columnName)
                if (column < 0) error("column not found: $columnName")
                val rawValue = data.get(columnName)
                val values = if (rawValue is JSONArray) rawValue.valuesList() else listOf(rawValue)
                for (rowOffset in 0 until rowCount) {
                    table.rows[rowIndex + rowOffset][column] = table.cast(column, values.getOrNull(rowOffset))
                }
            }
        }
    }

    fun resolveWildcards(patch: JSONObject, table: CsvTable): JSONObject {
        val result = JSONObject()
        val keys = patch.keysList().toMutableSet()
        keys.toList().forEach { key ->
            val names = wildcardNames(key, table) ?: return@forEach
            val rows = patch.getJSONObject(key)
            names.forEach { name ->
                val copy = JSONObject()
                merge(copy, rows)
                result.put(name, copy)
            }
            keys -= key
        }
        keys.forEach { key ->
            val rows = patch.getJSONObject(key)
            if (result.has(key)) {
                merge(result.getJSONObject(key), rows)
            } else {
                result.put(key, rows)
            }
        }
        return result
    }

    private fun wildcardNames(key: String, table: CsvTable): Collection<String>? {
        if (key == "*") return table.names.keys
        if (!key.startsWith("{") || !key.endsWith("}")) return null

        val inverted = key.startsWith("{!")
        val columnName = if (inverted) {
            key.substring(2, key.length - 1)
        } else {
            key.substring(1, key.length - 1)
        }
        val column = table.columns.indexOf(columnName)
        if (column < 0) error("column not found: $columnName")

        return table.names.mapNotNull { (name, index) ->
            val value = table.rows[index][column]
            val truthy = when (value) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                null -> false
                else -> true
            }
            if (truthy xor inverted) name else null
        }
    }

    private fun merge(base: JSONObject, updates: JSONObject) {
        updates.keysList().forEach { key ->
            base.put(key, updates.get(key))
        }
    }

    private fun JSONObject.keysList(): List<String> {
        val result = ArrayList<String>()
        val iterator = keys()
        while (iterator.hasNext()) {
            result += iterator.next()
        }
        return result
    }

    private fun JSONObject.arrayOrNull(key: String): JSONArray? {
        val value = get(key)
        return value as? JSONArray
    }

    private fun JSONArray.valuesList(): List<Any?> {
        return buildList {
            for (index in 0 until length()) {
                add(opt(index))
            }
        }
    }
}
