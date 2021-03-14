package me.egg82.antivpn.api.platform;

import me.egg82.avpn.api.platform.AbstractPluginMetadata;
import org.jetbrains.annotations.NotNull;

public class BukkitPluginMetadata extends AbstractPluginMetadata {
    private final String pluginVersion;

    public BukkitPluginMetadata(@NotNull String pluginVersion) {
        this.pluginVersion = pluginVersion;
    }

    @Override
    public @NotNull String getVersion() { return pluginVersion; }
}
