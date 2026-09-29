package me.ray.rpermadeath.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.ray.rpermadeath.RPermadeath;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

public class DatabaseManager {

    private final RPermadeath plugin;
    private HikariDataSource dataSource;

    public DatabaseManager(RPermadeath plugin) {
        this.plugin = plugin;
    }

    public boolean connect() {
        try {
            if (dataSource != null && !dataSource.isClosed()) {
                return true;
            }

            File dbFile = new File(plugin.getDataFolder(), "database.db");
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            config.setDriverClassName("org.sqlite.JDBC");
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(1);
            config.setIdleTimeout(60000);
            config.setMaxLifetime(0);
            config.setConnectionTimeout(30000);
            config.setPoolName("MidgardPermaDeath-SQLite");
            config.setConnectionInitSql("PRAGMA busy_timeout=5000");

            dataSource = new HikariDataSource(config);

            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                createTables(stmt);
            }

            plugin.getLogger().info("Banco de dados SQLite inicializado com sucesso! (" + dbFile.getName() + ")");
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Nao foi possivel inicializar o banco de dados SQLite!", e);
            return false;
        }
    }

    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Banco de dados SQLite encerrado.");
        }
    }

    public boolean reconnect() {
        plugin.getLogger().info("Reconectando ao banco de dados...");
        disconnect();
        dataSource = null;
        return connect();
    }

    /**
     * Retorna uma conexao do pool. O chamador DEVE fechar a conexao
     * usando try-with-resources para devolve-la ao pool.
     */
    public Connection getConnection() {
        if (dataSource == null || dataSource.isClosed()) {
            if (!connect()) {
                plugin.getLogger().severe("Falha ao reconectar ao banco de dados SQLite.");
                return null;
            }
        }
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Erro ao obter conexao SQLite", e);
            return null;
        }
    }

    /**
     * Verifica se o pool de conexoes esta disponivel sem obter uma conexao.
     */
    public boolean isAvailable() {
        return dataSource != null && !dataSource.isClosed();
    }

    private void createTables(Statement stmt) throws SQLException {
        String sqlReplays = "CREATE TABLE IF NOT EXISTS midgard_replays (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "player_uuid TEXT NOT NULL, " +
                "death_time INTEGER NOT NULL, " +
                "filename TEXT NOT NULL, " +
                "file_size INTEGER DEFAULT 0, " +
                "created_at TEXT DEFAULT CURRENT_TIMESTAMP" +
                ");";

        String sqlDeaths = "CREATE TABLE IF NOT EXISTS midgard_deaths (" +
                "player_uuid TEXT PRIMARY KEY, " +
                "player_name TEXT, " +
                "death_time TEXT, " +
                "killer_uuid TEXT, " +
                "killer_name TEXT, " +
                "world TEXT, " +
                "x REAL, " +
                "y REAL, " +
                "z REAL, " +
                "yaw REAL, " +
                "pitch REAL, " +
                "death_state TEXT DEFAULT 'VALHALLA'" +
                ");";

        String sqlBounties = "CREATE TABLE IF NOT EXISTS midgard_bounties (" +
                "player_uuid TEXT PRIMARY KEY, " +
                "bounty REAL NOT NULL DEFAULT 0" +
                ");";

        stmt.execute(sqlReplays);
        stmt.execute(sqlDeaths);
        stmt.execute(sqlBounties);

        stmt.execute("CREATE INDEX IF NOT EXISTS idx_replays_player ON midgard_replays(player_uuid)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_replays_time ON midgard_replays(death_time)");
    }

    public void removeWhitelist(java.util.UUID uuid) {
        removeWhitelist(uuid, null);
    }

    public void removeWhitelist(java.util.UUID uuid, String knownName) {
        final String uuidStr = uuid.toString();
        final String playerName = knownName != null ? knownName : "";

        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int totalRows = 0;

            try (Connection conn = getConnection()) {
                if (conn == null) {
                    plugin.getLogger().warning("Nao foi possivel remover da Whitelist: Conexao indisponivel.");
                    return;
                }

                try {
                    String sqlBase = "DELETE FROM midgard_whitelist WHERE " +
                                     "uuid = ? " +
                                     "OR (nickname IS NOT NULL AND LOWER(nickname) = LOWER(?))";
                    try (java.sql.PreparedStatement pstmt = conn.prepareStatement(sqlBase)) {
                        pstmt.setString(1, uuidStr);
                        pstmt.setString(2, playerName);
                        totalRows += pstmt.executeUpdate();
                    }
                } catch (SQLException e) {
                    plugin.getLogger().fine("Tabela midgard_whitelist nao encontrada: " + e.getMessage());
                }

                try {
                    String sqlLinks = "DELETE FROM midgard_whitelist WHERE " +
                                      "discord_id IN (SELECT discord_id FROM midgard_links WHERE uuid = ?)";
                    try (java.sql.PreparedStatement pstmt = conn.prepareStatement(sqlLinks)) {
                        pstmt.setString(1, uuidStr);
                        totalRows += pstmt.executeUpdate();
                    }
                } catch (SQLException e) {
                    plugin.getLogger().fine("Tabela midgard_links nao encontrada: " + e.getMessage());
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Erro ao fechar conexao apos removeWhitelist", e);
            }

            if (totalRows > 0) {
                plugin.getLogger().info("Jogador removido da Whitelist. UUID: " + uuidStr + " Nick: " + playerName + " Registros: " + totalRows);
            }
        });
    }
}
