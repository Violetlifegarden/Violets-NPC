package top.fifthlight.blazerod.render.api.refcount

class InvalidReferenceCountException(
    obj: Any,
    count: Int,
) : Exception("Bad reference count $count for object $obj")

interface RefCount {
    val closed: Boolean
    val referenceCount: Int
    fun increaseReferenceCount()
    fun decreaseReferenceCount()
}

inline fun <R> RefCount.use(crossinline block: () -> R): R {
    try {
        increaseReferenceCount()
        return block()
    } finally {
        decreaseReferenceCount()
    }
}

fun RefCount.checkInUse() = require(referenceCount > 0) { "Object $this is not in use." }
