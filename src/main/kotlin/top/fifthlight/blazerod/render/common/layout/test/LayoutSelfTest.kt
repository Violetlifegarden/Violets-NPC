package top.fifthlight.blazerod.render.common.layout.test

import java.lang.reflect.InvocationTargetException

/**
 * Runs every [Test] method of the ported layout tests; `./gradlew layoutSelfTest` is the std140/std430
 * check now that the JUnit source set is gone.
 */
fun main() {
    var run = 0
    val failures = mutableListOf<String>()
    for (suite in listOf(Std140Test::class.java, Std430Test::class.java)) {
        val instance = suite.getDeclaredConstructor().newInstance()
        for (method in suite.declaredMethods.sortedBy { it.name }) {
            if (method.getAnnotation(Test::class.java) == null) continue
            run++
            try {
                method.invoke(instance)
                println("  PASS ${suite.simpleName}.${method.name}")
            } catch (failure: InvocationTargetException) {
                failures += "${suite.simpleName}.${method.name}: ${failure.cause?.message}"
                println("  FAIL ${suite.simpleName}.${method.name}: ${failure.cause}")
            }
        }
    }
    println("layout tests: $run run, ${failures.size} failed")
    if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n"))
}
