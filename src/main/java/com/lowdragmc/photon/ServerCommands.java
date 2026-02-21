package com.lowdragmc.photon;

import com.lowdragmc.photon.command.*;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.List;

/**
 * @author KilaBash
 * @date 2023/2/9
 * @implNote ServerCommands
 */
public class ServerCommands {
    public static List<LiteralArgumentBuilder<CommandSourceStack>> createServerCommands() {
        return List.of(
                Commands.literal("photon")
                        .then(Commands.literal("fx").requires(source -> source.hasPermission(2))
                                .then(Commands.argument("location", new FxLocationArgument())
                                        .then(BlockEffectCommand.createServerCommand())
                                        .then(EntityEffectCommand.createServerCommand())
                                )
                                .then(Commands.literal("remove")
                                        .then(RemoveBlockEffectCommand.createServerCommand())
                                        .then(RemoveEntityEffectCommand.createServerCommand())
                                )
                        )

        );
    }
}
