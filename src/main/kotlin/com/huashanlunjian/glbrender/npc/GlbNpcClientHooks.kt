package com.huashanlunjian.glbrender.npc

import net.minecraft.network.chat.Component

/**
 * The one client-only action the common [GlbNpc] needs for its talk interaction, exposed as an injectable hook so the
 * entity class never references `net.minecraft.client`. The client bootstrap sets [openDialog]; on a dedicated server
 * it stays null and a plain right-click simply does nothing, which is correct there.
 */
object GlbNpcClientHooks {
    /** Opens the dialog screen for [npcName] walking [lines]. Set only under the client dist. */
    var openDialog: ((npcName: Component, lines: List<String>) -> Unit)? = null
}
