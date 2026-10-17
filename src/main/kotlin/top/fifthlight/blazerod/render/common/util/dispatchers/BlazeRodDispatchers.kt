package top.fifthlight.blazerod.render.common.util.dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import top.fifthlight.blazerod.render.common.BlazeRod

object BlazeRodDispatchers {
    val Main: CoroutineDispatcher
        get() = BlazeRod.mainDispatcher
}

val Dispatchers.BlazeRod
    get() = BlazeRodDispatchers
