package me.egg82.antivpn.logging;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import flexjson.JSONSerializer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import me.egg82.antivpn.api.APIException;
import me.egg82.antivpn.api.platform.Platform;
import me.egg82.antivpn.config.ConfigUtil;
import me.egg82.antivpn.core.DoubleBuffer;
import me.egg82.antivpn.lang.I18NManager;
import me.egg82.antivpn.lang.Locales;
import me.egg82.antivpn.lang.MessageKey;
import me.egg82.antivpn.logging.models.GELFSubmissionModel;
import me.egg82.antivpn.utils.TimeUtil;
import me.egg82.antivpn.web.GZIPCompressor;
import me.egg82.antivpn.web.WebRequest;
import me.egg82.antivpn.web.transformers.InstantTransformer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GELFLogger {
    private static final Logger logger = LoggerFactory.getLogger(GELFLogger.class);

    private static final String GELF_ADDRESS = "http://raw.egg82.ninja:12201/gelf"; // TODO: HTTPS
    private static final ScheduledExecutorService workPool = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setNameFormat("AntiVPN-GELFLogger-%d").build());

    private static final UUID selfId = UUID.randomUUID();

    private static UUID serverId = null;
    private static String pluginVersion = null;
    private static String platform = null;
    private static String platformVersion = null;

    private static volatile boolean initialized = false;
    private static volatile boolean sendErrors = false;

    private static final DoubleBuffer<GELFSubmissionModel> queue = new DoubleBuffer<>();

    private GELFLogger() {
        workPool.scheduleWithFixedDelay(GELFLogger::sendModels, 1L, 2L, TimeUnit.SECONDS);
    }

    public static void setData(@NotNull UUID serverId, @NotNull String pluginVersion, @NotNull Platform.Type platform, @NotNull String platformVersion) {
        GELFLogger.serverId = serverId;
        GELFLogger.pluginVersion = pluginVersion;
        GELFLogger.platform = platform.getFriendlyName();
        GELFLogger.platformVersion = platformVersion;
    }

    public static void doSendErrors(boolean sendErrors) {
        GELFLogger.initialized = true;
        GELFLogger.sendErrors = sendErrors;
        if (!sendErrors) {
            queue.getReadBuffer().clear();
            queue.getWriteBuffer().clear();
        }
    }

    public static void close() {
        workPool.shutdown();
        try {
            if (!workPool.awaitTermination(4L, TimeUnit.SECONDS)) {
                workPool.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public static void exception(@NotNull Logger logger, @NotNull Throwable ex, @NotNull I18NManager localizationManager, @NotNull MessageKey key) { exception(logger, ex, localizationManager.getText(key), Locales.getUS().getText(key), localizationManager); }

    public static void exception(@NotNull Logger logger, @NotNull Throwable ex, @NotNull I18NManager localizationManager, @NotNull MessageKey key, String... placeholders) { exception(logger, ex, localizationManager.getText(key, placeholders), Locales.getUS().getText(key, placeholders), localizationManager); }

    public static void exception(@NotNull Logger logger, @NotNull Throwable ex, @NotNull I18NManager localizationManager, @NotNull MessageKey key, @NotNull Map<String, String> placeholders) { exception(logger, ex, localizationManager.getText(key, placeholders), Locales.getUS().getText(key, placeholders), localizationManager); }

    private static void exception(@NotNull Logger logger, @NotNull Throwable ex, @NotNull String localizedMessage, @NotNull String sendMessage, @Nullable I18NManager localizationManager) {
        if (localizationManager == null) {
            localizationManager = Locales.getUS();
        }

        Throwable oldEx = null;
        if (ex instanceof CompletionException || ex instanceof ExecutionException) {
            oldEx = ex;
            ex = ex.getCause();
        }
        while (ex instanceof CompletionException || ex instanceof ExecutionException) {
            ex = ex.getCause();
        }

        if (ex instanceof APIException) {
            String apiError = localizationManager.getText(MessageKey.ERROR__API_EXCEPTION, "{hard}", String.valueOf(((APIException) ex).isHard()), "{message}", ex.getLocalizedMessage());
            if (ConfigUtil.getDebugOrFalse()) {
                logger.error(apiError, oldEx != null ? oldEx : ex);
            } else {
                logger.error(apiError);
            }
        } else {
            if (ex instanceof CancellationException) {
                if (ConfigUtil.getDebugOrFalse()) {
                    logger.warn(localizedMessage, oldEx != null ? oldEx : ex);
                } else {
                    logger.warn(localizedMessage);
                }
            } else {
                if (ConfigUtil.getDebugOrFalse()) {
                    logger.error(localizedMessage, oldEx != null ? oldEx : ex);
                } else {
                    logger.error(localizedMessage);
                }
            }
        }

        if (serverId == null || pluginVersion == null || platform == null || platformVersion == null) {
            return;
        }

        if (!initialized || sendErrors) {
            queue.getWriteBuffer().add(getModel(sendMessage, oldEx != null ? oldEx : ex, ex instanceof CancellationException ? 4 : 3));
        }
    }

    public static void exception(@NotNull Logger logger, @NotNull Throwable ex, @NotNull String platform, @NotNull String platformVersion, boolean yesIAmSureIWantToForceThisToSend) {
        Throwable oldEx = null;
        if (ex instanceof CompletionException || ex instanceof ExecutionException) {
            oldEx = ex;
            ex = ex.getCause();
        }
        while (ex instanceof CompletionException || ex instanceof ExecutionException) {
            ex = ex.getCause();
        }

        if (ex instanceof APIException) {
            String apiError = "[Hard: " + ((APIException) ex).isHard() + "] " + ex.getLocalizedMessage();
            if (ConfigUtil.getDebugOrFalse()) {
                logger.error(apiError, oldEx != null ? oldEx : ex);
            } else {
                logger.error(apiError);
            }
        } else {
            if (ex instanceof CancellationException) {
                if (ConfigUtil.getDebugOrFalse()) {
                    logger.warn(ex.getLocalizedMessage(), oldEx != null ? oldEx : ex);
                } else {
                    logger.warn(ex.getLocalizedMessage());
                }
            } else {
                if (ConfigUtil.getDebugOrFalse()) {
                    logger.error(ex.getLocalizedMessage(), oldEx != null ? oldEx : ex);
                } else {
                    logger.error(ex.getLocalizedMessage());
                }
            }
        }

        if (!yesIAmSureIWantToForceThisToSend) {
            return;
        }

        sendModel(getExplicitModel(ex, ex instanceof CancellationException ? 4 : 3, platform, platformVersion));
    }

    public static void exception(@NotNull Logger logger, @NotNull Throwable ex) { exception(logger, ex, null); }

    public static void exception(@NotNull Logger logger, @NotNull Throwable ex, @Nullable I18NManager consoleLocalizationManager) {
        if (consoleLocalizationManager == null) {
            consoleLocalizationManager = Locales.getUS();
        }

        Throwable oldEx = null;
        if (ex instanceof CompletionException || ex instanceof ExecutionException) {
            oldEx = ex;
            ex = ex.getCause();
        }
        while (ex instanceof CompletionException || ex instanceof ExecutionException) {
            ex = ex.getCause();
        }

        if (ex instanceof APIException) {
            String apiError = consoleLocalizationManager.getText(MessageKey.ERROR__API_EXCEPTION, "{hard}", String.valueOf(((APIException) ex).isHard()), "{message}", ex.getLocalizedMessage());
            if (ConfigUtil.getDebugOrFalse()) {
                logger.error(apiError, oldEx != null ? oldEx : ex);
            } else {
                logger.error(apiError);
            }
        } else {
            if (ex instanceof CancellationException) {
                if (ConfigUtil.getDebugOrFalse()) {
                    logger.warn(ex.getLocalizedMessage(), oldEx != null ? oldEx : ex);
                } else {
                    logger.warn(ex.getLocalizedMessage());
                }
            } else {
                if (ConfigUtil.getDebugOrFalse()) {
                    logger.error(ex.getLocalizedMessage(), oldEx != null ? oldEx : ex);
                } else {
                    logger.error(ex.getLocalizedMessage());
                }
            }
        }

        if (serverId == null || pluginVersion == null || platform == null || platformVersion == null) {
            return;
        }

        if (!initialized || sendErrors) {
            queue.getWriteBuffer().add(getModel(oldEx != null ? oldEx : ex, ex instanceof CancellationException ? 4 : 3));
        }
    }

    public static void error(@NotNull Logger logger, @NotNull I18NManager localizationManager, @NotNull MessageKey key) { error(logger, localizationManager.getText(key), Locales.getUS().getText(key)); }

    public static void error(@NotNull Logger logger, @NotNull I18NManager localizationManager, @NotNull MessageKey key, String... placeholders) { error(logger, localizationManager.getText(key, placeholders), Locales.getUS().getText(key, placeholders)); }

    public static void error(@NotNull Logger logger, @NotNull I18NManager localizationManager, @NotNull MessageKey key, @NotNull Map<String, String> placeholders) { error(logger, localizationManager.getText(key, placeholders), Locales.getUS().getText(key, placeholders)); }

    public static void error(@NotNull Logger logger, @NotNull String message, @NotNull String platform, @NotNull String platformVersion, boolean yesIAmSureIWantToForceThisToSend) {
        logger.error(message);

        if (!yesIAmSureIWantToForceThisToSend) {
            return;
        }

        GELFSubmissionModel model = getExplicitModel(message, platform, platformVersion);
        model.setLevel(3);
        sendModel(model);
    }

    private static void error(@NotNull Logger logger, @NotNull String localizedMessage, @NotNull String sendMessage) {
        logger.error(localizedMessage);

        if (serverId == null || pluginVersion == null || platform == null || platformVersion == null) {
            return;
        }

        if (!initialized || sendErrors) {
            GELFSubmissionModel model = getModel(sendMessage);
            model.setLevel(3);
            queue.getWriteBuffer().add(model);
        }
    }

    public static void warn(@NotNull Logger logger, @NotNull I18NManager localizationManager, @NotNull MessageKey key) { warn(logger, localizationManager.getText(key), Locales.getUS().getText(key)); }

    public static void warn(@NotNull Logger logger, @NotNull I18NManager localizationManager, @NotNull MessageKey key, String... placeholders) { warn(logger, localizationManager.getText(key, placeholders), Locales.getUS().getText(key, placeholders)); }

    public static void warn(@NotNull Logger logger, @NotNull I18NManager localizationManager, @NotNull MessageKey key, @NotNull Map<String, String> placeholders) { warn(logger, localizationManager.getText(key, placeholders), Locales.getUS().getText(key, placeholders)); }

    public static void warn(@NotNull Logger logger, @NotNull String message, @NotNull String platform, @NotNull String platformVersion, boolean yesIAmSureIWantToForceThisToSend) {
        logger.warn(message);

        if (!yesIAmSureIWantToForceThisToSend) {
            return;
        }

        GELFSubmissionModel model = getExplicitModel(message, platform, platformVersion);
        model.setLevel(4);
        sendModel(model);
    }

    private static void warn(@NotNull Logger logger, @NotNull String localizedMessage, @NotNull String sendMessage) {
        logger.warn(localizedMessage);

        if (serverId == null || pluginVersion == null || platform == null || platformVersion == null) {
            return;
        }

        if (!initialized || sendErrors) {
            GELFSubmissionModel model = getModel(sendMessage);
            model.setLevel(4);
            queue.getWriteBuffer().add(model);
        }
    }

    public static void info(@NotNull Logger logger, @NotNull I18NManager localizationManager, @NotNull MessageKey key) { info(logger, localizationManager.getText(key), Locales.getUS().getText(key)); }

    public static void info(@NotNull Logger logger, @NotNull I18NManager localizationManager, @NotNull MessageKey key, String... placeholders) { info(logger, localizationManager.getText(key, placeholders), Locales.getUS().getText(key, placeholders)); }

    public static void info(@NotNull Logger logger, @NotNull I18NManager localizationManager, @NotNull MessageKey key, @NotNull Map<String, String> placeholders) { info(logger, localizationManager.getText(key, placeholders), Locales.getUS().getText(key, placeholders)); }

    public static void info(@NotNull Logger logger, @NotNull String message, @NotNull String platform, @NotNull String platformVersion, boolean yesIAmSureIWantToForceThisToSend) {
        logger.info(message);

        if (!yesIAmSureIWantToForceThisToSend) {
            return;
        }

        GELFSubmissionModel model = getExplicitModel(message, platform, platformVersion);
        model.setLevel(6);
        sendModel(model);
    }

    private static void info(@NotNull Logger logger, @NotNull String localizedMessage, @NotNull String sendMessage) {
        logger.info(localizedMessage);

        if (serverId == null || pluginVersion == null || platform == null || platformVersion == null) {
            return;
        }

        if (!initialized || sendErrors) {
            GELFSubmissionModel model = getModel(sendMessage);
            model.setLevel(6);
            queue.getWriteBuffer().add(model);
        }
    }

    public static void debug(@NotNull Logger logger, @NotNull I18NManager localizationManager, @NotNull MessageKey key) { debug(logger, localizationManager.getText(key), Locales.getUS().getText(key)); }

    public static void debug(@NotNull Logger logger, @NotNull I18NManager localizationManager, @NotNull MessageKey key, String... placeholders) { debug(logger, localizationManager.getText(key, placeholders), Locales.getUS().getText(key, placeholders)); }

    public static void debug(@NotNull Logger logger, @NotNull I18NManager localizationManager, @NotNull MessageKey key, @NotNull Map<String, String> placeholders) { debug(logger, localizationManager.getText(key, placeholders), Locales.getUS().getText(key, placeholders)); }

    public static void debug(@NotNull Logger logger, @NotNull String message, @NotNull String platform, @NotNull String platformVersion, boolean yesIAmSureIWantToForceThisToSend) {
        logger.debug(message);

        if (!yesIAmSureIWantToForceThisToSend) {
            return;
        }

        GELFSubmissionModel model = getExplicitModel(message, platform, platformVersion);
        model.setLevel(7);
        sendModel(model);
    }

    private static void debug(@NotNull Logger logger, @NotNull String localizedMessage, @NotNull String sendMessage) {
        logger.debug(localizedMessage);

        if (serverId == null || pluginVersion == null || platform == null || platformVersion == null) {
            return;
        }

        if (!initialized || sendErrors) {
            GELFSubmissionModel model = getModel(sendMessage);
            model.setLevel(7);
            queue.getWriteBuffer().add(model);
        }
    }

    private static GELFSubmissionModel getModel(@NotNull String message, @NotNull Throwable ex, int level) {
        GELFSubmissionModel retVal = new GELFSubmissionModel();
        retVal.setHost(serverId);
        retVal.setShortMessage(message);
        try (StringWriter builder = new StringWriter(); PrintWriter writer = new PrintWriter(builder)) {
            ex.printStackTrace(writer);
            retVal.setFullMessage(builder.toString());
        } catch (IOException ex2) {
            logger.error(ex2.getLocalizedMessage(), ex2);
        }
        retVal.setLevel(level);
        retVal.setPluginVersion(pluginVersion);
        retVal.setPlatform(platform);
        retVal.setPlatformVersion(platformVersion);
        return retVal;
    }

    private static GELFSubmissionModel getExplicitModel(@NotNull String message, @NotNull Throwable ex, int level, @NotNull String platform, @NotNull String platformVersion) {
        GELFSubmissionModel retVal = new GELFSubmissionModel();
        retVal.setHost(selfId);
        retVal.setShortMessage(message);
        try (StringWriter builder = new StringWriter(); PrintWriter writer = new PrintWriter(builder)) {
            ex.printStackTrace(writer);
            retVal.setFullMessage(builder.toString());
        } catch (IOException ex2) {
            logger.error(ex2.getLocalizedMessage(), ex2);
        }
        retVal.setLevel(level);
        retVal.setPluginVersion("unknown");
        retVal.setPlatform(platform);
        retVal.setPlatformVersion(platformVersion);
        return retVal;
    }

    private static GELFSubmissionModel getModel(@NotNull Throwable ex, int level) {
        GELFSubmissionModel retVal = new GELFSubmissionModel();
        retVal.setHost(serverId);
        retVal.setShortMessage(ex.getMessage());
        try (StringWriter builder = new StringWriter(); PrintWriter writer = new PrintWriter(builder)) {
            ex.printStackTrace(writer);
            retVal.setFullMessage(builder.toString());
        } catch (IOException ex2) {
            logger.error(ex2.getLocalizedMessage(), ex2);
        }
        retVal.setLevel(level);
        retVal.setPluginVersion(pluginVersion);
        retVal.setPlatform(platform);
        retVal.setPlatformVersion(platformVersion);
        return retVal;
    }

    private static GELFSubmissionModel getExplicitModel(@NotNull Throwable ex, int level, @NotNull String platform, @NotNull String platformVersion) {
        GELFSubmissionModel retVal = new GELFSubmissionModel();
        retVal.setHost(selfId);
        retVal.setShortMessage(ex.getMessage());
        try (StringWriter builder = new StringWriter(); PrintWriter writer = new PrintWriter(builder)) {
            ex.printStackTrace(writer);
            retVal.setFullMessage(builder.toString());
        } catch (IOException ex2) {
            logger.error(ex2.getLocalizedMessage(), ex2);
        }
        retVal.setLevel(level);
        retVal.setPluginVersion("unknown");
        retVal.setPlatform(platform);
        retVal.setPlatformVersion(platformVersion);
        return retVal;
    }

    private static GELFSubmissionModel getModel(@NotNull String message) {
        GELFSubmissionModel retVal = new GELFSubmissionModel();
        retVal.setHost(serverId);
        retVal.setShortMessage(message);
        retVal.setPluginVersion(pluginVersion);
        retVal.setPlatform(platform);
        retVal.setPlatformVersion(platformVersion);
        return retVal;
    }

    private static GELFSubmissionModel getExplicitModel(@NotNull String message, @NotNull String platform, @NotNull String platformVersion) {
        GELFSubmissionModel retVal = new GELFSubmissionModel();
        retVal.setHost(selfId);
        retVal.setShortMessage(message);
        retVal.setPluginVersion("unknown");
        retVal.setPlatform(platform);
        retVal.setPlatformVersion(platformVersion);
        return retVal;
    }

    private static void sendModels() {
        if (!initialized || !sendErrors) {
            return;
        }

        queue.swapBuffers();

        GELFSubmissionModel model;
        while ((model = queue.getReadBuffer().poll()) != null) {
            sendModel(model);
        }
    }

    private static void sendModel(@NotNull GELFSubmissionModel model) {
        JSONSerializer modelSerializer = new JSONSerializer();
        modelSerializer.prettyPrint(false);
        modelSerializer.transform(new InstantTransformer(), Instant.class);

        try {
            WebRequest request = WebRequest.builder(new URL(GELF_ADDRESS))
                .method(WebRequest.RequestMethod.POST)
                .timeout(new TimeUtil.Time(5000L, TimeUnit.MILLISECONDS))
                .userAgent("egg82/GELFLogger")
                .header("Content-Type", "application/json")
                .outputData(GZIPCompressor.compress(modelSerializer.exclude("*.class").deepSerialize(model).getBytes(StandardCharsets.UTF_8)))
                .build();
            HttpURLConnection conn = request.getConnection();
            if (conn.getResponseCode() != 204) {
                throw new IOException("Did not get valid response from server (response code " + conn.getResponseCode() + "): \"" + WebRequest.getString(conn) + "\""); // TODO: Localization
            }
        } catch (IOException ex) {
            logger.error(ex.getLocalizedMessage(), ex);
        }
    }
}
