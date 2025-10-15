package edu.colorado.rrassist.utils

import com.intellij.openapi.diagnostic.Logger

object Log {
    private val logger: Logger = Logger.getInstance("edu.colorado.rrassist")

    fun info(message: String) = logger.info(message)
    fun warn(message: String) = logger.warn(message)
    fun debug(message: String, t: Throwable? = null) = logger.debug(message, t)
    fun error(message: String, t: Throwable? = null) = logger.error(message, t)
    fun trace(t: Throwable? = null) = logger.trace(t)
}
