package me.ray.rpermadeath.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.ray.rpermadeath.RPermadeath;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class ProxyWhitelistMySqlManager {

    private static final Pattern HOST_PATTERN = Pattern.compile("^[a-zA-Z0-9.-]+$");
    private static final Pattern DB_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    private final RPermadeath plugin;
    private HikariDataSource dataSource;
    private boolean enabled;
    private String host;
    private String port;
    private String database;
    private String user;
    private String password;
    private boolean useSsl;

    public ProxyWhitelistMySqlManager(RPermadeath plugin) {
        this.plugin = plugin;
    }

    public synchronized void reload() {
        loadConfig();

        if (!enabled) {
            disconnect();
            plugin.getLogger().info("Integracao MySQL da whitelist do proxy desativada.");
            return;
        }

        reconnect();
    }

    public synchronized void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Conexao MySQL da whitelist do proxy encerrada.");
        }
        dataSource = null;
    }

    public void removeWhitelist(UUID uuid, String playerName) {
        if (!enabled) {
            return;
        }

        final String safePlayerName = playerName == null ? "" : playerName;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = getConnection()) {
                if (conn == null) {
                    plugin.getLogger().warning("Nao foi possivel remover a whitelist do proxy: conexao MySQL indisponivel.");
                    return;
                }

                int deletedRows = 0;

                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM midgard_whitelist WHERE uuid = ? OR (nickname IS NOT NULL AND LOWER(nickname) = LOWER(?))")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, safePlayerName);
                    deletedRows += ps.executeUpdate();
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM midgard_whitelist WHERE discord_id IN (SELECT discord_id FROM midgard_links WHERE uuid = ?)")) {
                    ps.setString(1, uuid.toString());
                    deletedRows += ps.executeUpdate();
                }

                if (deletedRows > 0) {
                    plugin.getLogger().info("Whitelist removida no MySQL do proxy para " + safePlayerName + " (" + uuid + "). Registros: " + deletedRows);
                } else {
                    plugin.getLogger().info("Nenhum registro de whitelist encontrado no MySQL do proxy para " + safePlayerName + " (" + uuid + ").");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erro ao remover whitelist no MySQL do proxy para " + safePlayerName, e);
            }
        });
    }

    private synchronized Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            connect();
        }

        if (dataSource == null || dataSource.isClosed()) {
            return null;
        }

        return dataSource.getConnection();
    }

    private synchronized void reconnect() {
        disconnect();
        connect();
    }

    private synchronized void connect() {
        if (!enabled) {
            return;
        }

        if (dataSource != null && !dataSource.isClosed()) {
            return;
        }

        if (!isValidConfig()) {
            plugin.getLogger().warning("Integracao MySQL da whitelist do proxy desativada por configuracao invalida.");
            return;
        }

        HikariConfig config = new HikariConfig();
        config.setPoolName("MidgardPermaDeath-WhitelistMySQL");
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(15000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setInitializationFailTimeout(-1);

        StringBuilder jdbcUrl = new StringBuilder()
                .append("jdbc:mysql://")
                .append(host)
                .append(":")
                .append(port)
                .append("/")
                .append(database)
                .append("?useSSL=")
                .append(useSsl)
                .append("&characterEncoding=UTF-8")
                .append("&socketTimeout=30000")
                .append("&connectTimeout=15000")
                .append("&allowLoadLocalInfile=false")
                .append("&allowUrlInLocalInfile=false")
                .append("&autoDeserialize=false");

        if (useSsl) {
            jdbcUrl.append("&requireSSL=true&verifyServerCertificate=true");
        } else {
            jdbcUrl.append("&allowPublicKeyRetrieval=true");
        }

        config.setJdbcUrl(jdbcUrl.toString());
        config.setUsername(user);
        config.setPassword(password);

        try {
            dataSource = new HikariDataSource(config);
            plugin.getLogger().info("Conexao MySQL da whitelist do proxy inicializada.");
        } catch (Exception e) {
            dataSource = null;
            plugin.getLogger().log(Level.SEVERE, "Nao foi possivel conectar ao MySQL da whitelist do proxy.", e);
        }
    }

    private void loadConfig() {
        enabled = plugin.getConfig().getBoolean("proxy-whitelist-mysql.enabled", false);
        host = plugin.getConfig().getString("proxy-whitelist-mysql.host", "");
        port = plugin.getConfig().getString("proxy-whitelist-mysql.port", "3306");
        database = plugin.getConfig().getString("proxy-whitelist-mysql.database", "");
        user = plugin.getConfig().getString("proxy-whitelist-mysql.user", "");
        password = plugin.getConfig().getString("proxy-whitelist-mysql.password", "");
        useSsl = plugin.getConfig().getBoolean("proxy-whitelist-mysql.use-ssl", false);
    }

    private boolean isValidConfig() {
        if (host == null || host.isBlank() || !HOST_PATTERN.matcher(host).matches()) {
            plugin.getLogger().warning("Host do MySQL da whitelist do proxy invalido: " + host);
            return false;
        }

        if (database == null || database.isBlank() || !DB_PATTERN.matcher(database).matches()) {
            plugin.getLogger().warning("Database do MySQL da whitelist do proxy invalido: " + database);
            return false;
        }

        if (user == null || user.isBlank()) {
            plugin.getLogger().warning("Usuario do MySQL da whitelist do proxy nao configurado.");
            return false;
        }

        try {
            int portNumber = Integer.parseInt(port);
            if (portNumber < 1 || portNumber > 65535) {
                plugin.getLogger().warning("Porta do MySQL da whitelist do proxy invalida: " + port);
                return false;
            }
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("Porta do MySQL da whitelist do proxy nao numerica: " + port);
            return false;
        }

        return true;
    }
}
