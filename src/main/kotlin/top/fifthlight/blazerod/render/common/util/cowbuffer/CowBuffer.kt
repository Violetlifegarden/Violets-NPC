package top.fifthlight.blazerod.render.common.util.cowbuffer

import top.fifthlight.blazerod.render.api.refcount.RefCount
import top.fifthlight.blazerod.render.common.util.refcount.AbstractRefCount

/**
 * A container for copy-on-write buffers.
 *
 * It don't create extra buffer when there is only one reference, to avoid unnecessary allocations.
 * Make sure you maintain reference count correctly, otherwise bad things will happen.
 */
class CowBuffer<C : CowBuffer.Content<C>> constructor(val content: C) : AbstractRefCount() {
    init {
        content.increaseReferenceCount()
    }

    override val typeId: String
        get() = "cow_buffer"

    override fun onClosed() {
        content.decreaseReferenceCount()
    }

    interface Content<C : Content<C>> : RefCount {
        fun copy(): C
    }

    fun copy() = CowBuffer(content)

    fun edit(editor: C.() -> Unit): CowBuffer<C> {
        if (referenceCount <= 1) {
            editor(content)
            return this
        } else {
            val copy = content.copy()
            editor(copy)
            return CowBuffer(copy)
        }
    }
}

fun <C : CowBuffer.Content<C>> List<CowBuffer<C>>.copy() = List(size) { get(it).copy() }
