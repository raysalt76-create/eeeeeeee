package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.util.XrayState;
import interstellarTikiTiks.apexx.client.util.render.AlphaVertexConsumer;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BlockModelPart;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Xray, vanilla chunk builder. A block that is neither listed nor a translucent background block is
 * left out of the mesh altogether (so the quads face culling cannot remove, plants and rails, go
 * too); a background block is meshed through an alpha-multiplying consumer. Fluids are separate.
 */
@Mixin(BlockRenderManager.class)
public abstract class BlockRenderManagerOcclusionMixin {

    @Inject(method = "renderBlock", at = @At("HEAD"), cancellable = true)
    private void apexx$xraySkipHidden(BlockState state, BlockPos pos, BlockRenderView world, MatrixStack matrices,
                                      VertexConsumer consumer, boolean cull, List<BlockModelPart> parts,
                                      CallbackInfo ci) {
        if (XrayState.isActive() && !XrayState.isVisible(state.getBlock()) && !XrayState.isBackground(state)) {
            ci.cancel();
        }
    }

    @Redirect(method = "renderBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/block/BlockModelRenderer;render(Lnet/minecraft/world/BlockRenderView;"
                    + "Ljava/util/List;Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;"
                    + "Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;ZI)V"))
    private void apexx$xrayBackgroundAlpha(BlockModelRenderer renderer, BlockRenderView world,
                                           List<BlockModelPart> parts, BlockState state, BlockPos pos,
                                           MatrixStack matrices, VertexConsumer consumer, boolean cull, int overlay) {
        VertexConsumer out = XrayState.isBackground(state)
                ? new AlphaVertexConsumer(consumer, XrayState.backgroundOpacity()) : consumer;
        renderer.render(world, parts, state, pos, matrices, out, cull, overlay);
    }
}
