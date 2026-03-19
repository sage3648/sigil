package sigil.hash

/**
 * Pure Kotlin implementation of Blake3 hash function.
 * Produces 256-bit (32-byte) digests.
 *
 * Based on the Blake3 specification: https://github.com/BLAKE3-team/BLAKE3-specs/blob/master/blake3.pdf
 */
object Blake3 {
    private const val OUT_LEN = 32
    private const val KEY_LEN = 32
    private const val BLOCK_LEN = 64
    private const val CHUNK_LEN = 1024

    // IV constants (same as Blake2s)
    private val IV = intArrayOf(
        0x6A09E667.toInt(), 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
        0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB.toInt(), 0x5BE0CD19
    )

    // Flags
    private const val CHUNK_START = 1
    private const val CHUNK_END = 2
    private const val PARENT = 4
    private const val ROOT = 8

    // Message schedule permutation
    private val MSG_PERMUTATION = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)

    private fun wrappingAdd(a: Int, b: Int): Int = a + b

    private fun rotateRight(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

    private fun g(state: IntArray, a: Int, b: Int, c: Int, d: Int, mx: Int, my: Int) {
        state[a] = wrappingAdd(wrappingAdd(state[a], state[b]), mx)
        state[d] = rotateRight(state[d] xor state[a], 16)
        state[c] = wrappingAdd(state[c], state[d])
        state[b] = rotateRight(state[b] xor state[c], 12)
        state[a] = wrappingAdd(wrappingAdd(state[a], state[b]), my)
        state[d] = rotateRight(state[d] xor state[a], 8)
        state[c] = wrappingAdd(state[c], state[d])
        state[b] = rotateRight(state[b] xor state[c], 7)
    }

    private fun round(state: IntArray, m: IntArray) {
        // Column step
        g(state, 0, 4, 8, 12, m[0], m[1])
        g(state, 1, 5, 9, 13, m[2], m[3])
        g(state, 2, 6, 10, 14, m[4], m[5])
        g(state, 3, 7, 11, 15, m[6], m[7])
        // Diagonal step
        g(state, 0, 5, 10, 15, m[8], m[9])
        g(state, 1, 6, 11, 12, m[10], m[11])
        g(state, 2, 7, 8, 13, m[12], m[13])
        g(state, 3, 4, 9, 14, m[14], m[15])
    }

    private fun permute(m: IntArray): IntArray {
        val permuted = IntArray(16)
        for (i in 0 until 16) {
            permuted[i] = m[MSG_PERMUTATION[i]]
        }
        return permuted
    }

    private fun compress(
        chainingValue: IntArray,
        blockWords: IntArray,
        counter: Long,
        blockLen: Int,
        flags: Int
    ): IntArray {
        val state = intArrayOf(
            chainingValue[0], chainingValue[1], chainingValue[2], chainingValue[3],
            chainingValue[4], chainingValue[5], chainingValue[6], chainingValue[7],
            IV[0], IV[1], IV[2], IV[3],
            counter.toInt(), (counter ushr 32).toInt(), blockLen, flags
        )
        var msg = blockWords.copyOf()
        round(state, msg) // round 1
        msg = permute(msg)
        round(state, msg) // round 2
        msg = permute(msg)
        round(state, msg) // round 3
        msg = permute(msg)
        round(state, msg) // round 4
        msg = permute(msg)
        round(state, msg) // round 5
        msg = permute(msg)
        round(state, msg) // round 6
        msg = permute(msg)
        round(state, msg) // round 7

        for (i in 0 until 8) {
            state[i] = state[i] xor state[i + 8]
            state[i + 8] = state[i + 8] xor chainingValue[i]
        }
        return state
    }

    private fun bytesToWords(bytes: ByteArray, offset: Int = 0, count: Int = 64): IntArray {
        val words = IntArray(count / 4)
        for (i in words.indices) {
            val base = offset + i * 4
            words[i] = if (base + 3 < bytes.size) {
                (bytes[base].toInt() and 0xFF) or
                        ((bytes[base + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[base + 2].toInt() and 0xFF) shl 16) or
                        ((bytes[base + 3].toInt() and 0xFF) shl 24)
            } else {
                var w = 0
                for (j in 0 until 4) {
                    if (base + j < bytes.size) {
                        w = w or ((bytes[base + j].toInt() and 0xFF) shl (j * 8))
                    }
                }
                w
            }
        }
        return words
    }

    private fun wordsToBytes(words: IntArray, count: Int = words.size * 4): ByteArray {
        val bytes = ByteArray(count)
        for (i in 0 until count) {
            bytes[i] = (words[i / 4] ushr ((i % 4) * 8)).toByte()
        }
        return bytes
    }

    private data class Output(
        val inputChainingValue: IntArray,
        val blockWords: IntArray,
        val counter: Long,
        val blockLen: Int,
        val flags: Int
    ) {
        fun chainingValue(): IntArray {
            return compress(inputChainingValue, blockWords, counter, blockLen, flags)
                .copyOfRange(0, 8)
        }

        fun rootOutputBytes(outLen: Int): ByteArray {
            val result = ByteArray(outLen)
            var outputBlockCounter = 0L
            var pos = 0
            while (pos < outLen) {
                val words = compress(inputChainingValue, blockWords, outputBlockCounter, blockLen, flags or ROOT)
                val bytes = wordsToBytes(words)
                val take = minOf(bytes.size, outLen - pos)
                System.arraycopy(bytes, 0, result, pos, take)
                pos += take
                outputBlockCounter++
            }
            return result
        }
    }

    private class ChunkState(
        private var chainingValue: IntArray,
        private val chunkCounter: Long,
        private var flags: Int
    ) {
        private var block = ByteArray(BLOCK_LEN)
        private var blockLen = 0
        private var blocksCompressed = 0

        fun len(): Int = BLOCK_LEN * blocksCompressed + blockLen

        fun update(input: ByteArray, offset: Int = 0, length: Int = input.size) {
            var pos = offset
            var remaining = length
            while (remaining > 0) {
                if (blockLen == BLOCK_LEN) {
                    val blockWords = bytesToWords(block)
                    var blockFlags = flags
                    if (blocksCompressed == 0) blockFlags = blockFlags or CHUNK_START
                    chainingValue = compress(chainingValue, blockWords, chunkCounter, BLOCK_LEN, blockFlags)
                        .copyOfRange(0, 8)
                    blocksCompressed++
                    block = ByteArray(BLOCK_LEN)
                    blockLen = 0
                }
                val take = minOf(BLOCK_LEN - blockLen, remaining)
                System.arraycopy(input, pos, block, blockLen, take)
                blockLen += take
                pos += take
                remaining -= take
            }
        }

        fun output(): Output {
            var blockFlags = flags or CHUNK_END
            if (blocksCompressed == 0) blockFlags = blockFlags or CHUNK_START
            val paddedBlock = block.copyOf(BLOCK_LEN)
            return Output(chainingValue, bytesToWords(paddedBlock), chunkCounter, blockLen, blockFlags)
        }
    }

    private fun parentOutput(leftChildCV: IntArray, rightChildCV: IntArray, flags: Int): Output {
        val blockWords = IntArray(16)
        System.arraycopy(leftChildCV, 0, blockWords, 0, 8)
        System.arraycopy(rightChildCV, 0, blockWords, 8, 8)
        return Output(IV, blockWords, 0, BLOCK_LEN, PARENT or flags)
    }

    /**
     * Hash the input bytes and return a 32-byte (256-bit) Blake3 digest.
     */
    fun hash(input: ByteArray): ByteArray {
        val flags = 0
        val cvStack = mutableListOf<IntArray>()
        var chunkCounter = 0L
        var pos = 0

        while (pos + CHUNK_LEN <= input.size) {
            val chunk = ChunkState(IV, chunkCounter, flags)
            chunk.update(input, pos, CHUNK_LEN)
            var cv = chunk.output().chainingValue()
            pos += CHUNK_LEN
            chunkCounter++

            var totalChunks = chunkCounter
            while (totalChunks and 1L == 0L) {
                val leftCV = cvStack.removeAt(cvStack.size - 1)
                cv = parentOutput(leftCV, cv, flags).chainingValue()
                totalChunks = totalChunks shr 1
            }
            cvStack.add(cv)
        }

        // Last chunk (or only chunk if input < CHUNK_LEN)
        val lastChunk = ChunkState(IV, chunkCounter, flags)
        if (pos < input.size) {
            lastChunk.update(input, pos, input.size - pos)
        }
        var output = lastChunk.output()

        while (cvStack.isNotEmpty()) {
            val leftCV = cvStack.removeAt(cvStack.size - 1)
            output = parentOutput(leftCV, output.chainingValue(), flags)
        }

        return output.rootOutputBytes(OUT_LEN)
    }

    /**
     * Hash and return hex string.
     */
    fun hashHex(input: ByteArray): String {
        return hash(input).joinToString("") { "%02x".format(it) }
    }

    /**
     * Hash a string (UTF-8 encoded) and return hex string.
     */
    fun hashString(input: String): String {
        return hashHex(input.toByteArray(Charsets.UTF_8))
    }
}
