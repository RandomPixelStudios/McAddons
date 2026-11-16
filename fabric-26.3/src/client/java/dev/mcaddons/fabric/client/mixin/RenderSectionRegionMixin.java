package dev.mcaddons.fabric.client.mixin;

import dev.mcaddons.fabric.client.BlockHiding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla chunk meshing reads blocks through RenderSectionRegion. */
@Mixin(targets = "net.minecraft.client.renderer.chunk.RenderSectionRegion")
public class RenderSectionRegionMixin {
    @Inject(method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"), cancellable = true)
    private void mcaddons$hide(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (!BlockHiding.active()) return;
        BlockState s = cir.getReturnValue(), f = BlockHiding.filter(s);
        if (f != s) cir.setReturnValue(f);
    }
}
