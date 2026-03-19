package sigil.api

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {

    @TempDir
    lateinit var tempDir: File

    private val originalOut = System.out
    private val originalErr = System.err
    private lateinit var outCapture: ByteArrayOutputStream
    private lateinit var errCapture: ByteArrayOutputStream

    @BeforeEach
    fun setUp() {
        outCapture = ByteArrayOutputStream()
        errCapture = ByteArrayOutputStream()
        System.setOut(PrintStream(outCapture))
        System.setErr(PrintStream(errCapture))
    }

    @AfterEach
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    private fun stdout(): String = outCapture.toString()
    private fun stderr(): String = errCapture.toString()

    private fun writeSigilFile(name: String, content: String): File {
        val file = File(tempDir, name)
        file.writeText(content)
        return file
    }

    @Test
    fun `help command shows usage`() {
        Cli.run(arrayOf("help"))
        val out = stdout()
        assertContains(out, "Sigil Compiler")
        assertContains(out, "Commands:")
        assertContains(out, "compile")
        assertContains(out, "run")
        assertContains(out, "verify")
        assertContains(out, "inspect")
    }

    @Test
    fun `no args shows help`() {
        Cli.run(arrayOf())
        val out = stdout()
        assertContains(out, "Sigil Compiler")
        assertContains(out, "Commands:")
    }

    @Test
    fun `compile command produces output`() {
        val file = writeSigilFile("add.sigil", "fn add(a: Int, b: Int) -> Int { a + b }")
        val outDir = File(tempDir, "out")

        Cli.run(arrayOf("compile", file.absolutePath, "--output-dir", outDir.absolutePath))

        val out = stdout()
        assertContains(out, "Compiled:")
        assertContains(out, "Hash:")
        assertContains(out, "Verification tier:")
        assertContains(out, "compiled successfully")

        // Check .class files were written
        val classFiles = outDir.listFiles()?.filter { it.extension == "class" } ?: emptyList()
        assertTrue(classFiles.isNotEmpty(), "Expected .class files in output directory")
    }

    @Test
    fun `run command executes function`() {
        val file = writeSigilFile("math.sigil", "fn add(a: Int, b: Int) -> Int { a + b }")

        Cli.run(arrayOf("run", file.absolutePath, "add", "3", "4"))

        val out = stdout().trim()
        assertEquals("7", out)
    }

    @Test
    fun `run command with single argument function`() {
        val file = writeSigilFile("double.sigil", """
            fn double(x: Int) -> Int { x + x }
        """.trimIndent())

        Cli.run(arrayOf("run", file.absolutePath, "double", "5"))

        val out = stdout().trim()
        assertEquals("10", out)
    }

    @Test
    fun `verify command reports tier`() {
        val file = writeSigilFile("math.sigil", "fn add(a: Int, b: Int) -> Int { a + b }")

        Cli.run(arrayOf("verify", file.absolutePath))

        val out = stdout()
        assertContains(out, "Verification Report:")
        assertContains(out, "Function: add")
        assertContains(out, "Verification tier:")
        assertContains(out, "verified")
    }

    @Test
    fun `verify command verbose mode`() {
        val file = writeSigilFile("math.sigil", "fn add(a: Int, b: Int) -> Int { a + b }")

        Cli.run(arrayOf("verify", file.absolutePath, "--verbose"))

        val out = stdout()
        assertContains(out, "Hash:")
        assertContains(out, "Bytecode size:")
    }

    @Test
    fun `inspect command shows function details`() {
        val file = writeSigilFile("math.sigil", "fn add(a: Int, b: Int) -> Int { a + b }")

        Cli.run(arrayOf("inspect", file.absolutePath))

        val out = stdout()
        assertContains(out, "AST Inspection:")
        assertContains(out, "Function: add")
        assertContains(out, "Params:")
        assertContains(out, "Returns: Int")
        assertContains(out, "Hash:")
    }

    @Test
    fun `inspect command shows contracts`() {
        val file = writeSigilFile("contract.sigil", """
            fn positive(x: Int) -> Int {
                requires x > 0
                x
            }
        """.trimIndent())

        Cli.run(arrayOf("inspect", file.absolutePath))

        val out = stdout()
        assertContains(out, "Function: positive")
        assertContains(out, "Contracts:")
        assertContains(out, "requires:")
    }

    @Test
    fun `compile file not found`() {
        val ex = assertThrows<CliException> {
            Cli.run(arrayOf("compile", "/nonexistent/file.sigil"))
        }
        assertContains(ex.message!!, "file not found")
    }

    @Test
    fun `run with wrong argument count`() {
        val file = writeSigilFile("math.sigil", "fn add(a: Int, b: Int) -> Int { a + b }")

        val ex = assertThrows<CliException> {
            Cli.run(arrayOf("run", file.absolutePath, "add", "3"))
        }
        assertContains(ex.message!!, "expects 2 argument(s), got 1")
    }

    @Test
    fun `run with unknown function`() {
        val file = writeSigilFile("math.sigil", "fn add(a: Int, b: Int) -> Int { a + b }")

        val ex = assertThrows<CliException> {
            Cli.run(arrayOf("run", file.absolutePath, "subtract"))
        }
        assertContains(ex.message!!, "function 'subtract' not found")
    }

    @Test
    fun `compile with multiple functions`() {
        val file = writeSigilFile("multi.sigil", """
            fn add(a: Int, b: Int) -> Int { a + b }
            fn sub(a: Int, b: Int) -> Int { a - b }
        """.trimIndent())
        val outDir = File(tempDir, "out")

        Cli.run(arrayOf("compile", file.absolutePath, "--output-dir", outDir.absolutePath))

        val out = stdout()
        assertContains(out, "2 function(s) compiled successfully")
    }

    @Test
    fun `run command with boolean result`() {
        val file = writeSigilFile("bool.sigil", "fn isPositive(x: Int) -> Bool { x > 0 }")

        Cli.run(arrayOf("run", file.absolutePath, "isPositive", "5"))

        val out = stdout().trim()
        assertEquals("true", out)
    }
}
