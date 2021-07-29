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

package com.discordsrv.common;

import com.discordsrv.api.discord.connection.DiscordConnectionDetails;
import com.discordsrv.api.event.bus.EventBus;
import com.discordsrv.api.event.events.lifecycle.DiscordSRVShuttingDownEvent;
import com.discordsrv.common.api.util.ApiInstanceUtil;
import com.discordsrv.common.channel.ChannelConfig;
import com.discordsrv.common.channel.DefaultGlobalChannel;
import com.discordsrv.common.component.ComponentFactory;
import com.discordsrv.common.config.connection.ConnectionConfig;
import com.discordsrv.common.config.main.MainConfig;
import com.discordsrv.common.config.manager.ConnectionConfigManager;
import com.discordsrv.common.config.manager.MainConfigManager;
import com.discordsrv.common.discord.api.DiscordAPIImpl;
import com.discordsrv.common.discord.connection.DiscordConnectionManager;
import com.discordsrv.common.discord.connection.jda.JDAConnectionManager;
import com.discordsrv.common.discord.details.DiscordConnectionDetailsImpl;
import com.discordsrv.common.event.bus.EventBusImpl;
import com.discordsrv.common.function.CheckedRunnable;
import com.discordsrv.common.listener.DefaultChannelLookupListener;
import com.discordsrv.common.listener.DefaultChatListener;
import com.discordsrv.common.logging.DependencyLoggingFilter;
import com.discordsrv.common.logging.logger.backend.LoggingBackend;
import net.dv8tion.jda.api.JDA;
import org.jetbrains.annotations.NotNull;

import javax.annotation.OverridingMethodsMustInvokeSuper;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public abstract class AbstractDiscordSRV<C extends MainConfig, CC extends ConnectionConfig> implements DiscordSRV {

    private final AtomicReference<Status> status = new AtomicReference<>(Status.INITIALIZED);

    // DiscordSRVApi
    private final EventBus eventBus;
    private final ComponentFactory componentFactory;
    private final DiscordAPIImpl discordAPI;
    private final DiscordConnectionDetails discordConnectionDetails;

    // DiscordSRV
    private final DefaultGlobalChannel defaultGlobalChannel = new DefaultGlobalChannel(this);
    private ChannelConfig channelConfig;
    private DiscordConnectionManager discordConnectionManager;

    // Internal
    private final DependencyLoggingFilter dependencyLoggingFilter = new DependencyLoggingFilter(this);

    public AbstractDiscordSRV() {
        ApiInstanceUtil.setInstance(this);
        this.eventBus = new EventBusImpl(this);
        this.componentFactory = new ComponentFactory();
        this.discordAPI = new DiscordAPIImpl(this);
        this.discordConnectionDetails = new DiscordConnectionDetailsImpl(this);
    }

    // DiscordSRVApi

    @Override
    public @NotNull Status status() {
        return status.get();
    }

    @Override
    public @NotNull EventBus eventBus() {
        return eventBus;
    }

    @Override
    public @NotNull ComponentFactory componentFactory() {
        return componentFactory;
    }

    @Override
    public @NotNull DiscordAPIImpl discordAPI() {
        return discordAPI;
    }

    @Override
    public JDA jda() {
        return discordConnectionManager != null ? discordConnectionManager.instance() : null;
    }

    @Override
    public @NotNull DiscordConnectionDetails discordConnectionDetails() {
        return discordConnectionDetails;
    }

    // DiscordSRV

    @Override
    public DefaultGlobalChannel defaultGlobalChannel() {
        return defaultGlobalChannel;
    }

    @Override
    public ChannelConfig channelConfig() {
        return channelConfig;
    }

    @Override
    public DiscordConnectionManager discordConnectionManager() {
        return discordConnectionManager;
    }

    // Config
    @Override
    public abstract ConnectionConfigManager<CC> connectionConfigManager();

    @Override
    public CC connectionConfig() {
        return connectionConfigManager().config();
    }

    @Override
    public abstract MainConfigManager<C> configManager();

    @Override
    public C config() {
        return configManager().config();
    }

    @Override
    public Locale locale() {
        // TODO: config
        return Locale.getDefault();
    }

    @Override
    public void setStatus(Status status) {
        this.status.set(status);
    }

    protected CompletableFuture<Void> invoke(CheckedRunnable runnable, String message, boolean enable) {
        return CompletableFuture.runAsync(() -> {
            try {
                runnable.run();
            } catch (Throwable t) {
                if (enable) {
                    setStatus(Status.FAILED_TO_START);
                    disable();
                }
                logger().error(message, t);
            }
        }, scheduler().executor());
    }

    @Override
    public final CompletableFuture<Void> invokeEnable() {
        return invoke(this::enable, "Failed to enable", true);
    }

    @Override
    public final CompletableFuture<Void> invokeDisable() {
        return invoke(this::disable, "Failed to disable", false);
    }

    @Override
    public final CompletableFuture<Void> invokeReload() {
        return invoke(this::reload, "Failed to reload", false);
    }

    @OverridingMethodsMustInvokeSuper
    protected void enable() throws Throwable {
        // Config
        try {
            connectionConfigManager().load();
            configManager().load();

            // Utility
            channelConfig = new ChannelConfig(this);
        } catch (Throwable t) {
            setStatus(Status.FAILED_TO_LOAD_CONFIG);
            throw t;
        }

        // Logging
        LoggingBackend backend = console().loggingBackend();
        backend.addFilter(dependencyLoggingFilter);

        discordConnectionManager = new JDAConnectionManager(this);
        discordConnectionManager.connect().join();

        // Register PlayerProvider listeners
        playerProvider().subscribe();

        // Register listeners
        // Chat
        eventBus().subscribe(new DefaultChannelLookupListener(this));
        eventBus().subscribe(new DefaultChatListener(this));
    }

    @OverridingMethodsMustInvokeSuper
    protected void disable() {
        Status status = this.status.get();
        if (status.isShutdown()) {
            // Already shutting down/shutdown
            return;
        }
        this.status.set(Status.SHUTTING_DOWN);
        eventBus().publish(new DiscordSRVShuttingDownEvent());

        // Logging
        LoggingBackend backend = console().loggingBackend();
        backend.removeFilter(dependencyLoggingFilter);
    }

    @OverridingMethodsMustInvokeSuper
    protected void reload() {

    }
}
