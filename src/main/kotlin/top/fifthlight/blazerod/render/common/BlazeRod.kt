package top.fifthlight.blazerod.render.common

import kotlinx.coroutines.CoroutineDispatcher

object BlazeRod {
    const val INSTANCE_SIZE = 128
    const val MAX_ENABLED_MORPH_TARGETS = 32

    lateinit var mainDispatcher: CoroutineDispatcher
    var debug = false
}
