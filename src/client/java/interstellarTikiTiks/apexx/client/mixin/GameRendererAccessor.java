package interstellarTikiTiks.apexx.client.mixin;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

    @Invoker("getFov")
    float callGetFov(Camera camera, float tickDelta, boolean changingFov);

    @Accessor("guiRenderer")
    GuiRenderer apexx$getGuiRenderer();

    @Accessor("fogRenderer")
    FogRenderer apexx$getFogRenderer();
}
