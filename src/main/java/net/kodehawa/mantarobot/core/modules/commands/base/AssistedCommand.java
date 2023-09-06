/*
 * Copyright (C) 2016 Kodehawa
 *
 * Mantaro is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * Mantaro is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mantaro. If not, see http://www.gnu.org/licenses/
 *
 */

package net.kodehawa.mantarobot.core.modules.commands.base;

import net.dv8tion.jda.api.EmbedBuilder;
import net.kodehawa.mantarobot.core.command.slash.IContext;
import net.kodehawa.mantarobot.options.core.Option;

/**
 * "Assisted" version of the {@link Command} interface, providing some "common ground" for all Commands based on it.
 */
public interface AssistedCommand extends Command {
    default EmbedBuilder baseEmbed(IContext ctx, String name) {
        return baseEmbed(ctx, name, ctx.getAuthor().getEffectiveAvatarUrl());
    }

    default EmbedBuilder baseEmbed(IContext ctx, String name, String image) {
        return new EmbedBuilder()
                .setAuthor(name, null, image)
                .setColor(ctx.getMember().getColor())
                .setFooter("Requested by: %s".formatted(ctx.getMember().getEffectiveName()),
                        ctx.getGuild().getIconUrl()
                );
    }

    @SuppressWarnings("unused")
    default void doTimes(int times, Runnable runnable) {
        for (int i = 0; i < times; i++) runnable.run();
    }

    @Override
    default Command addOption(String call, Option option) {
        Option.addOption(call, option);
        return this;
    }
}
