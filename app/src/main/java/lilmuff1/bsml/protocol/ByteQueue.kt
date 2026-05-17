package lilmuff1.bsml.protocol

class ByteQueue {
    private var buffer = ByteArray(4096)
    private var start = 0
    private var end = 0

    val size: Int
        get() = end - start

    fun append(chunk: ByteArray) {
        ensureCapacity(chunk.size)
        chunk.copyInto(buffer, end)
        end += chunk.size
    }

    fun peek(count: Int): ByteArray = buffer.copyOfRange(start, start + count)

    fun skip(count: Int) {
        start += count
        compactIfNeeded()
    }

    fun read(count: Int): ByteArray {
        val out = buffer.copyOfRange(start, start + count)
        skip(count)
        return out
    }

    private fun ensureCapacity(incoming: Int) {
        val required = size + incoming
        if (required <= buffer.size) {
            if (end + incoming <= buffer.size) return
            compact()
            return
        }

        var newSize = buffer.size
        while (newSize < required) {
            newSize *= 2
        }

        val newBuffer = ByteArray(newSize)
        val currentSize = size
        buffer.copyInto(newBuffer, destinationOffset = 0, startIndex = start, endIndex = end)
        buffer = newBuffer
        start = 0
        end = currentSize
    }

    private fun compactIfNeeded() {
        if (start == end) {
            start = 0
            end = 0
            return
        }

        if (start >= buffer.size / 2) {
            compact()
        }
    }

    private fun compact() {
        val currentSize = size
        buffer.copyInto(buffer, destinationOffset = 0, startIndex = start, endIndex = end)
        start = 0
        end = currentSize
    }
}
