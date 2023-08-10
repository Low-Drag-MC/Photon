package com.lowdragmc.photon.fabric.core.mixins;

import com.lowdragmc.photon.command.FxLocationArgument;
import com.mojang.brigadier.arguments.ArgumentType;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.core.Registry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


/**
 * @author KilaBash
 * @date 2023/6/12
 * @implNote ArgumentTypeInfosMixin
 */
@Mixin(ArgumentTypeInfos.class)
public abstract class ArgumentTypeInfosMixin {
    @Shadow
    private static <A extends ArgumentType<?>, T extends ArgumentTypeInfo.Template<A>> ArgumentTypeInfo<A, T> register(Registry<ArgumentTypeInfo<?, ?>> registry, String id, Class<? extends A> argumentClass, ArgumentTypeInfo<A, T> info) {
        return null;
    }

    @Inject(
            method = "bootstrap",
            at = {@At(value = "HEAD")})
    private static void injectRegisterArgumentTypes(Registry<ArgumentTypeInfo<?, ?>> registry, CallbackInfoReturnable<ArgumentTypeInfo<?, ?>> cir) {
        register(registry, "fx_location", FxLocationArgument.class, SingletonArgumentInfo.contextFree(FxLocationArgument::new));
    }
}
