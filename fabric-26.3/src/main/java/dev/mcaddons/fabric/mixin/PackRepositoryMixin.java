package dev.mcaddons.fabric.mixin;

import dev.mcaddons.fabric.PluginPack;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Arrays;

/** Adds the generated plugin pack to every pack repository (client resources + server data). */
@Mixin(PackRepository.class)
public abstract class PackRepositoryMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
    private static RepositorySource[] mcaddons$addPluginPack(RepositorySource[] sources) {
        boolean client = Arrays.stream(sources).anyMatch(s -> s.getClass().getName().contains("ClientPackSource"));
        RepositorySource[] out = Arrays.copyOf(sources, sources.length + 1);
        out[sources.length] = PluginPack.source(client ? PackType.CLIENT_RESOURCES : PackType.SERVER_DATA);
        return out;
    }
}
