package com.f9n.altibase.schemadiff;

import com.f9n.altibase.schemadiff.model.DiffResult;
import com.f9n.altibase.schemadiff.model.SchemaSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;

@Command(
        name = "altibase-schema-diff",
        mixinStandardHelpOptions = true,
        description = "Compare schemas between two Altibase servers and detect drift.",
        versionProvider = Main.VersionProvider.class
)
public class Main implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @Option(names = {"--source-server"}, description = "Source server hostname/IP", defaultValue = "${ALTIBASE_SOURCE_SERVER:-127.0.0.1}")
    private String sourceServer;

    @Option(names = {"--source-port"}, description = "Source server port", defaultValue = "${ALTIBASE_SOURCE_PORT:-20300}")
    private int sourcePort;

    @Option(names = {"--source-user"}, description = "Source server username", defaultValue = "${ALTIBASE_SOURCE_USER:-sys}")
    private String sourceUser;

    @Option(names = {"--source-password"}, description = "Source server password", defaultValue = "${ALTIBASE_SOURCE_PASSWORD:-manager}")
    private String sourcePassword;

    @Option(names = {"--source-database"}, description = "Source database name", defaultValue = "${ALTIBASE_SOURCE_DATABASE:-mydb}")
    private String sourceDatabase;

    @Option(names = {"--target-server"}, description = "Target server hostname/IP", defaultValue = "${ALTIBASE_TARGET_SERVER:-127.0.0.1}")
    private String targetServer;

    @Option(names = {"--target-port"}, description = "Target server port", defaultValue = "${ALTIBASE_TARGET_PORT:-20300}")
    private int targetPort;

    @Option(names = {"--target-user"}, description = "Target server username", defaultValue = "${ALTIBASE_TARGET_USER:-sys}")
    private String targetUser;

    @Option(names = {"--target-password"}, description = "Target server password", defaultValue = "${ALTIBASE_TARGET_PASSWORD:-manager}")
    private String targetPassword;

    @Option(names = {"--target-database"}, description = "Target database name", defaultValue = "${ALTIBASE_TARGET_DATABASE:-mydb}")
    private String targetDatabase;

    @Option(names = {"--schemas"}, description = "Comma-separated list of schemas to compare (default: all non-system)", split = ",")
    private Set<String> schemas = Set.of();

    @Option(names = {"--connect-timeout"}, description = "Connection timeout in seconds", defaultValue = "${ALTIBASE_CONNECT_TIMEOUT:-10}")
    private int connectTimeout;

    @Option(names = {"--cache-dir"}, description = "Directory for caching schema snapshots")
    private Path cacheDir;

    @Option(names = {"--cache-ttl"}, description = "Cache TTL in seconds (default: 3600)", defaultValue = "3600")
    private long cacheTtlSeconds;

    @Option(names = {"--no-cache"}, description = "Disable caching completely (no read, no write)", defaultValue = "false")
    private boolean noCache;

    @Option(names = {"--refresh-cache"}, description = "Ignore existing cache but write new results", defaultValue = "false")
    private boolean refreshCache;

    @Option(names = {"--no-color"}, description = "Disable colored output", defaultValue = "false")
    private boolean noColor;

    @Option(names = {"--debug"}, description = "Enable debug logging", defaultValue = "false")
    private boolean debug;

    @Option(names = {"--trace"}, description = "Enable trace logging (very verbose, includes column details)", defaultValue = "false")
    private boolean trace;

    @Override
    public Integer call() {
        if (trace) {
            LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
            ctx.getLogger("com.f9n.altibase.schemadiff").setLevel(Level.TRACE);
        } else if (debug) {
            LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
            ctx.getLogger("com.f9n.altibase.schemadiff").setLevel(Level.DEBUG);
        }

        ConnectionConfig sourceConfig = new ConnectionConfig(sourceServer, sourcePort, sourceUser, sourcePassword, sourceDatabase, connectTimeout);
        ConnectionConfig targetConfig = new ConnectionConfig(targetServer, targetPort, targetUser, targetPassword, targetDatabase, connectTimeout);

        Set<String> schemaFilter = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        schemaFilter.addAll(schemas);

        SchemaCache cache = resolveCache();

        SchemaSnapshot sourceSnapshot = loadOrExtract(sourceConfig, schemaFilter, cache);
        if (sourceSnapshot == null) return 1;

        SchemaSnapshot targetSnapshot = loadOrExtract(targetConfig, schemaFilter, cache);
        if (targetSnapshot == null) return 1;

        DiffResult result = new SchemaDiffer().diff(sourceSnapshot, targetSnapshot);

        boolean useColor = !noColor && System.console() != null;
        new DiffPrinter(System.out, useColor).print(result);

        return result.hasDifferences() ? 2 : 0;
    }

    private SchemaSnapshot loadOrExtract(ConnectionConfig config, Set<String> schemaFilter, SchemaCache cache) {
        if (cache != null && !refreshCache) {
            SchemaSnapshot cached = cache.load(config);
            if (cached != null) return cached;
        }

        SchemaSnapshot snapshot = null;
        try (Connection conn = connect(config)) {
            conn.setReadOnly(true);
            try (var stmt = conn.createStatement()) {
                stmt.execute("exec set_client_info('altibase-schema-diff')");
            } catch (SQLException ignored) {}

            SchemaExtractor extractor = new SchemaExtractor(conn, config, schemaFilter);
            snapshot = extractor.extract();
        } catch (SQLException e) {
            log.error("Failed to extract schema from {}: {}", config.label(), e.getMessage());
            System.err.println("ERROR: Cannot connect to " + config.label() + ": " + e.getMessage());
            return null;
        }

        if (cache != null && snapshot != null) {
            cache.save(config, snapshot);
        }
        return snapshot;
    }

    private Connection connect(ConnectionConfig config) throws SQLException {
        log.info("Connecting to {}", config.label());

        Properties props = new Properties();
        props.setProperty("user", config.user());
        props.setProperty("password", config.password());

        final SQLException[] holder = new SQLException[1];
        final Connection[] result = new Connection[1];

        Thread connectThread = new Thread(() -> {
            try {
                result[0] = DriverManager.getConnection(config.jdbcUrl(), props);
            } catch (SQLException e) {
                holder[0] = e;
            }
        }, "altibase-connect-" + config.server());
        connectThread.setDaemon(true);
        connectThread.start();

        try {
            connectThread.join(config.connectTimeoutSeconds() * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Connection interrupted to " + config.label(), e);
        }

        if (holder[0] != null) throw holder[0];
        if (result[0] != null) {
            log.info("Connected to {} (read-only)", config.label());
            return result[0];
        }
        throw new SQLException("Connection timeout after " + config.connectTimeoutSeconds() + "s to " + config.label());
    }

    private SchemaCache resolveCache() {
        if (noCache) return null;
        Path dir = cacheDir;
        if (dir == null) {
            dir = Path.of(System.getProperty("user.home"), ".altibase-schema-diff", "cache");
        }
        return new SchemaCache(dir, Duration.ofSeconds(cacheTtlSeconds));
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String v = Main.class.getPackage().getImplementationVersion();
            return new String[]{"altibase-schema-diff " + (v != null ? v : "dev")};
        }
    }
}
