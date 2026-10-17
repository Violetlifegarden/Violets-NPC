package com.huashanlunjian.glbrender;

import com.huashanlunjian.glbrender.client.GlbrenderClient;
import com.huashanlunjian.glbrender.client.npc.GlbNpcClient;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import top.fifthlight.blazerod.render.runtime.BlazeRodRuntime;

@Mod(GlbRenderMod.MOD_ID)
public class GlbRenderMod {
    public static final String MOD_ID = "glbrender";

    public static final Logger LOGGER = LogUtils.getLogger();

    public GlbRenderMod(IEventBus modEventBus, ModContainer modContainer) {
        // Entity type, attributes and the spawn egg are needed on the dedicated server too.
        GlbRenderContent.INSTANCE.register(modEventBus);
        // The runtime classes touch client-only GL types, so a dedicated server must not load them.
        if (FMLEnvironment.dist.isClient()) {
            BlazeRodRuntime.INSTANCE.register();
            GlbrenderClient.INSTANCE.init(modEventBus);
            GlbNpcClient.INSTANCE.register(modEventBus);
        }
        LOGGER.info("[glbrender] mod constructed");
    }
}
