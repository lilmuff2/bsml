package lilmuff1.bsml.modpatch

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.DataFormatException
import org.json.JSONObject
import org.tukaani.xz.LZMAInputStream

class CsvTable(
    val columns: List<String>,
    val types: List<String>,
    val rows: MutableList<MutableList<Any?>>
) {
    val names: MutableMap<String, Int> = HashMap()
    val columnIndexes: Map<String, Int> = columns.withIndex().associate { it.value to it.index }

    init {
        require(columns.size == types.size) { "names and types must have the same length" }
        if (hasNames()) {
            rows.forEachIndexed { index, row ->
                val name = row.firstOrNull()?.toString().orEmpty()
                if (name.isNotEmpty()) names[name] = index
            }
        }
    }

    fun hasNames(): Boolean {
        return when (columns.firstOrNull()?.lowercase()) {
            "name", "tid", "tr", "map" -> true
            else -> false
        }
    }

    fun cast(column: Int, value: Any?): Any? {
        if (value == null || value == JSONObject.NULL) return null
        if (value is String && value.isEmpty()) return null
        return when (types[column].lowercase()) {
            "int", "intarray" -> value.toString().toInt()
            "boolean", "booleanarray" -> value.toString().toBoolean()
            else -> value.toString()
        }
    }

    fun insertDataRow(index: Int): IndexedCsvRow {
        val boundedIndex = index.coerceIn(0, rows.size)
        val row = MutableList<Any?>(columns.size) { null }
        names.keys.toList().forEach { key ->
            val current = names[key] ?: return@forEach
            if (current >= boundedIndex) names[key] = current + 1
        }
        rows.add(boundedIndex, row)
        return IndexedCsvRow(index = boundedIndex, row = row)
    }

    fun countRowsOfName(name: String): Int {
        val start = names[name] ?: return 0
        var index = start + 1
        while (index < rows.size && rows[index].firstOrNull() == null) {
            index++
        }
        return index - start
    }

    fun toCsvBytes(): ByteArray {
        return buildString {
            append(printRow(columns))
            append('\n')
            append(printRow(types))
            append('\n')
            rows.forEach { row ->
                append(printRow(row))
                append('\n')
            }
        }.toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        fun load(data: ByteArray): CsvTable {
            var bytes = data
            if (
                bytes.size >= SIGNATURE_PREFIX_LENGTH &&
                bytes[0] == 'S'.code.toByte() &&
                bytes[1] == 'i'.code.toByte() &&
                bytes[2] == 'g'.code.toByte() &&
                bytes[3] == ':'.code.toByte()
            ) {
                bytes = bytes.copyOfRange(SIGNATURE_PREFIX_LENGTH, bytes.size)
            }
            if (bytes.isNotEmpty() && bytes[0].toInt() in 90..95) {
                bytes = try {
                    if (bytes.size <= 9) {
                        throw DataFormatException("LZMA CSV is too short")
                    }
                    decodeLzmaCsv(bytes)
                } catch (error: Throwable) {
                    throw DataFormatException("Failed to decode LZMA CSV: ${error.message ?: "unknown"}")
                }
            }

            val records = parseRecords(bytes.toString(StandardCharsets.UTF_8))
                .filter { it.any(String::isNotEmpty) }
            if (records.size < 2) throw DataFormatException("headers have to be present")
            val columns = records[0]
            val types = records[1]
            val rows = mutableListOf<MutableList<Any?>>()
            val table = CsvTable(columns, types, rows)
            records.drop(2).forEach { values ->
                if (values.all { it.isEmpty() }) return@forEach
                val rowName = values.getOrNull(0).orEmpty()
                val row = MutableList<Any?>(columns.size) { index ->
                    table.cast(index, values.getOrNull(index).orEmpty())
                }
                if (rowName.isEmpty()) {
                    row[0] = null
                }
                if (table.hasNames() && rowName.isNotEmpty()) {
                    table.names[rowName] = rows.size
                }
                rows += row
            }
            return table
        }

        fun parseRecords(input: String): List<List<String>> {
            val records = ArrayList<List<String>>()
            val row = ArrayList<String>()
            val builder = StringBuilder()
            var quoted = false
            var index = 0

            fun finishCell() {
                row += builder.toString()
                builder.setLength(0)
            }

            fun finishRow() {
                finishCell()
                records += row.toList()
                row.clear()
            }

            while (index < input.length) {
                val char = input[index]
                when {
                    char == '"' -> {
                        if (quoted && index + 1 < input.length && input[index + 1] == '"') {
                            builder.append('"')
                            index++
                        } else {
                            quoted = !quoted
                        }
                    }
                    char == ',' && !quoted -> finishCell()
                    (char == '\n' || char == '\r') && !quoted -> {
                        finishRow()
                        if (char == '\r' && index + 1 < input.length && input[index + 1] == '\n') {
                            index++
                        }
                    }
                    else -> builder.append(char)
                }
                index++
            }
            if (builder.isNotEmpty() || row.isNotEmpty()) {
                finishRow()
            }
            return records
        }

        private fun decodeLzmaCsv(bytes: ByteArray): ByteArray {
            return daniillnull.tools.LZMA.decompress(bytes)
        }

        fun parseRow(input: String): List<String> {
            val words = ArrayList<String>()
            val builder = StringBuilder()
            var quoted = false
            var endingQuote = false
            input.forEach { char ->
                if (char == '"') {
                    when {
                        !quoted -> quoted = true
                        endingQuote -> {
                            endingQuote = false
                            builder.append('"')
                        }
                        else -> endingQuote = true
                    }
                    return@forEach
                }
                if (endingQuote) {
                    quoted = false
                    endingQuote = false
                }
                if (char == ',' && !quoted) {
                    words += builder.toString()
                    builder.setLength(0)
                } else {
                    builder.append(char)
                }
            }
            words += builder.toString()
            return words
        }

        fun printRow(words: List<Any?>): String {
            return buildString {
                words.forEachIndexed { index, value ->
                    if (index > 0) append(',')
                    when (value) {
                        null -> Unit
                        is Boolean, is Number -> append(value)
                        else -> {
                            val text = value.toString()
                            if (text.isNotEmpty()) {
                                append('"')
                                append(text.replace("\"", "\"\""))
                                append('"')
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val SIGNATURE_PREFIX_LENGTH = 68

data class IndexedCsvRow(
    val index: Int,
    val row: MutableList<Any?>
)
