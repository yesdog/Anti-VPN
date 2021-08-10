package me.egg82.antivpn.commands.internal;

import co.aikar.commands.CommandIssuer;
import com.velocitypowered.api.proxy.ProxyServer;
import me.egg82.antivpn.locale.LocalizedCommandSender;
import me.egg82.antivpn.services.lookup.PlayerInfo;
import me.egg82.antivpn.services.lookup.PlayerLookup;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public abstract class AbstractCommand implements Runnable {
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected final ProxyServer proxy;
    protected final LocalizedCommandSender issuer;

    protected AbstractCommand(@NotNull ProxyServer proxy, @NotNull LocalizedCommandSender issuer) {
        this.proxy = proxy;
        this.issuer = issuer;
    }

    protected @NotNull CompletableFuture<UUID> fetchUuid(@NotNull String name) { return PlayerLookup.get(name, proxy).thenApply(PlayerInfo::getUUID); }
}
