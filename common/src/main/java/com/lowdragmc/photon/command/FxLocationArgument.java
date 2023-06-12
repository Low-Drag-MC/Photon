package com.lowdragmc.photon.command;

import com.lowdragmc.lowdraglib.LDLib;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.CompletableFuture;

/**
 * @author KilaBash
 * @date 2023/6/12
 * @implNote FxLocationArgument
 */
public class FxLocationArgument extends ResourceLocationArgument {
    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        if (context.getSource() instanceof CommandSourceStack stack) {
            return SharedSuggestionProvider.suggestResource(
                    stack.getServer().getResourceManager().listResources("fx", arg -> arg.getPath().endsWith(".fx")).keySet()
                            .stream().map(rl -> new ResourceLocation(rl.getNamespace(), rl.getPath().substring(3, rl.getPath().length() - 3))),
                    builder);
        } else if (LDLib.isClient()) {
            return SharedSuggestionProvider.suggestResource(
                    Minecraft.getInstance().getResourceManager().listResources("fx", arg -> arg.getPath().endsWith(".fx")).keySet()
                            .stream().map(rl -> new ResourceLocation(rl.getNamespace(), rl.getPath().substring(3, rl.getPath().length() - 3))),
                    builder);
        }
        return super.listSuggestions(context, builder);
    }
}
