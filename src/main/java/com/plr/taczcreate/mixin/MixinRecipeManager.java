package com.plr.taczcreate.mixin;

import com.google.gson.JsonElement;
import com.plr.taczcreate.AutoAmmoRecipes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Injects auto-generated Create mixing recipes for any TaCZ ammo (from all loaded gun packs) into
 * the recipe map right before the recipe manager parses and finalizes it.
 */
@Mixin(RecipeManager.class)
public class MixinRecipeManager {

    @Inject(method = "apply", at = @At("HEAD"))
    private void tacz_create_ammo$injectAmmoRecipes(Map<ResourceLocation, JsonElement> object, ResourceManager resourceManager, ProfilerFiller profiler, CallbackInfo ci) {
        AutoAmmoRecipes.inject(object, resourceManager);
    }
}