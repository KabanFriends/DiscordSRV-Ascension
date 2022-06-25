/*
 * This file is part of DiscordSRV, licensed under the GPLv3 License
 * Copyright (c) 2016-2022 Austin "Scarsz" Shapiro, Henri "Vankka" Schubin and DiscordSRV contributors
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

package com.discordsrv.bukkit.console.executor;

import com.discordsrv.bukkit.BukkitDiscordSRV;
import com.discordsrv.common.command.game.executor.CommandExecutor;
import com.discordsrv.common.command.game.executor.CommandExecutorProvider;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

import java.util.function.Consumer;

public class BukkitCommandExecutorProvider implements CommandExecutorProvider {

    private static final boolean HAS_PAPER_FORWARDING;

    static {
        boolean has = false;
        try {
            has = PaperCommandExecutor.CREATE_COMMAND_SENDER != null;
        } catch (Throwable ignored) {}
        HAS_PAPER_FORWARDING = has;
    }

    private final BukkitDiscordSRV discordSRV;

    public BukkitCommandExecutorProvider(BukkitDiscordSRV discordSRV) {
        this.discordSRV = discordSRV;
    }

    @Override
    public CommandExecutor getConsoleExecutor(Consumer<Component> componentConsumer) {
        if (HAS_PAPER_FORWARDING) {
            try {
                return new PaperCommandExecutor(discordSRV, componentConsumer);
            } catch (Throwable ignored) {}
        }

        CommandSender commandSender = new BukkitCommandExecutorProxy(discordSRV.server().getConsoleSender(), componentConsumer).getProxy();
        return new CommandSenderExecutor(discordSRV, commandSender);
    }
}