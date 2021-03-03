package me.egg82.antivpn.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import me.egg82.antivpn.api.event.VPNEvent;
import me.egg82.antivpn.api.model.ip.AbstractIPManager;
import me.egg82.antivpn.api.model.player.AbstractPlayerManager;
import me.egg82.antivpn.api.model.source.SourceManagerImpl;
import me.egg82.avpn.api.platform.AbstractPluginMetadata;
import me.egg82.antivpn.api.platform.Platform;
import me.egg82.antivpn.config.ConfigUtil;
import me.egg82.antivpn.utils.PacketUtil;
import net.kyori.event.EventBus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class VPNAPIImpl implements VPNAPI {
    private final Platform platform;
    private final AbstractPluginMetadata pluginMetadata;

    private final AbstractIPManager ipManager;
    private final AbstractPlayerManager playerManager;
    private final SourceManagerImpl sourceManager;
    private final EventBus<VPNEvent> eventBus;

    private static VPNAPIImpl instance = null;
    public static @Nullable VPNAPIImpl get() { return instance; }

    public VPNAPIImpl(@NotNull Platform platform, @NotNull AbstractPluginMetadata pluginMetadata, @NotNull AbstractIPManager ipManager, @NotNull AbstractPlayerManager playerManager, @NotNull SourceManagerImpl sourceManager, @NotNull EventBus<VPNEvent> eventBus) {
        this.platform = platform;
        this.pluginMetadata = pluginMetadata;

        this.ipManager = ipManager;
        this.playerManager = playerManager;
        this.sourceManager = sourceManager;
        this.eventBus = eventBus;

        instance = this;
    }

    public @NotNull UUID getServerId() { return ConfigUtil.getCachedConfig().getServerId(); }

    public @NotNull AbstractIPManager getIPManager() { return ipManager; }

    public @NotNull AbstractPlayerManager getPlayerManager() { return playerManager; }

    public @NotNull SourceManagerImpl getSourceManager() { return sourceManager; }

    public @NotNull Platform getPlatform() { return platform; }

    public @NotNull AbstractPluginMetadata getPluginMetadata() { return pluginMetadata; }

    public @NotNull CompletableFuture<Void> runUpdateTask() { return CompletableFuture.runAsync(PacketUtil::trySendQueue); }

    public @NotNull EventBus<VPNEvent> getEventBus() { return eventBus; }
}
