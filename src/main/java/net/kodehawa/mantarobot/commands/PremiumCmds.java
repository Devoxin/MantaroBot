/*
 * Copyright (C) 2016-2021 David Rubio Escares / Kodehawa
 *
 *  Mantaro is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  Mantaro is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mantaro. If not, see http://www.gnu.org/licenses/
 */

package net.kodehawa.mantarobot.commands;

import com.google.common.eventbus.Subscribe;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.kodehawa.mantarobot.commands.currency.profile.Badge;
import net.kodehawa.mantarobot.core.CommandRegistry;
import net.kodehawa.mantarobot.core.command.meta.*;
import net.kodehawa.mantarobot.core.command.slash.SlashCommand;
import net.kodehawa.mantarobot.core.command.slash.SlashContext;
import net.kodehawa.mantarobot.core.modules.Module;
import net.kodehawa.mantarobot.core.modules.commands.SimpleCommand;
import net.kodehawa.mantarobot.core.modules.commands.base.CommandCategory;
import net.kodehawa.mantarobot.core.modules.commands.base.Context;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.entities.Player;
import net.kodehawa.mantarobot.db.entities.PremiumKey;
import net.kodehawa.mantarobot.log.LogUtils;
import net.kodehawa.mantarobot.utils.APIUtils;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import net.kodehawa.mantarobot.utils.commands.ratelimit.IncreasingRateLimiter;
import net.kodehawa.mantarobot.utils.commands.ratelimit.RatelimitUtils;

import java.awt.*;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.lang.System.currentTimeMillis;

@Module
public class PremiumCmds {
    @Subscribe
    public void register(CommandRegistry cr) {
        cr.registerSlash(ActivateKey.class);
        cr.registerSlash(Premium.class);
    }

    @Description("Activates a premium key.")
    @Category(CommandCategory.UTILS)
    @Options({
            @Options.Option(type = OptionType.STRING, name = "key", description = "The key to use.", required = true)
    })
    @Help(
            description = "Activates a premium key. Example: `~>activatekey a4e98f07-1a32-4dcc-b53f-c540214d54ec`. No, that isn't a valid key.",
            usage = "`/activatekey [key]`",
            parameters = {
                    @Help.Parameter(name = "key", description = "The key to activate. If it's a server key, make sure to run this command in the server where you want to enable premium on.")
            }
    )
    public static class ActivateKey extends SlashCommand {
        @Override
        protected void process(SlashContext ctx) {
            ctx.deferEphemeral();
            final var db = ctx.db();
            if (ctx.getConfig().isPremiumBot()) {
                ctx.reply("commands.activatekey.mp", EmoteReference.WARNING);
                return;
            }

            var key = db.getPremiumKey(ctx.getOptionAsString("key"));
            if (key == null || (key.isEnabled())) {
                ctx.reply("commands.activatekey.invalid_key", EmoteReference.ERROR);
                return;
            }

            var scopeParsed = key.getParsedType();
            var author = ctx.getAuthor();
            if (scopeParsed.equals(PremiumKey.Type.GUILD)) {
                var guild = ctx.getDBGuild();
                var currentKey = db.getPremiumKey(guild.getData().getPremiumKey());
                if (currentKey != null && currentKey.isEnabled() && currentTimeMillis() < currentKey.getExpiration()) { //Should always be enabled...
                    ctx.reply("commands.activatekey.guild_already_premium", EmoteReference.POPPER);
                    return;
                }

                // Add to keys claimed storage if it's NOT your first key (count starts at 2/2 = 1)
                if (!author.getId().equals(key.getOwner())) {
                    var ownerUser = db.getUser(key.getOwner());
                    ownerUser.getData().getKeysClaimed().put(author.getId(), key.getId());
                    ownerUser.saveAsync();
                }

                key.activate(180);
                guild.getData().setPremiumKey(key.getId());
                guild.saveAsync();

                ctx.reply("commands.activatekey.guild_successful", EmoteReference.POPPER, key.getDurationDays());
                return;
            }

            if (scopeParsed.equals(PremiumKey.Type.USER)) {
                var dbUser = ctx.getDBUser();
                var player = ctx.getPlayer();

                if (dbUser.isPremium()) {
                    ctx.reply("commands.activatekey.user_already_premium", EmoteReference.POPPER);
                    return;
                }

                if (author.getId().equals(key.getOwner())) {
                    if (player.getData().addBadgeIfAbsent(Badge.DONATOR_2)) {
                        player.saveUpdating();
                    }
                }

                // Add to keys claimed storage if it's NOT your first key (count starts at 2/2 = 1)
                if (!author.getId().equals(key.getOwner())) {
                    var ownerUser = db.getUser(key.getOwner());
                    ownerUser.getData().getKeysClaimed().put(author.getId(), key.getId());
                    ownerUser.saveAsync();
                }

                key.activate(author.getId().equals(key.getOwner()) ? 365 : 180);
                dbUser.getData().setPremiumKey(key.getId());
                dbUser.saveAsync();

                ctx.reply("commands.activatekey.user_successful", EmoteReference.POPPER);
            }
        }
    }

    @Description("Check premium status of a user or a server.")
    @Category(CommandCategory.UTILS)
    @Options({
            @Options.Option(type = OptionType.USER, name = "user", description = "The user to check. If not specified, it's you.")
    })
    @Help(
            description = "Checks the premium status of a user or a server.",
            usage = "`/premium user [user]` or `/premium server`",
            parameters = {
                    @Help.Parameter(name = "user", description = "The user to check for. If not specified, it checks yourself.", optional = true)
            }
    )
    public static class Premium extends SlashCommand {
        @Override
        protected void process(SlashContext ctx) {}

        @Name("user")
        @Description("Checks the premium status of an user.")
        public static class UserCommand extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                var toCheck = ctx.getOptionAsUser("user", ctx.getAuthor());
                var dbUser = ctx.db().getUser(toCheck);
                var data = dbUser.getData();
                var isLookup = toCheck.getIdLong() != ctx.getAuthor().getIdLong();

                if (!dbUser.isPremium()) {
                    ctx.replyEphemeral("commands.vipstatus.user.not_premium", EmoteReference.ERROR, toCheck.getAsTag());
                    return;
                }

                var lang = ctx.getLanguageContext();
                var embedBuilder = new EmbedBuilder()
                        .setAuthor(isLookup ? String.format(lang.get("commands.vipstatus.user.header_other"), toCheck.getName())
                                : lang.get("commands.vipstatus.user.header"), null, toCheck.getEffectiveAvatarUrl()
                        );

                var currentKey = ctx.db().getPremiumKey(data.getPremiumKey());

                if (currentKey == null || currentKey.validFor() < 1) {
                    ctx.replyEphemeral("commands.vipstatus.user.not_premium", toCheck.getAsTag(), EmoteReference.ERROR);
                    return;
                }

                var owner = ctx.getShardManager().retrieveUserById(currentKey.getOwner()).complete();
                var marked = false;
                if (owner == null) {
                    marked = true;
                    owner = ctx.getAuthor();
                }

                // Give the badge to the key owner, I'd guess?
                if (!marked && isLookup) {
                    Player player = ctx.db().getPlayer(owner);
                    if (player.getData().addBadgeIfAbsent(Badge.DONATOR_2))
                        player.saveUpdating();
                }

                var patreonInformation = APIUtils.getPledgeInformation(owner.getId());
                var linkedTo = currentKey.getData().getLinkedTo();
                var amountClaimed = data.getKeysClaimed().size();

                embedBuilder.setColor(Color.CYAN)
                        .setThumbnail(toCheck.getEffectiveAvatarUrl())
                        .setDescription(lang.get("commands.vipstatus.user.premium") + "\n" + lang.get("commands.vipstatus.description"))
                        .addField(lang.get("commands.vipstatus.key_owner"), owner.getName() + "#" + owner.getDiscriminator(), true)
                        .addField(lang.get("commands.vipstatus.patreon"),
                                patreonInformation == null ? "Error" : String.valueOf(patreonInformation.getLeft()), true)
                        .addField(lang.get("commands.vipstatus.keys_claimed"), String.valueOf(amountClaimed), false)
                        .addField(lang.get("commands.vipstatus.linked"), String.valueOf(linkedTo != null), false)
                        .setFooter(lang.get("commands.vipstatus.thank_note"), null);

                try {
                    // User has more keys than what the system would allow. Warn.
                    if (patreonInformation != null && patreonInformation.getLeft()) {
                        var patreonAmount = Double.parseDouble(patreonInformation.getRight());
                        if ((patreonAmount / 2) - amountClaimed < 0) {
                            var amount = amountClaimed - (patreonAmount / 2);
                            var keys = data.getKeysClaimed()
                                    .values()
                                    .stream()
                                    .limit((long) amount)
                                    .map(s -> "key:" + s)
                                    .collect(Collectors.joining("\n"));

                            LogUtils.log(
                                    """
                                    %s has more keys claimed than given keys, dumping extra keys:
                                    %s
                                    Currently pledging: %s, Claimed keys: %s, Should have %s total keys.""".formatted(
                                            owner.getId(), Utils.paste(keys, true),
                                            patreonAmount, amountClaimed, (patreonAmount / 2)
                                    )
                            );
                        }
                    }
                } catch (Exception ignored) { }

                if (linkedTo != null) {
                    var linkedUser = ctx.getShardManager().retrieveUserById((currentKey.getOwner())).complete();
                    if (linkedUser != null)
                        embedBuilder.addField(lang.get("commands.vipstatus.linked_to"),
                                linkedUser.getAsTag(),
                                true
                        );
                } else {
                    embedBuilder.addField(lang.get("commands.vipstatus.expire"),
                            currentKey.validFor() + " " + lang.get("general.days"),
                            true
                    ).addField(lang.get("commands.vipstatus.key_duration"),
                            currentKey.getDurationDays() + " " + lang.get("general.days"),
                            true
                    );
                }

                ctx.reply(embedBuilder.build());
            }
        }

        @Name("server")
        @Description("Checks the premium status of this server.")
        public static class GuildCommand extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                var dbGuild = ctx.getDBGuild();
                if (!dbGuild.isPremium()) {
                    ctx.replyEphemeral("commands.vipstatus.guild.not_premium", EmoteReference.ERROR);
                    return;
                }

                var lang = ctx.getLanguageContext();
                var embedBuilder = new EmbedBuilder()
                        .setAuthor(String.format(lang.get("commands.vipstatus.guild.header"), ctx.getGuild().getName()),
                                null, ctx.getAuthor().getEffectiveAvatarUrl());

                var currentKey = ctx.db().getPremiumKey(dbGuild.getData().getPremiumKey());
                if (currentKey == null || currentKey.validFor() < 1) {
                    ctx.replyEphemeral("commands.vipstatus.guild.not_premium", EmoteReference.ERROR);
                    return;
                }

                var owner = ctx.getShardManager().retrieveUserById(currentKey.getOwner()).complete();
                if (owner == null)
                    owner = Objects.requireNonNull(ctx.getGuild().getOwner()).getUser();

                var patreonInformation = APIUtils.getPledgeInformation(owner.getId());
                var linkedTo = currentKey.getData().getLinkedTo();
                embedBuilder.setColor(Color.CYAN)
                        .setThumbnail(ctx.getGuild().getIconUrl())
                        .setDescription(lang.get("commands.vipstatus.guild.premium")  + "\n" + lang.get("commands.vipstatus.description"))
                        .addField(lang.get("commands.vipstatus.key_owner"), owner.getName() + "#" + owner.getDiscriminator(), true)
                        .addField(lang.get("commands.vipstatus.patreon"),
                                patreonInformation == null ? "Error" : String.valueOf(patreonInformation.getLeft()), true)
                        .addField(lang.get("commands.vipstatus.linked"), String.valueOf(linkedTo != null), false)
                        .setFooter(lang.get("commands.vipstatus.thank_note"), null);

                if (linkedTo != null) {
                    User linkedUser = ctx.getShardManager().retrieveUserById(currentKey.getOwner()).complete();
                    if (linkedUser != null)
                        embedBuilder.addField(lang.get("commands.vipstatus.linked_to"), linkedUser.getName()  + "#" +
                                linkedUser.getDiscriminator(), false);
                } else {
                    embedBuilder
                            .addField(lang.get("commands.vipstatus.expire"), currentKey.validFor() + " " + lang.get("general.days"), true)
                            .addField(lang.get("commands.vipstatus.key_duration"), currentKey.getDurationDays() + " " + lang.get("general.days"), true);
                }

                ctx.reply(embedBuilder.build());
            }
        }
    }

    //@Subscribe
    public void claimkey(CommandRegistry cr) {
        final IncreasingRateLimiter rateLimiter = new IncreasingRateLimiter.Builder()
                .spamTolerance(3)
                .limit(2)
                .cooldown(1, TimeUnit.MINUTES)
                .maxCooldown(5, TimeUnit.MINUTES)
                .pool(MantaroData.getDefaultJedisPool())
                .prefix("claimkey")
                .build();

        cr.register("claimkey", new SimpleCommand(CommandCategory.UTILS) {
            @Override
            protected void call(Context ctx, String content, String[] args) {
                if (ctx.getConfig().isPremiumBot()) {
                    ctx.sendLocalized("commands.activatekey.mp", EmoteReference.WARNING);
                    return;
                }

                var scopeParsed = PremiumKey.Type.USER;
                if (args.length > 0) {
                    try {
                        scopeParsed = PremiumKey.Type.valueOf(args[0].toUpperCase());
                    } catch (IllegalArgumentException e) {
                        ctx.sendLocalized("commands.claimkey.invalid_scope", EmoteReference.ERROR);
                    }
                }

                final var author = ctx.getAuthor();
                var dbUser = ctx.getDBUser();

                //left: isPatron, right: pledgeAmount, basically.
                var pledgeInfo = APIUtils.getPledgeInformation(author.getId());
                if (pledgeInfo == null || !pledgeInfo.getLeft() || !dbUser.isPremium()) {
                    ctx.sendLocalized("commands.claimkey.not_patron", EmoteReference.ERROR);
                    return;
                }

                var pledgeAmount = Double.parseDouble(pledgeInfo.getRight());
                var data = dbUser.getData();

                //Check for pledge changes on DBUser#isPremium
                if (pledgeAmount == 1 || data.getKeysClaimed().size() >= (pledgeAmount / 2)) {
                    ctx.sendLocalized("commands.claimkey.already_top", EmoteReference.ERROR);
                    return;
                }

                if (!RatelimitUtils.ratelimit(rateLimiter, ctx, null)) {
                    return;
                }

                final var scope = scopeParsed;

                // Send message in a DM (it's private after all)
                ctx.getAuthor().openPrivateChannel()
                        .flatMap(privateChannel -> {
                            var newKey = PremiumKey.generatePremiumKey(author.getId(), scope, true);
                            var languageContext = ctx.getLanguageContext();

                            // Placeholder so they don't spam key creation. Save as random UUID first, to avoid conflicting.
                            data.getKeysClaimed().put(UUID.randomUUID().toString(), newKey.getId());
                            var amountClaimed = data.getKeysClaimed().size();

                            privateChannel.sendMessageFormat(languageContext.get("commands.claimkey.successful"),
                                    EmoteReference.HEART, newKey.getId(), amountClaimed, (int) ((pledgeAmount / 2) - amountClaimed), newKey.getParsedType()
                            ).queue();

                            dbUser.saveAsync();
                            newKey.saveAsync();

                            // Assume it all went well.
                            // This one is actually needed, lol.
                            return ctx.getChannel().sendMessageFormat(languageContext.get("commands.claimkey.success"), EmoteReference.CORRECT);
                        }).queue(null, error -> ctx.sendLocalized("commands.claimkey.cant_dm", EmoteReference.ERROR));
            }
        });
    }
}
