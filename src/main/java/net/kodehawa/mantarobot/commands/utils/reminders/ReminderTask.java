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

package net.kodehawa.mantarobot.commands.utils.reminders;

import net.dv8tion.jda.api.entities.User;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ReminderTask {
    private static final Logger log = LoggerFactory.getLogger(ReminderTask.class);

    public static void handle() {
        log.debug("Checking reminder data...");
        // The issue with handling this kind of stuff with ScheduledTasks is that we need to catch everything
        // In case the Redis server is, for some reason, unavailable, or getting the resource fails
        // This will silently die and kill every further reminder from being fired.
        // Such, we need two try/catches here, one so the ScheduledTask doesn't die, and one so the loop doesn't break
        // Java moments.
        try (Jedis j = MantaroData.getDefaultJedisPool().getResource()) {
            List<String> reminders = j.zrange("zreminder", 0, 14);
            var bot = MantaroBot.getInstance();

            log.debug("Reminder check - remainder is: {}", reminders.size());
            for (var rem : reminders) {
                try { // If we don't try here regardless, we kill the loop.
                    var data = new JSONObject(rem);
                    var fireAt = data.getLong("at");

                    // If the time has passed...
                    if (System.currentTimeMillis() >= fireAt) {
                        log.debug("Reminder date has passed, remind accordingly.");
                        var userId = data.getString("user");
                        var fullId = data.getString("id") + ":" + userId;
                        var guildId = data.getString("guild");
                        var scheduledAt = data.getLong("scheduledAt");

                        // 1 day passed already, assuming it's a stale reminder:
                        // Done because ReminderTask wasn't working.
                        if (System.currentTimeMillis() - fireAt > TimeUnit.DAYS.toMillis(1)) {
                            Reminder.cancel(userId, fullId, Reminder.CancelReason.CANCEL);
                            return;
                        }

                        var reminder = data.getString("reminder"); //The actual reminder data
                        var guild = bot.getShardManager().getGuildById(guildId);
                        var scheduledTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(scheduledAt), ZoneId.systemDefault());
                        bot.getShardManager().retrieveUserById(userId)
                                .flatMap(User::openPrivateChannel)
                                .flatMap(privateChannel -> privateChannel
                                        .sendMessageFormat("""
                                                        %s**Reminder!**
                                                        
                                                        You asked me to remind you of: **%s**
                                                        Asked at: <t:%s>%s""",
                                                EmoteReference.POPPER,
                                                reminder.trim().isEmpty() ? "something" : reminder, scheduledTime.toEpochSecond(),
                                                (guild != null ? "%nAsked on: %s".formatted(guild.getName()) : "")
                                        )
                                ).queue(success -> {
                                            log.debug("Reminded {}. Removing from remind database", fullId);
                                            Reminder.cancel(userId, fullId, Reminder.CancelReason.REMINDED);
                                        }, err -> Reminder.cancel(userId, fullId, Reminder.CancelReason.ERROR_DELIVERING)
                                );
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
