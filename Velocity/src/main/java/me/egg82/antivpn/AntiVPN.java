package me.egg82.antivpn;

import co.aikar.commands.*;
import co.aikar.locales.MessageKeyProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.SetMultimap;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import me.egg82.antivpn.api.APIRegistrationUtil;
import me.egg82.antivpn.api.VPNAPI;
import me.egg82.antivpn.api.VPNAPIImpl;
import me.egg82.antivpn.api.VPNAPIProvider;
import me.egg82.antivpn.api.event.VPNEvent;
import me.egg82.antivpn.api.event.api.APIDisableEventImpl;
import me.egg82.antivpn.api.event.api.APILoadedEventImpl;
import me.egg82.antivpn.api.model.ip.VelocityIPManager;
import me.egg82.antivpn.api.model.player.VelocityPlayerManager;
import me.egg82.antivpn.api.model.source.Source;
import me.egg82.antivpn.api.model.source.SourceManagerImpl;
import me.egg82.antivpn.api.model.source.models.SourceModel;
import me.egg82.antivpn.api.platform.Platform;
import me.egg82.antivpn.api.platform.PluginMetadata;
import me.egg82.antivpn.api.platform.VelocityPlatform;
import me.egg82.antivpn.api.platform.VelocityPluginMetadata;
import me.egg82.antivpn.commands.AntiVPNCommand;
import me.egg82.antivpn.config.CachedConfig;
import me.egg82.antivpn.config.ConfigUtil;
import me.egg82.antivpn.config.ConfigurationFileUtil;
import me.egg82.antivpn.events.EventHolder;
import me.egg82.antivpn.events.PlayerEvents;
import me.egg82.antivpn.hooks.LuckPermsHook;
import me.egg82.antivpn.hooks.PlayerAnalyticsHook;
import me.egg82.antivpn.hooks.PluginHook;
import me.egg82.antivpn.locale.*;
import me.egg82.antivpn.messaging.MessagingService;
import me.egg82.antivpn.messaging.ServerIDUtil;
import me.egg82.antivpn.messaging.handler.MessagingHandler;
import me.egg82.antivpn.messaging.handler.MessagingHandlerImpl;
import me.egg82.antivpn.storage.StorageService;
import me.egg82.antivpn.utils.ValidationUtil;
import net.engio.mbassy.bus.MBassador;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.event.SimpleEventBus;
import ninja.egg82.events.VelocityEventSubscriber;
import ninja.egg82.service.ServiceLocator;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class AntiVPN {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private VelocityCommandManager commandManager;

    private final List<EventHolder> eventHolders = new ArrayList<>();
    private final List<VelocityEventSubscriber<?>> events = new ArrayList<>();
    private final List<ScheduledTask> tasks = new ArrayList<>();

    private final Object plugin;
    private final ProxyServer proxy;
    private final PluginDescription description;

    private LocalizedCommandSender consoleCommandIssuer = null;

    public AntiVPN(@NotNull Object plugin, @NotNull ProxyServer proxy, @NotNull PluginDescription description) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.description = description;
    }

    public void onLoad() {
        // Empty
    }

    public void onEnable() {

        commandManager = new VelocityCommandManager(proxy, plugin);
        commandManager.enableUnstableAPI("help");

        setChatColors();

        // TODO fix
        VelocityCommandIssuer console = commandManager.getCommandIssuer(proxy.getConsoleCommandSource());
        consoleCommandIssuer = new AbstractLocalizedCommandSender(console, console.getIssuer(), LocaleUtil.getConsoleI18N()) {
            @Override
            public boolean isConsole() {
                return true;
            }

            @Override
            public boolean isUser() {
                return false;
            }
        };

        loadServices();
        loadLanguages();
        loadCommands();
        loadEvents();
        loadTasks();
        loadHooks();

        int numEvents = events.size();
        for (EventHolder eventHolder : eventHolders) {
            numEvents += eventHolder.numEvents();
        }

        consoleCommandIssuer.sendMessage(MessageKey.GENERAL__ENABLE_MESSAGE);
        consoleCommandIssuer.sendMessage(MessageKey.GENERAL__LOAD_MESSAGE,
                                      "{version}", description.getVersion().get(),
                                      "{apiversion}", VPNAPIProvider.getInstance().getPluginMetadata().getApiVersion(),
                                      "{commands}", String.valueOf(commandManager.getRegisteredRootCommands().size()),
                                      "{events}", String.valueOf(numEvents),
                                      "{tasks}", String.valueOf(tasks.size())
        );
    }

    public void onDisable() {
        commandManager.unregisterCommands();

        for (ScheduledTask task : tasks) {
            task.cancel();
        }
        tasks.clear();

        try {
            VPNAPIProvider.getInstance().runUpdateTask().join();
        } catch (CancellationException | CompletionException ex) {
            logger.error(ex.getClass().getName() + ": " + ex.getMessage(), ex);
        }

        for (EventHolder eventHolder : eventHolders) {
            eventHolder.cancel();
        }
        eventHolders.clear();
        for (VelocityEventSubscriber<?> event : events) {
            event.cancel();
        }
        events.clear();

        unloadHooks();
        unloadServices();

        consoleCommandIssuer.sendMessage(MessageKey.GENERAL__DISABLE_MESSAGE);

    }

    private void loadLanguages() {
        CachedConfig cachedConfig = ConfigUtil.getCachedConfig();

        VelocityLocales locales = commandManager.getLocales();

        try {
            for (Locale locale : Locale.getAvailableLocales()) {
                ResourceBundle localeFile = LanguageFileUtil.getLanguage(
                        new File(description.getSource().get().getParent().toFile(), description.getName().get()),
                        locale
                );
                if (localeFile != null) {
                    commandManager.addSupportedLanguage(locale);
                    //loadYamlLanguageFile(locales, localeFile.get(), locale);
                }
            }
        } catch (IOException ex) {
            logger.error(ex.getClass().getName() + ": " + ex.getMessage(), ex);
        }

        locales.loadLanguages();
        locales.setDefaultLocale(cachedConfig.getLanguage());
        commandManager.usePerIssuerLocale(true);

        setChatColors();
    }

    private void setChatColors() {
        //commandManager.setFormat(MessageType.ERROR, NamedTextColor.DARK_RED, NamedTextColor.YELLOW, NamedTextColor.AQUA, NamedTextColor.WHITE);
        /*commandManager.setFormat(
                MessageType.INFO,
                NamedTextColor.WHITE,
                NamedTextColor.YELLOW,
                NamedTextColor.AQUA,
                NamedTextColor.GREEN,
                NamedTextColor.RED,
                NamedTextColor.GOLD,
                NamedTextColor.BLUE,
                NamedTextColor.GRAY,
                NamedTextColor.DARK_RED
        );*/
    }

    private void loadServices() {
        SourceManagerImpl sourceManager = new SourceManagerImpl();

        MessagingHandler messagingHandler = new MessagingHandlerImpl();
        ServiceLocator.register(messagingHandler);

        ConfigurationFileUtil.reloadConfig(
                new File(description.getSource().get().getParent().toFile(), description.getName().get()),
                consoleCommandIssuer,
                messagingHandler,
                sourceManager
        );

        CachedConfig cachedConfig = ConfigUtil.getCachedConfig();

        VelocityIPManager ipManager = new VelocityIPManager(proxy, sourceManager, cachedConfig.getCacheTime(), cachedConfig.getCacheTime().getUnit());
        VelocityPlayerManager playerManager = new VelocityPlayerManager(
                proxy,
                cachedConfig.getThreads(),
                cachedConfig.getMcLeaksKey(),
                cachedConfig.getCacheTime(),
                cachedConfig.getCacheTime().getUnit()
        );
        Platform platform = new VelocityPlatform(System.currentTimeMillis());
        VelocityPluginMetadata metadata = new VelocityPluginMetadata(proxy.getVersion().getVersion());
        VPNAPI api = new VPNAPIImpl(platform, metadata, ipManager, playerManager, sourceManager, new SimpleEventBus<>(VPNEvent.class));

        APIRegistrationUtil.register(api);

        api.getEventBus().post(new APILoadedEventImpl(api));
    }

    private void loadCommands() {
        commandManager.getCommandConditions().addCondition(String.class, "ip", (c, exec, value) -> {
            if (!ValidationUtil.isValidIp(value)) {
                throw new ConditionFailedException("Value must be a valid IP address.");
            }
        });

        commandManager.getCommandConditions().addCondition(String.class, "source", (c, exec, value) -> {
            List<Source<SourceModel>> sources = VPNAPIProvider.getInstance().getSourceManager().getSources();
            for (Source<SourceModel> source : sources) {
                if (source.getName().equalsIgnoreCase(value)) {
                    return;
                }
            }

            throw new ConditionFailedException("Value must be a valid source name.");
        });

        commandManager.getCommandCompletions().registerCompletion("source", c -> {
            String lower = c.getInput().toLowerCase().replace(" ", "_");
            List<Source<SourceModel>> sources = VPNAPIProvider.getInstance().getSourceManager().getSources();
            Set<String> retVal = new LinkedHashSet<>();

            for (Source<? extends SourceModel> source : sources) {
                String ss = source.getName();
                if (ss.toLowerCase().startsWith(lower)) {
                    retVal.add(ss);
                }
            }
            return ImmutableList.copyOf(retVal);
        });

        commandManager.getCommandConditions().addCondition(String.class, "storage", (c, exec, value) -> {
            String v = value.replace(" ", "_");
            CachedConfig cachedConfig = ConfigUtil.getCachedConfig();
            for (StorageService service : cachedConfig.getStorage()) {
                if (service.getName().equalsIgnoreCase(v)) {
                    return;
                }
            }
            throw new ConditionFailedException("Value must be a valid storage name.");
        });

        commandManager.getCommandCompletions().registerCompletion("storage", c -> {
            String lower = c.getInput().toLowerCase().replace(" ", "_");
            Set<String> retVal = new LinkedHashSet<>();
            CachedConfig cachedConfig = ConfigUtil.getCachedConfig();
            for (StorageService service : cachedConfig.getStorage()) {
                String ss = service.getName();
                if (ss.toLowerCase().startsWith(lower)) {
                    retVal.add(ss);
                }
            }
            return ImmutableList.copyOf(retVal);
        });

        commandManager.getCommandCompletions().registerCompletion("player", c -> {
            String lower = c.getInput().toLowerCase();
            Set<String> players = new LinkedHashSet<>();
            for (Player p : proxy.getAllPlayers()) {
                if (lower.isEmpty() || p.getUsername().toLowerCase().startsWith(lower)) {
                    players.add(p.getUsername());
                }
            }
            return ImmutableList.copyOf(players);
        });

        commandManager.getCommandCompletions().registerCompletion("type", c -> {
            String lower = c.getInput().toLowerCase();
            Set<String> retVal = new LinkedHashSet<>();
            if ("vpn".startsWith(lower)) {
                retVal.add("vpn");
            }
            if ("mcleaks".startsWith(lower)) {
                retVal.add("mcleaks");
            }
            return ImmutableList.copyOf(retVal);
        });

        commandManager.getCommandCompletions().registerCompletion("subcommand", c -> {
            String lower = c.getInput().toLowerCase();
            Set<String> commands = new LinkedHashSet<>();
            SetMultimap<String, RegisteredCommand> subcommands = commandManager.getRootCommand("antivpn").getSubCommands();
            for (Map.Entry<String, RegisteredCommand> kvp : subcommands.entries()) {
                if (!kvp.getValue().isPrivate() && (lower.isEmpty() || kvp.getKey().toLowerCase().startsWith(lower)) && kvp.getValue().getCommand().indexOf(' ') == -1) {
                    commands.add(kvp.getValue().getCommand());
                }
            }
            return ImmutableList.copyOf(commands);
        });

        commandManager.registerCommand(new AntiVPNCommand(proxy, description, consoleCommandIssuer));
    }

    private void loadEvents() {
        eventHolders.add(new PlayerEvents(plugin, proxy, consoleCommandIssuer));
    }

    private void loadTasks() {
        tasks.add(proxy.getScheduler().buildTask(plugin, () -> {
            try {
                VPNAPIProvider.getInstance().runUpdateTask().join();
            } catch (CancellationException | CompletionException ex) {
                logger.error(ex.getClass().getName() + ": " + ex.getMessage(), ex);
            }
        }).delay(1L, TimeUnit.SECONDS).repeat(1L, TimeUnit.SECONDS).schedule());
    }

    private void loadHooks() {
        PluginManager manager = proxy.getPluginManager();

        if (manager.getPlugin("plan").isPresent()) {
            consoleCommandIssuer.sendMessage(MessageKey.GENERAL__ENABLE_HOOK, "{plugin}", "Plan");
            ServiceLocator.register(new PlayerAnalyticsHook(proxy));
        } else {
            consoleCommandIssuer.sendMessage(MessageKey.GENERAL__NO_HOOK, "{plugin}", "Plan");
        }

        if (manager.getPlugin("luckperms").isPresent()) {
            consoleCommandIssuer.sendMessage(MessageKey.GENERAL__ENABLE_HOOK, "{plugin}", "LuckPerms");
            if (ConfigUtil.getDebugOrFalse()) {
                consoleCommandIssuer.sendMessage("<c2>Running actions on pre-login.</c2>");
            }
            ServiceLocator.register(new LuckPermsHook(consoleCommandIssuer));
        } else {
            consoleCommandIssuer.sendMessage(MessageKey.GENERAL__NO_HOOK, "{plugin}", "LuckPerms");
            if (ConfigUtil.getDebugOrFalse()) {
                consoleCommandIssuer.sendMessage("<c2>Running actions on post-login.</c2>");
            }
        }
    }

    private static final AtomicLong blockedVPNs = new AtomicLong(0L);

    private static final AtomicLong blockedMCLeaks = new AtomicLong(0L);

    public static void incrementBlockedVPNs() {
        blockedVPNs.getAndIncrement();
    }

    public static void incrementBlockedMCLeaks() {
        blockedMCLeaks.getAndIncrement();
    }

    private void unloadHooks() {
        Set<? extends PluginHook> hooks = ServiceLocator.remove(PluginHook.class);
        for (PluginHook hook : hooks) {
            hook.cancel();
        }
    }

    public void unloadServices() {
        VPNAPI api = VPNAPIProvider.getInstance();
        api.getEventBus().post(new APIDisableEventImpl(api));
        api.getEventBus().unregisterAll();
        APIRegistrationUtil.deregister();

        CachedConfig cachedConfig = ConfigUtil.getCachedConfig();
        for (MessagingService service : cachedConfig.getMessaging()) {
            service.close();
        }
        for (StorageService service : cachedConfig.getStorage()) {
            service.close();
        }

        Set<? extends MessagingHandler> messagingHandlers = ServiceLocator.remove(MessagingHandler.class);
    }

    public boolean loadYamlLanguageFile(@NotNull VelocityLocales locales, @NotNull File file, @NotNull Locale locale) throws IOException {
        ConfigurationLoader<CommentedConfigurationNode> fileLoader = YamlConfigurationLoader.builder().nodeStyle(NodeStyle.BLOCK).indent(2).file(file).build();
        return loadLanguage(locales, fileLoader.load(), locale);
    }

    private boolean loadLanguage(@NotNull VelocityLocales locales, @NotNull CommentedConfigurationNode config, @NotNull Locale locale) {
        boolean loaded = false;
        for (Map.Entry<Object, CommentedConfigurationNode> kvp : config.childrenMap().entrySet()) {
            for (Map.Entry<Object, CommentedConfigurationNode> kvp2 : kvp.getValue().childrenMap().entrySet()) {
                String value = kvp2.getValue().getString();
                if (value != null && !value.isEmpty()) {
                    Map<String, String> m = new HashMap<>();
                    m.put(kvp.getKey() + "." + kvp2.getKey(), value);
                    locales.addMessageStrings(locale, m);
                    loaded = true;
                }
            }
        }
        return loaded;
    }
}
