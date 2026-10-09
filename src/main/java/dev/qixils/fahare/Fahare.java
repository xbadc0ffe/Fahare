package dev.qixils.fahare;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationStore;
import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import dev.qixils.fahare.events.FahareResetEvent;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;

public final class Fahare extends JavaPlugin implements Listener {

    private static final NamespacedKey REAL_OVERWORLD_KEY = NamespacedKey.minecraft("overworld");
    private static final Random RANDOM = new Random();
    // todo: i18n?
    private final Permission resetPerm = new Permission("fahare.reset", "Whether to allow manually resetting the world", PermissionDefault.OP);
    private final Permission worldAgePerm = new Permission("fahare.worldage", "Whether to allow editing the world age and the dragon record", PermissionDefault.OP);
    private final GameOver gameOver = new GameOver(this);
    private final WorldAge worldAge = new WorldAge(this);
    private final NamespacedKey fakeOverworldKey = new NamespacedKey(this, "overworld");
    private final NamespacedKey limboWorldKey = new NamespacedKey(this, "limbo");
    private final NamespacedKey lastResetIdKey = new NamespacedKey(this, "last_reset_id");
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private final Set<UUID> playersPendingMountCancel = new HashSet<>();
    private int currentResetId = 0;
    private World limboWorld;
    private Path worldContainer;
    private @Nullable Path backupContainer;
    private @Nullable Path resetIdPath;
    private boolean resetting = false;
    // config
    private boolean backup = true;
    private boolean autoReset = true;
    private boolean anyDeath = false;
    private int lives = 1;
    private Difficulty forceDifficulty = null;
    private long forceSeed = 0L;
    private String levelName = "world";
    private boolean joinWarning = true;
    private String joinWarningName = "";

    private static @NotNull World overworld() {
        return Objects.requireNonNull(Bukkit.getWorld(REAL_OVERWORLD_KEY), "Overworld not found");
    }

    private @NotNull World fakeOverworld() {
        return Objects.requireNonNullElseGet(Bukkit.getWorld(fakeOverworldKey), this::createFakeOverworld);
    }

    @NotNull NamespacedKey fakeOverworldKey() {
        return fakeOverworldKey;
    }

    boolean isResetting() {
        return resetting;
    }

    private long getNewSeed() {
        if (forceSeed != 0L) return forceSeed;
        return RANDOM.nextLong();
    }

    private Difficulty getNewDifficulty() {
        if (forceDifficulty != null) return forceDifficulty;
        return overworld().getDifficulty();
    }

    private boolean getNewHardcore(Difficulty difficulty) {
        return autoReset && lives <= 1 && difficulty == Difficulty.HARD;
    }

    private @NotNull World createFakeOverworld() {
        // Create fake overworld
        Difficulty difficulty = getNewDifficulty();
        long seed = getNewSeed();
        getComponentLogger().info(translatable("fhr.log.overworld-seed", text(seed)));
        boolean hardcore = getNewHardcore(difficulty);
        WorldCreator creator = new WorldCreator(fakeOverworldKey)
                .copy(overworld())
                .seed(seed)
                .hardcore(hardcore);

        World world = Objects.requireNonNull(creator.createWorld(), "Could not load fake overworld");
        world.setDifficulty(difficulty);
        world.setHardcore(hardcore);

        return world;
    }

    private void saveResetId() {
        // todo: if we have to expand any further than 1 saved value then we should get a json but for now this is fine
        if (resetIdPath == null) return;

        try {
            Files.createDirectories(resetIdPath.getParent());
            Files.writeString(resetIdPath, String.valueOf(currentResetId));
        } catch (IOException e) {
            getComponentLogger().warn(translatable("fhr.log.error.save-reset-id"), e);
        }
    }

    @Override
    public void onEnable() {
        // Register i18n first so every log line below is translatable
        TranslationStore.StringBased<MessageFormat> registry = TranslationStore.messageFormat(new NamespacedKey(this, "translations"));
        registry.defaultLocale(Locale.US);
        for (Locale locale : List.of(Locale.US)) { // TODO: reflection
            ResourceBundle bundle = ResourceBundle.getBundle("Fahare", locale);
            registry.registerAll(locale, bundle, false);
        }
        GlobalTranslator.translator().addSource(registry);

        // Load config
        loadConfig();
        resetIdPath = getDataPath().resolve("current-reset-id.txt");
        try {
            if (Files.exists(resetIdPath)) {
                String idString = Files.readString(resetIdPath).trim();
                currentResetId = Integer.parseInt(idString);
            }
        } catch (Exception ignored) {
            // file probably doesn't exist yet
        }

        // Create backup folder
        worldContainer = Bukkit.getWorldContainer().toPath();
        backupContainer = worldContainer.resolve("fahare-backups");

        if (!Files.exists(backupContainer)) {
            try {
                Files.createDirectory(backupContainer);
            } catch (Exception e) {
                getComponentLogger().error(translatable("fhr.log.error.backup-folder"), e);
                backupContainer = null;
            }
        }

        // Load server properties to extract level-seed & difficulty
        Properties properties = new Properties();
        Path serverProperties = worldContainer.resolve("server.properties");
        if (Files.exists(serverProperties)) {
            try (InputStream contents = Files.newInputStream(serverProperties)) {
                properties.load(contents);
                levelName = properties.getProperty("level-name", levelName);
                try {
                    String levelSeed = properties.getProperty("level-seed", "");
                    forceSeed = Long.parseLong(levelSeed);
                    getComponentLogger().info(translatable("fhr.log.info.found-seed", text(forceSeed)));
                } catch (Exception e) {
                    forceSeed = 0;
                    getComponentLogger().info(translatable("fhr.log.info.missing-seed"));
                }

                String difficulty = properties.getProperty("difficulty", "");
                try {
                    forceDifficulty = Difficulty.valueOf(difficulty.toUpperCase(Locale.US));
                    getComponentLogger().warn(translatable("fhr.log.info.difficulty", text(String.valueOf(forceDifficulty))));
                } catch (Exception e) {
                    getComponentLogger().warn(translatable("fhr.log.error.difficulty", text(difficulty)));
                }
            } catch (Exception e) {
                getComponentLogger().warn(translatable("fhr.log.error.properties"), e);
            }
        }

        // Create limbo world
        WorldCreator creator = new WorldCreator(limboWorldKey)
                .type(WorldType.FLAT)
                .generateStructures(false)
                .generatorSettings("{\"biome\":\"minecraft:the_end\",\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}]}");
        limboWorld = creator.createWorld();

        // Create fake overworld
        World fakeOverworld = createFakeOverworld();

        // Register commands
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
            LiteralArgumentBuilder<CommandSourceStack> resetCommand = Commands.literal("fahare")
                            .then(Commands.literal("reset")
                                    .requires(Commands.restricted(source -> source.getSender().hasPermission(resetPerm)))
                                    .executes(ctx -> {
                                        ctx.getSource().getSender().sendMessage(translatable("fhr.chat.resetting"));
                                        reset();
                                        return Command.SINGLE_SUCCESS;
                                    })
                            )
                            .then(Commands.literal("days")
                                    .requires(Commands.restricted(source -> source.getSender().hasPermission(worldAgePerm)))
                                    .executes(ctx -> {
                                        Long record = worldAge.dragonRecord();
                                        Component recordText = record == null ? translatable("fhr.chat.record-none") : text(record);
                                        ctx.getSource().getSender().sendMessage(translatable("fhr.chat.days-query", text(worldAge.days()), recordText));
                                        return Command.SINGLE_SUCCESS;
                                    })
                                    .then(Commands.argument("days", IntegerArgumentType.integer(0))
                                            .executes(ctx -> {
                                                int days = IntegerArgumentType.getInteger(ctx, "days");
                                                String key = worldAge.setDays(days) ? "fhr.chat.days-set" : "fhr.chat.days-unavailable";
                                                ctx.getSource().getSender().sendMessage(translatable(key, text(days)));
                                                return Command.SINGLE_SUCCESS;
                                            })
                                    )
                            )
                            .then(Commands.literal("record")
                                    .requires(Commands.restricted(source -> source.getSender().hasPermission(worldAgePerm)))
                                    .then(Commands.literal("clear")
                                            .executes(ctx -> {
                                                worldAge.setDragonRecord(null);
                                                ctx.getSource().getSender().sendMessage(translatable("fhr.chat.record-cleared"));
                                                return Command.SINGLE_SUCCESS;
                                            })
                                    )
                                    .then(Commands.argument("days", IntegerArgumentType.integer(0))
                                            .executes(ctx -> {
                                                int days = IntegerArgumentType.getInteger(ctx, "days");
                                                worldAge.setDragonRecord((long) days);
                                                ctx.getSource().getSender().sendMessage(translatable("fhr.chat.record-set", text(days)));
                                                return Command.SINGLE_SUCCESS;
                                            })
                                    )
                            );
            commands.registrar().register(resetCommand.build());
        });

        // Register events and tasks
        Bukkit.getPluginManager().registerEvents(this, this);
        worldAge.enable();
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, $ -> {
            // Teleport players from real overworld
            Location destination = fakeOverworld.getSpawnLocation();
            for (Player player : overworld().getPlayers()) {
                player.teleport(destination);
            }
        }, 1, 1);
    }

    private void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        var config = getConfig();
        backup = config.getBoolean("backup", backup);
        autoReset = config.getBoolean("auto-reset", autoReset);
        anyDeath = config.getBoolean("any-death", anyDeath);
        lives = Math.max(1, config.getInt("lives", lives));
        gameOver.configure(
                config.getInt("game-over.delay", 30),
                config.getInt("game-over.countdown", 5),
                config.getDouble("game-over.explosion-power", 40));
        worldAge.configure(
                config.getBoolean("world-age.enabled", true),
                config.getBoolean("world-age.freeze-when-empty", true));
        joinWarning = config.getBoolean("join-warning.enabled", joinWarning);
        joinWarningName = config.getString("join-warning.name", joinWarningName);
    }

    public int getDeathsFor(UUID player) {
        return deaths.getOrDefault(player, 0);
    }

    public void addDeathTo(UUID player) {
        deaths.put(player, getDeathsFor(player)+1);
    }

    public boolean isDead(UUID player) {
        return getDeathsFor(player) >= lives;
    }

    public boolean isAlive(UUID player) {
        return !isDead(player);
    }

    private int getPlayerLastResetId(Player player) {
        return player.getPersistentDataContainer().getOrDefault(lastResetIdKey, PersistentDataType.INTEGER, 0);
    }

    private void setPlayerLastResetId(Player player, int resetId) {
        player.getPersistentDataContainer().set(lastResetIdKey, PersistentDataType.INTEGER, resetId);
    }

    private void resetPlayer(Player player) {
        player.setGameMode(GameMode.SURVIVAL);
        player.closeInventory();
        player.clearActiveItem();
        player.setItemOnCursor(ItemStack.empty());
        player.getInventory().clear();
        player.getEnderChest().clear();
        player.updateInventory();
        player.setLevel(0);
        player.setExp(0);
        player.setHealth(20);
        player.setFoodLevel(20);
        player.setSaturation(5);
        player.clearActivePotionEffects();

        try {
            Iterator<Advancement> advancements = Bukkit.advancementIterator();
            while (advancements.hasNext()) {
                Advancement advancement = advancements.next();
                AdvancementProgress progress = player.getAdvancementProgress(advancement);
                // list is an unmodifiable copy so iterating is safe
                for (String criteria : progress.getAwardedCriteria()) {
                    progress.revokeCriteria(criteria);
                }
            }
        } catch (Exception e) {
            // idk it just feels like it would error doesn't it
            getComponentLogger().warn(translatable("fhr.log.error.advancements"), e);
        }

        setPlayerLastResetId(player, currentResetId);
    }

    private void deleteNextWorld(List<World> worlds, @Nullable Path backupDestination) {
        // check if all worlds are deleted
        if (worlds.isEmpty()) {
            World overworld = fakeOverworld();
            Location spawn = overworld.getSpawnLocation();
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.setGameMode(GameMode.SURVIVAL);
                player.teleport(spawn);
            }

            worldAge.onReset(overworld);

            try {
                new FahareResetEvent(overworld).callEvent();
            } catch (Exception e) {
                getComponentLogger().error(translatable("fhr.log.error.reset-event"), e);
            }

            resetting = false;
            gameOver.onResetComplete();
            return;
        }

        // check if worlds are ticking
        if (Bukkit.isTickingWorlds()) {
            Bukkit.getGlobalRegionScheduler().runDelayed(this, $ -> deleteNextWorld(worlds, backupDestination), 1);
            return;
        }

        // get world data
        World world = worlds.removeFirst();
        String worldName = world.getName();
        Component worldKey = text(worldName);
        WorldCreator creator = new WorldCreator(world.getKey());

        Difficulty difficulty = getNewDifficulty();
        boolean hardcore = getNewHardcore(difficulty);
        long seed = getNewSeed();
        getComponentLogger().info(translatable("fhr.log.seed", worldKey, text(seed)));

        creator
                .copy(world)
                .seed(seed)
                .hardcore(hardcore);

        Path worldFolder = world.getWorldFolder().toPath();

        // unload world
        if (Bukkit.unloadWorld(world, backup)) {
            try {
                Component arg = text(worldFolder.toString());
                if (backupDestination != null) {
                    // Backup world
                    getComponentLogger().info(translatable("fhr.log.info.backup", arg));
                    Files.move(worldFolder, backupDestination.resolve(worldName));
                } else {
                    // Delete world
                    getComponentLogger().info(translatable("fhr.log.info.delete", arg));
                    IOUtils.deleteDirectory(worldFolder);
                }

                // create new world
                World newWorld = creator.createWorld();
                if (newWorld == null) throw new IllegalStateException("World was null");

                newWorld.setDifficulty(difficulty);
                newWorld.setHardcore(hardcore);

                Bukkit.getServer().sendMessage(translatable("fhr.chat.success", worldKey));
            } catch (Exception e) {
                Component error = translatable("fhr.chat.error", NamedTextColor.RED, worldKey);
                Audience.audience(Bukkit.getOnlinePlayers()).sendMessage(error);
                getComponentLogger().warn(error, e);
            }
        } else {
            Bukkit.getServer().sendMessage(translatable("fhr.chat.error", NamedTextColor.RED, worldKey));
        }

        Bukkit.getGlobalRegionScheduler().runDelayed(this, $ -> deleteNextWorld(worlds, backupDestination), 1);
    }

    private void backupPlayerData(Path backupDestination) {
        // flush every online player (data, stats, advancements) so the copy reflects the moment of the reset
        Bukkit.savePlayers();
        Path levelRoot = worldContainer.resolve(levelName);
        // 26.x keeps everything under players/; older layouts use three sibling folders
        for (String folder : List.of("players", "playerdata", "advancements", "stats")) {
            Path source = levelRoot.resolve(folder);
            if (!Files.isDirectory(source)) continue;
            Component arg = text(source.toString());
            try {
                getComponentLogger().info(translatable("fhr.log.info.backup-players", arg));
                IOUtils.copyDirectory(source, backupDestination.resolve(folder));
            } catch (Exception e) {
                getComponentLogger().warn(translatable("fhr.log.error.backup-players", arg), e);
            }
        }
    }

    public synchronized void reset() {
        if (resetting)
            return;
        if (limboWorld == null)
            return;
        // check if worlds are ticking; nothing below may run twice, so wait before touching anything
        if (Bukkit.isTickingWorlds()) {
            Bukkit.getGlobalRegionScheduler().runDelayed(this, $ -> reset(), 1);
            return;
        }
        resetting = true;
        deaths.clear();
        gameOver.cancel();

        // Update and save current reset ID to keep track of players that were offline during the reset
        currentResetId++;
        saveResetId();

        // calculate backup folder
        Path backupDestination = null;
        if (backup && backupContainer != null) {
            String baseName = ISO_LOCAL_DATE.format(LocalDate.now());
            int attempt = 1;
            do {
                String name = baseName + '-' + attempt++;
                backupDestination = backupContainer.resolve(name);
            } while (Files.exists(backupDestination));
            try {
                Files.createDirectory(backupDestination);
            } catch (Exception e) {
                getComponentLogger().error(translatable("fhr.log.error.backup-subfolder", text(backupDestination.toString())), e);
                backupDestination = null;
            }
        }
        // back up player data before it is wiped
        if (backupDestination != null) {
            backupPlayerData(backupDestination);
        }

        // teleport all players to limbo
        Location destination = new Location(limboWorld, 0, 100, 0);
        for (Player player : Bukkit.getOnlinePlayers()) {
            resetPlayer(player);
            player.setGameMode(GameMode.SPECTATOR);
            player.teleport(destination);
        }
        // unload and delete worlds
        List<World> worlds = Bukkit.getWorlds().stream()
                .filter(w -> !w.getKey().equals(limboWorldKey) && !w.getKey().equals(REAL_OVERWORLD_KEY))
                .collect(Collectors.toList());
        deleteNextWorld(worlds, backupDestination);
    }

    public void resetCheck(boolean death) {
        resetCheck(death, null);
    }

    private void resetCheck(boolean death, @Nullable Player victim) {
        if (!autoReset)
            return;
        if (anyDeath && death) {
            gameOver.begin(victim);
            return;
        }
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        if (players.isEmpty())
            return;
        for (Player player : players) {
            if (isAlive(player.getUniqueId()))
                return;
        }
        gameOver.begin(victim);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        gameOver.recordDeath(player);
        addDeathTo(player.getUniqueId());
        if (isAlive(player.getUniqueId()))
            return;
        Bukkit.getRegionScheduler().runDelayed(this, player.getLocation(), $ -> {
            player.setGameMode(GameMode.SPECTATOR);
            player.spigot().respawn();
            resetCheck(true, player);
        }, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof EnderDragon)
            worldAge.onDragonDeath();
    }

    private void sendJoinWarning(Player player) {
        Component rule = translatable("fhr.chat.join-warning.rule", NamedTextColor.DARK_RED).decorate(TextDecoration.STRIKETHROUGH);
        Component title = translatable("fhr.chat.join-warning.title", NamedTextColor.RED, text(joinWarningName)).decorate(TextDecoration.BOLD);
        Component word = translatable("fhr.chat.join-warning.hardcore-word", NamedTextColor.RED);
        Component hardcore = translatable("fhr.chat.join-warning.hardcore", NamedTextColor.GRAY, word);
        Component reset = translatable("fhr.chat.join-warning.reset", NamedTextColor.GRAY);
        for (Component line : List.of(rule, title, rule, hardcore, reset, rule))
            player.sendMessage(line);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityPortal(EntityPortalEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        World toWorld = to.getWorld();
        if (toWorld == null) return;
        if (!toWorld.getKey().equals(REAL_OVERWORLD_KEY)) return;

        // check if player is coming from the end, and if so just send them to spawn
        if (event.getPortalType() == PortalType.ENDER)
            event.setTo(fakeOverworld().getSpawnLocation());
            // else just update the world
        else
            to.setWorld(fakeOverworld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerPortal(PlayerPortalEvent event) {
        Location to = event.getTo();
        World toWorld = to.getWorld();
        if (toWorld == null) return;
        if (!toWorld.getKey().equals(REAL_OVERWORLD_KEY)) return;

        // check if player is coming from the end, and if so just send them to spawn
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL)
            event.setTo(fakeOverworld().getSpawnLocation());
            // else just update the world
        else
            to.setWorld(fakeOverworld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        // The client decides whether to draw hardcore hearts from the login packet, which is built
        // from the world the player spawns into. Redirect away from the real overworld before that
        // packet exists; the PlayerJoinEvent teleport below is too late for a first join.
        World toWorld = event.getSpawnLocation().getWorld();
        if (toWorld == null || !toWorld.getKey().equals(REAL_OVERWORLD_KEY)) return;

        World fake = Bukkit.getWorld(fakeOverworldKey);
        if (fake != null)
            event.setSpawnLocation(fake.getSpawnLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (joinWarning)
            player.getScheduler().runDelayed(this, $ -> sendJoinWarning(player), null, 20L);
        worldAge.onJoin(player);

        if (resetting) {
            // The worlds are being regenerated right now: park the player in limbo like everyone else
            // and let the end of the reset move them into the new world
            resetPlayer(player);
            player.setGameMode(GameMode.SPECTATOR);
            player.teleport(new Location(limboWorld, 0, 100, 0));
            return;
        }

        // Check if player missed a reset
        int playerLastReset = getPlayerLastResetId(player);
        if (playerLastReset < currentResetId) {
            // Player was offline during reset, apply reset procedure
            resetPlayer(player);
            player.teleport(fakeOverworld().getSpawnLocation());

            // Store the player's UID for one tick to stop the potential EntityMountEvent
            playersPendingMountCancel.add(player.getUniqueId());
            Bukkit.getGlobalRegionScheduler().runDelayed(
                    this,
                    $ -> playersPendingMountCancel.remove(player.getUniqueId()),
                    1L
            );
        } else if (player.getWorld().getKey().equals(REAL_OVERWORLD_KEY)) {
            // Normal case: redirect from real overworld to fake overworld
            player.teleport(fakeOverworld().getSpawnLocation());
        } else if (player.getWorld().getKey().equals(limboWorldKey)) {
            // Logged out in limbo during a reset that has since finished
            player.setGameMode(GameMode.SURVIVAL);
            player.teleport(fakeOverworld().getSpawnLocation());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        worldAge.onQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityMount(EntityMountEvent event) {
        if (playersPendingMountCancel.contains(event.getEntity().getUniqueId())) {
            // Player is re-mounting a previously mounted entity from a reset world, cancel and remove it
            // This causes a vanilla log message "Couldn't reattach entity to player", but it is harmless
            event.setCancelled(true);
            event.getMount().remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (autoReset && gameOver.isActive() && (gameOver.isPending() || isDead(player.getUniqueId()))) {
            // Everyone spectates their own crater until the reset
            Location spot = gameOver.deathSpot(player);
            if (spot != null && spot.getWorld() != null) {
                event.setRespawnLocation(spot);
                return;
            }
        }
        Location destination = event.getRespawnLocation();
        if (destination.getWorld().getKey().equals(REAL_OVERWORLD_KEY))
            event.setRespawnLocation(fakeOverworld().getSpawnLocation());
    }
}
