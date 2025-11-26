package edu.colorado.rrassist.cli

import java.io.PrintStream

class TeePrintStream(
    private val out1: PrintStream,
    private val out2: PrintStream
) : PrintStream(out1) {

    override fun println(x: Any?) {
        out1.println(x)
        out2.println(x)
    }

    override fun println(x: String?) {
        out1.println(x)
        out2.println(x)
    }

    override fun println() {
        out1.println()
        out2.println()
    }

    override fun print(x: Any?) {
        out1.print(x)
        out2.print(x)
    }

    override fun print(x: String?) {
        out1.print(x)
        out2.print(x)
    }
}
