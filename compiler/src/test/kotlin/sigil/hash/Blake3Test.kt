package sigil.hash

import kotlin.test.Test
import kotlin.test.assertEquals

class Blake3Test {
    @Test
    fun `empty input produces known hash`() {
        // Blake3 hash of empty input - known test vector
        val hash = Blake3.hashHex(ByteArray(0))
        assertEquals(
            "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262",
            hash
        )
    }

    @Test
    fun `single byte input`() {
        // Blake3 hash of single zero byte
        val hash = Blake3.hashHex(byteArrayOf(0))
        assertEquals(64, hash.length, "Hash should be 64 hex characters (256 bits)")
    }

    @Test
    fun `known input produces consistent hash`() {
        val input = "hello world".toByteArray(Charsets.UTF_8)
        val hash1 = Blake3.hashHex(input)
        val hash2 = Blake3.hashHex(input)
        assertEquals(hash1, hash2, "Same input must produce same hash")
    }

    @Test
    fun `different inputs produce different hashes`() {
        val hash1 = Blake3.hashString("hello")
        val hash2 = Blake3.hashString("world")
        assert(hash1 != hash2) { "Different inputs should produce different hashes" }
    }

    @Test
    fun `hash is 256 bits`() {
        val hash = Blake3.hash("test".toByteArray())
        assertEquals(32, hash.size, "Hash should be 32 bytes (256 bits)")
    }

    @Test
    fun `hello world known vector`() {
        // Known Blake3 test vector for "hello world"
        val hash = Blake3.hashString("hello world")
        assertEquals(
            "d74981efa70a0c880b8d8c1985d075dbcbf679b99a5f9914e5aaf96b831a9e24",
            hash
        )
    }

    @Test
    fun `large input over chunk size`() {
        // Test with input larger than one chunk (1024 bytes)
        val input = ByteArray(2048) { it.toByte() }
        val hash = Blake3.hashHex(input)
        assertEquals(64, hash.length)
        // Verify determinism
        assertEquals(hash, Blake3.hashHex(input))
    }

    @Test
    fun `sequential bytes are deterministic`() {
        val input = ByteArray(251) { it.toByte() }
        val hash1 = Blake3.hashHex(input)
        val hash2 = Blake3.hashHex(input)
        assertEquals(64, hash1.length)
        assertEquals(hash1, hash2, "Same sequential input must produce same hash")
    }
}
