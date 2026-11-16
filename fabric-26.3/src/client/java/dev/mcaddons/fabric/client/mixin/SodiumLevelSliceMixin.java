package dev.mcaddons.fabric.client.mixin;

import dev.mcaddons.fabric.client.BlockHiding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sodium meshes chunks through its own LevelSlice; only applied when Sodium is installed. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.LevelSlice")
public class SodiumLevelSliceMixin {
    @Inject(method = "getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"), cancellable = true, require = 0)
    private void mcaddons$hideXyz(int x, int y, int z, CallbackInfoReturnable<BlockState> cir) {
        if (!BlockHiding.active()) return;
        BlockState s = cir.getReturnValue(), f = BlockHiding.filter(s);
        if (f != s) cir.setReturnValue(f);
    }

    @Inject(method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"), cancellable = true, require = 0)
    private void mcaddons$hidePos(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (!BlockHiding.active()) return;
        BlockState s = cir.getReturnValue(), f = BlockHiding.filter(s);
        if (f != s) cir.setReturnValue(f);
    }
}
