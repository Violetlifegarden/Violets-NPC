package com.huashanlunjian.glbrender.mixin;

import com.huashanlunjian.glbrender.GlbRenderMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.fifthlight.blazerod.gl.Gl;

import java.io.BufferedReader;

/**
 * Runtime smoke test: 1.21.1's own tooling proves nothing until the mod is loaded and the mixin channel
 * works inside the game, so this runs once on the render thread and checks the two things the whole port
 * depends on (a GL context we may drive directly, and the shader assets shipped inside the mod jar).
 */
@Mixin(Minecraft.class)
public class MinecraftUpdateTitleMixin {
    private static final int SMOKE_TEST_TIMEOUT_FRAMES = 600;

    private int glbrender$attempts = 0;
    private boolean glbrender$done = false;

    @Inject(method = "updateTitle", at = @At("TAIL"))
    private void glbrender$assertRuntimeChannelWorks(CallbackInfo ci) {
        if (glbrender$done || glbrender$attempts++ > SMOKE_TEST_TIMEOUT_FRAMES) {
            return;
        }
        Minecraft minecraft = (Minecraft) (Object) this;
        try {
            if (runSmokeTest(minecraft)) {
                // The channel is proven; the game stays open so the in-world draw gets its frames.
                glbrender$done = true;
            }
        } catch (Exception | UnsatisfiedLinkError e) {
            // The title also updates while packs are still mounting, so a failure just means "try again".
            GlbRenderMod.LOGGER.warn("[glbrender] smoke test attempt failed: {}", e.toString());
        }
    }

    /** @return false when the game is not far enough along yet, so the caller retries next frame. */
    private boolean runSmokeTest(Minecraft minecraft) throws Exception {
        GlbRenderMod.LOGGER.info("[glbrender] mixin channel OK");

        // Route A stands or falls on driving Minecraft's context without RenderSystem's wrappers.
        Gl.INSTANCE.init();
        String glVersion = GL11.glGetString(GL11.GL_VERSION);
        GlbRenderMod.LOGGER.info(
            "[glbrender] gl context = {}.{}, version=[{}], ssbo={}, tbo={}, labels={}",
            Gl.INSTANCE.getMajorVersion(), Gl.INSTANCE.getMinorVersion(), glVersion,
            Gl.INSTANCE.isSsboSupported(), Gl.INSTANCE.isTextureBufferSupported(), Gl.INSTANCE.isDebugLabelsSupported()
        );
        if (glVersion == null) {
            throw new IllegalStateException("no OpenGL context on the render thread");
        }

        // The ported GLSL has to come out of the mod jar through Minecraft's pack machinery.
        ResourceLocation shader = ResourceLocation.fromNamespaceAndPath("blazerod", "shaders/core/unlit.vsh");
        String firstLine = minecraft.getResourceManager()
            .getResource(shader)
            .map(resource -> {
                try (BufferedReader reader = resource.openAsReader()) {
                    return reader.lines().findFirst().orElse("");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            })
            .orElse("");
        GlbRenderMod.LOGGER.info("[glbrender] resource pack channel = {}", firstLine);
        if (firstLine.isBlank()) {
            throw new IllegalStateException(shader + " is not readable through the resource manager");
        }
        return true;
    }
}
