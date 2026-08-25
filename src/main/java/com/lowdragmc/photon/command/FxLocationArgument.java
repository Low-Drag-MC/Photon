package com.lowdragmc.photon.command;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.photon.client.fx.FXHelper;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;

import java.util.concurrent.CompletableFuture;

/**
 * @author KilaBash
 * @date 2023/6/12
 * @implNote FxLocationArgument
 */
public class FxLocationArgument extends IdentifierArgument {
    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        if (LDLib2.isClient()) {
            // the cached listing, not a fresh walk of every pack: this runs on every character typed
            return SharedSuggestionProvider.suggestResource(FXHelper.listAllFX(), builder);
        }
        return super.listSuggestions(context, builder);
    }
}
