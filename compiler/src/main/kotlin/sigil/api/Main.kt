package sigil.api

fun main(args: Array<String>) {
    try {
        Cli.run(args)
    } catch (e: CliException) {
        System.exit(e.exitCode)
    }
}
