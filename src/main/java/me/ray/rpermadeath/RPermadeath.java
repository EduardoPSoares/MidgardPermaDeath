package me.ray.rpermadeath;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import me.ray.rpermadeath.config.MessageManager;
import me.ray.rpermadeath.commands.MDeathCommand;
import me.ray.rpermadeath.commands.ReplayToggleCommand;
import me.ray.rpermadeath.listeners.DeathListener;
import me.ray.rpermadeath.listeners.HeadInteractListener;
import me.ray.rpermadeath.listeners.MenuListener;
import me.ray.rpermadeath.listeners.ReviveListener;
import me.ray.rpermadeath.listeners.SpectatorListener;
import me.ray.rpermadeath.managers.BountyManager;
import me.ray.rpermadeath.managers.DeathManager;
import me.ray.rpermadeath.managers.LuckPermsManager;
import me.ray.rpermadeath.managers.MenuManager;
import me.ray.rpermadeath.managers.WorldGuardManager;
import me.ray.rpermadeath.database.DatabaseManager;
import me.ray.rpermadeath.database.ProxyWhitelistMySqlManager;
import me.ray.rpermadeath.replay.ReplayListener;
import me.ray.rpermadeath.replay.ReplayManager;
import me.ray.rpermadeath.replay.audio.ReplayAudioManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class RPermadeath extends JavaPlugin {

    private static RPermadeath instance;
    private MessageManager messageManager;
    private DatabaseManager databaseManager;
    private ProxyWhitelistMySqlManager proxyWhitelistMySqlManager;
    private DeathManager deathManager;
    private LuckPermsManager luckPermsManager;
    private BountyManager bountyManager;
    private ReplayManager replayManager;
    private ReplayListener replayListener;
    private ReplayAudioManager replayAudioManager;
    private me.ray.rpermadeath.replay.ReplayWorldManager replayWorldManager;
    private MenuManager menuManager;
    private WorldGuardManager worldGuardManager;
    private me.ray.rpermadeath.managers.GhostManager ghostManager;
    private me.ray.rpermadeath.utils.DiscordWebhook discordWebhook;
    private me.ray.rpermadeath.placeholders.MidgardExpansion placeholderExpansion;
    private DeathListener deathListener;
    private SpectatorListener spectatorListener;
    private static net.milkbowl.vault.economy.Economy econ = null;

    @Override
    public void onEnable() {
        try {
            instance = this;
            saveDefaultConfig();
            addConfigDefaults();
            this.messageManager = new MessageManager(this);
            this.messageManager.load();
            
            // Inicializa Webhook
            this.discordWebhook = new me.ray.rpermadeath.utils.DiscordWebhook(this);
            
            // Registrar canal de mensagens para comunicação com Velocity
            this.getServer().getMessenger().registerOutgoingPluginChannel(this, "rpermadeath:death");

            this.luckPermsManager = new LuckPermsManager(this);
            if (!luckPermsManager.isAvailable()) {
                getLogger().warning("LuckPerms nao encontrado. Funcionalidades de permissao serao executadas via comandos ou desativadas.");
            }

            if (!setupEconomy()) {
                getLogger().warning("Vault não encontrado ou nenhum plugin de economia instalado! O sistema de recompensas (Bounties) será desativado.");
            }

            // Inicializa Banco de Dados (SQLite local)
            this.databaseManager = new DatabaseManager(this);
            this.databaseManager.connect();
            this.proxyWhitelistMySqlManager = new ProxyWhitelistMySqlManager(this);
            this.proxyWhitelistMySqlManager.reload();

            this.deathManager = new DeathManager(this, databaseManager);
            this.deathManager.load();
            
            this.bountyManager = new BountyManager(this, databaseManager);
            this.worldGuardManager = new WorldGuardManager(this);
            this.ghostManager = new me.ray.rpermadeath.managers.GhostManager(this, deathManager, luckPermsManager);


            // Inicializa sistema de replay
            this.replayWorldManager = new me.ray.rpermadeath.replay.ReplayWorldManager(this);
            this.replayAudioManager = new ReplayAudioManager(this);
            this.replayManager = new ReplayManager(this, databaseManager);
            this.replayListener = new ReplayListener(this);

            // Registra addons no Simple Voice Chat via Bukkit Services API
            org.bukkit.plugin.Plugin vcPlugin = getServer().getPluginManager().getPlugin("MidgardVoice");
            if (vcPlugin == null) vcPlugin = getServer().getPluginManager().getPlugin("voicechat");
            if (vcPlugin != null && vcPlugin.isEnabled()) {
                try {
                    int registeredCount = 0;
                    de.maxhenkel.voicechat.api.BukkitVoicechatService vcService =
                            getServer().getServicesManager().load(de.maxhenkel.voicechat.api.BukkitVoicechatService.class);
                    
                    if (vcService == null) {
                        // Fallback: acesso direto via reflection (contorna incompatibilidade de classloader)
                        getLogger().info("[VoiceChat] ServicesManager não retornou BukkitVoicechatService, tentando reflection...");
                        try {
                            Class<?> voicechatClass = vcPlugin.getClass().getClassLoader()
                                    .loadClass("de.maxhenkel.voicechat.Voicechat");
                            java.lang.reflect.Field apiServiceField = voicechatClass.getDeclaredField("apiService");
                            apiServiceField.setAccessible(true);
                            Object apiService = apiServiceField.get(null);
                            if (apiService instanceof de.maxhenkel.voicechat.api.BukkitVoicechatService) {
                                vcService = (de.maxhenkel.voicechat.api.BukkitVoicechatService) apiService;
                                getLogger().info("[VoiceChat] Reflection fallback bem-sucedido.");
                            }
                        } catch (Exception reflectEx) {
                            getLogger().warning("[VoiceChat] Reflection fallback falhou: " + reflectEx.getMessage());
                        }
                    }
                    
                    if (vcService != null) {
                        // 1. Registra addon de replay de áudio
                        vcService.registerPlugin(new me.ray.rpermadeath.replay.audio.ReplayVoicechatPlugin());
                        registeredCount++;
                        getLogger().info("[VoiceChat] Addon 'rpermadeath-replay-audio' registrado (captura de áudio para replay).");
                        
                        // 2. Registra integração de ghost (muta fantasmas no voice chat)
                        vcService.registerPlugin(new me.ray.rpermadeath.utils.VoiceChatIntegration());
                        registeredCount++;
                        getLogger().info("[VoiceChat] Addon 'rpermadeath' registrado (mute de fantasmas).");
                    }
                    
                    if (registeredCount > 0) {
                        getLogger().info("[VoiceChat] " + registeredCount + " addon(s) registrado(s) com sucesso no Simple Voice Chat.");
                    } else {
                        getLogger().warning("[VoiceChat] Nenhum addon registrado - BukkitVoicechatService não disponível.");
                        if (replayAudioManager != null) replayAudioManager.setEnabled(false);
                    }
                } catch (Exception e) {
                    getLogger().severe("[VoiceChat] Erro ao registrar addons: " + e.getMessage());
                    e.printStackTrace();
                    if (replayAudioManager != null) replayAudioManager.setEnabled(false);
                }
            } else {
                getLogger().info("[VoiceChat] Simple Voice Chat não encontrado. Replay de áudio e mute de ghost desativados.");
                if (replayAudioManager != null) replayAudioManager.setEnabled(false);
            }
            
            // Inicializa gerenciador de menus
            this.menuManager = new MenuManager(this, deathManager);
            this.menuManager.setReplayManager(replayManager);

            this.deathListener = new DeathListener(this, deathManager, luckPermsManager, replayManager, bountyManager, menuManager);
            this.spectatorListener = new SpectatorListener(this, deathManager, worldGuardManager, replayListener);
            getServer().getPluginManager().registerEvents(deathListener, this);
            getServer().getPluginManager().registerEvents(new HeadInteractListener(this), this);
            getServer().getPluginManager().registerEvents(new MenuListener(this, menuManager, replayManager), this);
            getServer().getPluginManager().registerEvents(new me.ray.rpermadeath.listeners.RitualListener(this, deathManager), this);
            getServer().getPluginManager().registerEvents(spectatorListener, this);
            getServer().getPluginManager().registerEvents(new me.ray.rpermadeath.listeners.GhostListener(this, ghostManager), this);
            getServer().getPluginManager().registerEvents(new me.ray.rpermadeath.listeners.MaintenanceFixListener(this), this);

            // Ghosts são repopulados automaticamente dentro de deathManager.load() (async callback)
            getServer().getPluginManager().registerEvents(replayListener, this);
            
            if (getServer().getPluginManager().isPluginEnabled("ReviveMe")) {
                getServer().getPluginManager().registerEvents(new ReviveListener(this, replayManager), this);
                getLogger().info("ReviveMe encontrado! Integração ativada.");
            } else {
                getLogger().warning("ReviveMe não encontrado. Integração desativada.");
            }

            if (getServer().getPluginManager().isPluginEnabled("MMOCore")) {
                getServer().getPluginManager().registerEvents(new me.ray.rpermadeath.replay.ReplayMMOCoreListener(this, replayManager), this);
                getLogger().info("MMOCore encontrado! Integração de Replay ativada.");
            } else {
                getLogger().warning("MMOCore não encontrado. Replay de skills desativado.");
            }

            if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                this.placeholderExpansion = new me.ray.rpermadeath.placeholders.MidgardExpansion(this);
                this.placeholderExpansion.register();
                getLogger().info("PlaceholderAPI encontrado! Placeholders registrados.");
            }



            // ProtocolLib Support
            if (getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
                new me.ray.rpermadeath.replay.ReplayProtocolListener(this, replayManager);
                getLogger().info("ProtocolLib encontrado! Gravação de partículas ativada.");
            }

            // MMOItems Support
            if (getServer().getPluginManager().isPluginEnabled("MMOItems")) {
                getLogger().info("MMOItems encontrado! Suporte a itens customizados no Replay ativado.");
            }

            // Cria instâncias de comandos (compartilhadas entre Brigadier e Bukkit fallback)
            MDeathCommand mdeathCommand = new MDeathCommand(this, deathManager, menuManager, replayManager, replayListener, bountyManager);
            ReplayToggleCommand replayToggleCommand = new ReplayToggleCommand(this, replayManager);

            getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
                final Commands commands = event.registrar();
                commands.register("rdeath", "Comando principal do RPermadeath", mdeathCommand);
                commands.register("replaytoggle", "Ativa/Desativa a gravação de replays", replayToggleCommand);
            });

            // Bukkit fallback command registration (PlugManX compatibility)
            // LifecycleEvents.COMMANDS only fires on server boot, not on PlugManX reload
            PluginCommand rdeathCmd = getCommand("rdeath");
            if (rdeathCmd != null) {
                rdeathCmd.setExecutor(mdeathCommand);
                rdeathCmd.setTabCompleter(mdeathCommand);
            }
            PluginCommand replayCmd = getCommand("replaytoggle");
            if (replayCmd != null) {
                replayCmd.setExecutor(replayToggleCommand);
            }
            
            // Auto-save removido: dados já são salvos imediatamente no banco via markDead()/setDeathState()/revive()
            
            getLogger().info("RPermadeath ativado com sucesso!");
            getLogger().info("Sistema de Replay inicializado - gravando continuamente...");
        } catch (Exception e) {
            getLogger().severe("Erro fatal ao iniciar RPermadeath: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    public static RPermadeath getInstance() {
        return instance;
    }

    public ReplayListener getReplayListener() {
        return replayListener;
    }
    
    public me.ray.rpermadeath.utils.DiscordWebhook getDiscordWebhook() {
        return discordWebhook;
    }

    public void reloadPlugin() {
        reloadConfig();
        addConfigDefaults();
        // Flush deletes pendentes ANTES de recarregar dados do banco
        // Garante que jogadores revividos não sejam re-carregados
        if (deathManager != null) {
            deathManager.flushPendingDeletes();
        }
        deathManager.load();
        bountyManager.load();
        if (deathListener != null) {
            deathListener.reloadConfig();
        }
        if (spectatorListener != null) {
            spectatorListener.reloadConfig();
        }
        if (discordWebhook != null) {
            discordWebhook.loadConfig();
        }
        if (replayManager != null) {
            replayManager.reload();
        }
        if (replayAudioManager != null) {
            replayAudioManager.reload();
        }
        if (messageManager != null) {
            messageManager.reload();
        }
        if (proxyWhitelistMySqlManager != null) {
            proxyWhitelistMySqlManager.reload();
        }
    }

    @Override
    public void onDisable() {
        try {
            // Flush deletes pendentes ANTES de cancelar tasks e desconectar o DB
            // Sem isso, revives feitos nesta sessão podem não ter sido deletados do DB
            // (o DELETE roda async e cancelTasks() mataria o DELETE antes de executar)
            if (deathManager != null) {
                deathManager.flushPendingDeletes();
            }

            // Cancelar tarefas primeiro para que nada novo tente rodar
            this.getServer().getScheduler().cancelTasks(this);

            // Fechar inventários abertos do plugin (PlugManX: evita referências orfas)
            for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (p.getOpenInventory().getTopInventory().getHolder() == null) {
                    // Plugin menus use null holder - check title for our menus
                    String title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(p.getOpenInventory().title());
                    if (title.contains("ᴠᴀʟʜᴀʟʟᴀ") || title.contains("ᴄᴏɴꜰɪʀᴍᴀʀ") 
                            || title.contains("ɪɴꜰᴏ ᴍᴏʀᴛᴇ") || title.contains("Replays -")
                            || title.contains("ʀɪᴛᴜᴀʟ") || title.contains("ʀᴇssᴜʀʀᴇɪçãᴏ")
                            || title.contains("ʀᴇssᴜsᴄɪᴛᴀʀ") || title.contains("ᴇxᴄʟᴜsãᴏ")
                            || title.contains("ᴘʟᴀʏᴇʀ:")) {
                        p.closeInventory();
                    }
                }
            }

            // Ghost state não é restaurado aqui propositalmente:
            // - No PLM reload: updateGhost() no onEnable reaplicará o estado correto
            // - No shutdown real: GhostListener.onJoin() reaplicará quando o player entrar

            // Desligar subsistemas lógicos
            if (replayManager != null) {
                try {
                    replayManager.shutdown();
                } catch (Exception e) {
                    getLogger().severe("Erro ao desligar ReplayManager: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            if (replayListener != null) {
                try {
                    replayListener.stopAllReplays();
                } catch (Exception e) {
                    getLogger().severe("Erro ao parar replays: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            
            // Salvamento no shutdown removido: dados já estão persistidos no banco de dados

            // Fechar conexões (DB é sempre um dos últimos)
            if (this.databaseManager != null) {
                try {
                    this.databaseManager.disconnect();
                } catch (Exception e) {
                    getLogger().severe("Erro ao desconectar do banco de dados: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            if (this.proxyWhitelistMySqlManager != null) {
                try {
                    this.proxyWhitelistMySqlManager.disconnect();
                } catch (Exception e) {
                    getLogger().severe("Erro ao desconectar o MySQL da whitelist do proxy: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            // Limpar canais e APIs externas
            this.getServer().getMessenger().unregisterOutgoingPluginChannel(this);

            if (getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
                try {
                    com.comphenix.protocol.ProtocolLibrary.getProtocolManager().removePacketListeners(this);
                } catch (Exception e) {
                    getLogger().warning("Erro ao remover listeners do ProtocolLib: " + e.getMessage());
                }
            }

            // Desregistrar PlaceholderAPI expansion (PlugManX: evita duplicatas ao recarregar)
            if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                try {
                    if (placeholderExpansion != null) {
                        placeholderExpansion.unregister();
                        placeholderExpansion = null;
                    }
                } catch (Exception e) {
                    getLogger().warning("Erro ao desregistrar PlaceholderAPI expansion: " + e.getMessage());
                }
            }

            // Desregistrar todos os listeners explicitamente (PlugManX: garante limpeza completa)
            org.bukkit.event.HandlerList.unregisterAll(this);

            getLogger().info("RPermadeath desativado!");

        } catch (Exception e) {
            getLogger().severe("Erro fatal ao desativar RPermadeath: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // LIMPEZA DE MEMÓRIA CRÍTICA (Sempre no finally)
            instance = null;
            econ = null;
        }
    }
    
    public ReplayManager getReplayManager() {
        return replayManager;
    }

    public MessageManager getMessages() {
        return messageManager;
    }
    
    public me.ray.rpermadeath.replay.ReplayWorldManager getReplayWorldManager() {
        return replayWorldManager;
    }

    public ReplayAudioManager getReplayAudioManager() {
        return replayAudioManager;
    }

    public DeathManager getDeathManager() {
        return deathManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public ProxyWhitelistMySqlManager getProxyWhitelistMySqlManager() {
        return proxyWhitelistMySqlManager;
    }

    public BountyManager getBountyManager() {
        return bountyManager;
    }

    public WorldGuardManager getWorldGuardManager() {
        return worldGuardManager;
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        org.bukkit.plugin.RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> rsp = getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        if (econ != null) {
            getLogger().info("Sistema de economia conectado: " + econ.getName());
        }
        return econ != null;
    }

    public static net.milkbowl.vault.economy.Economy getEconomy() {
        return econ;
    }

    public me.ray.rpermadeath.managers.GhostManager getGhostManager() {
        return ghostManager;
    }

    public void removeProxyWhitelist(String playerName, java.util.UUID playerId) {
        if (proxyWhitelistMySqlManager != null) {
            proxyWhitelistMySqlManager.removeWhitelist(playerId, playerName);
        }
        sendWhitelistRemoveRequest(playerName);
    }

    public boolean startPermanentReset(java.util.UUID playerId, String playerName, String reason) {
        String resolvedName = (playerName == null || playerName.isBlank()) ? playerId.toString() : playerName;
        String resolvedReason = (reason == null || reason.isBlank())
                ? "\u00A7c[RPermadeath] \u00A7fSeu personagem foi resetado permanentemente."
                : reason;

        org.bukkit.entity.Player onlinePlayer = getServer().getPlayer(playerId);
        if (onlinePlayer != null) {
            if (ghostManager != null) {
                ghostManager.removeGhost(onlinePlayer);
            }
            onlinePlayer.kick(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                    .deserialize(resolvedReason));
        }

        removeProxyWhitelist(resolvedName, playerId);
        sendKickRequest(resolvedName, resolvedReason);

        return new me.ray.rpermadeath.utils.PlayerDataWiper(this).wipePermanentData(playerId, resolvedName);
    }

    public void sendKickRequest(String playerName, String reason) {
        com.google.common.io.ByteArrayDataOutput out = com.google.common.io.ByteStreams.newDataOutput();
        out.writeUTF("KICK");
        out.writeUTF(playerName);
        out.writeUTF(reason);
        
        sendPluginMessage(out);
    }

    public void sendWhitelistRemoveRequest(String playerName) {
        com.google.common.io.ByteArrayDataOutput out = com.google.common.io.ByteStreams.newDataOutput();
        out.writeUTF("WHITELIST_REMOVE");
        out.writeUTF(playerName);
        
        sendPluginMessage(out);
    }

    private void sendPluginMessage(com.google.common.io.ByteArrayDataOutput out) {
        try {
            // Envia através de qualquer jogador online, ou aguarda um entrar
            if (!getServer().getOnlinePlayers().isEmpty()) {
                getServer().getOnlinePlayers().iterator().next().sendPluginMessage(this, "rpermadeath:death", out.toByteArray());
            } else {
                getLogger().warning("Nao foi possivel enviar mensagem para o Proxy: Nenhum jogador online.");
            }
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Erro ao enviar Plugin Message", e);
        }
    }
    private void addConfigDefaults() {
        getConfig().addDefault("permanent-reset.check-interval-ticks", 10L);
        getConfig().addDefault("permanent-reset.post-logout-delay-ticks", 20L);
        getConfig().addDefault("permanent-reset.commands", java.util.List.of(
                "advancedjobsadmin delete user %player%",
                "mmocore admin reset all %player%",
                "eco set %player% 0",
                "lands admin player %player% delete",
                "lands admin player %player% delete confirm",
                "backpack clear %player%",
                "skin clear %player%",
                "races reset %player%",
                "customfishing statistics reset %player%",
                "quests admin moddata fullreset %player%",
                "mcpets clearStats %player%"
        ));
        getConfig().addDefault("permanent-reset.plugin-data-dirs", java.util.List.of(
                "MMOCore/userdata",
                "MMOItems/userdata",
                "MMOInventory/userdata",
                "CustomStructures/userdata",
                "AdvancedEnchantments/userdata",
                "Jobs/config/jobs",
                "ShopGUIPlus/players"
        ));
        getConfig().addDefault("purgatory.enabled", true);
        getConfig().addDefault("purgatory.world", "purgatorio");
        getConfig().addDefault("purgatory.force-spectator", false);
        getConfig().addDefault("purgatory.allowed-regions", java.util.List.of());
        getConfig().addDefault("proxy-whitelist-mysql.enabled", false);
        getConfig().addDefault("proxy-whitelist-mysql.host", "localhost");
        getConfig().addDefault("proxy-whitelist-mysql.port", "3306");
        getConfig().addDefault("proxy-whitelist-mysql.database", "midgard");
        getConfig().addDefault("proxy-whitelist-mysql.user", "root");
        getConfig().addDefault("proxy-whitelist-mysql.password", "");
        getConfig().addDefault("proxy-whitelist-mysql.use-ssl", false);
        getConfig().options().copyDefaults(true);
        saveConfig();
    }
}

