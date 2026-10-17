package top.fifthlight.blazerod.render.common.layout.test

import kotlin.reflect.KClass

/**
 * The std140/std430 tests moved to the main source set because resolving a JUnit test configuration
 * never completes in this environment. These are the only assertions the two ported files use, and
 * [main] of LayoutSelfTest drives them without a test framework.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Test

fun assertEquals(expected: Int, actual: Int, message: String? = null) {
    if (expected != actual) throw AssertionError(describe(message, expected, actual))
}

fun assertEquals(expected: Float, actual: Float, message: String? = null) {
    if (expected != actual) throw AssertionError(describe(message, expected, actual))
}

@Suppress("UNCHECKED_CAST")
fun <T : Throwable> assertFailsWith(expected: KClass<T>, block: () -> Unit): T {
    var thrown: Throwable? = null
    try {
        block()
    } catch (actual: Throwable) {
        thrown = actual
    }
    thrown ?: throw AssertionError("Expected ${expected.simpleName} to be thrown, but nothing was thrown")
    if (!expected.java.isInstance(thrown)) throw AssertionError(
        "Expected ${expected.simpleName} to be thrown, but was ${thrown.javaClass.name}: ${thrown.message}",
        thrown,
    )
    return expected.java.cast(thrown)
}

private fun describe(message: String?, expected: Any, actual: Any) =
    "${message?.plus(". ") ?: ""}expected <$expected> but was <$actual>"
