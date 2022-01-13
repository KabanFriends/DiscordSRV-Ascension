/*
 * This file is part of DiscordSRV, licensed under the GPLv3 License
 * Copyright (c) 2016-2021 Austin "Scarsz" Shapiro, Henri "Vankka" Schubin and DiscordSRV contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.discordsrv.common.channel;

import com.discordsrv.common.DiscordSRV;
import com.discordsrv.common.config.main.ChannelUpdaterConfig;
import com.discordsrv.common.module.type.AbstractModule;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.GuildChannel;
import net.dv8tion.jda.api.managers.channel.ChannelManager;
import net.dv8tion.jda.api.managers.channel.concrete.TextChannelManager;
import org.apache.commons.lang3.StringUtils;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ChannelUpdaterModule extends AbstractModule {

    private final Set<ScheduledFuture<?>> futures = new LinkedHashSet<>();

    public ChannelUpdaterModule(DiscordSRV discordSRV) {
        super(discordSRV);
    }

    @Override
    public void enable() {
        reload();
    }

    @Override
    public void reload() {
        Iterator<ScheduledFuture<?>> iterator = futures.iterator();
        while (iterator.hasNext()) {
            iterator.next().cancel(false);
            iterator.remove();
        }

        for (ChannelUpdaterConfig config : discordSRV.config().channelUpdaters) {
            futures.add(
                    discordSRV.scheduler().runAtFixedRate(() -> update(config), config.timeMinutes, TimeUnit.MINUTES)
            );
        }
    }

    public void update(ChannelUpdaterConfig config) {
        JDA jda = discordSRV.jda().orElse(null);
        if (jda == null) {
            return;
        }

        String topicFormat = config.topicFormat;
        String nameFormat = config.nameFormat;

        if (topicFormat != null) {
            topicFormat = discordSRV.placeholderService().replacePlaceholders(topicFormat);
        }
        if (nameFormat != null) {
            nameFormat = discordSRV.placeholderService().replacePlaceholders(nameFormat);
        }

        for (Long channelId : config.channelIds) {
            GuildChannel channel = jda.getGuildChannelById(channelId);
            if (channel == null) {
                continue;
            }

            ChannelManager<?, ?> manager = channel.getManager();
            if (manager instanceof TextChannelManager && StringUtils.isNotEmpty(topicFormat)) {
                manager = ((TextChannelManager) manager).setTopic(topicFormat);
            }
            if (StringUtils.isNotEmpty(nameFormat)) {
                manager = manager.setName(nameFormat);
            }

            manager.timeout(30, TimeUnit.SECONDS).queue();
        }
    }


}
