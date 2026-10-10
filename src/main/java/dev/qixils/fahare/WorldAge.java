package dev.qixils.fahare;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.boss.KeyedBossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;

/**
 * Counts the days spent in the current world and keeps the best (fewest days) dragon kill across
 * resets. The day count is read straight from the play world's clock: one day per dawn, a sleep
 * skip jumps the clock to the next dawn, and the clock is paused while the server is empty. The
 * count therefore restarts at 0 together with the world.
 */
final class WorldAge {

    private static final NamespacedKey LEGACY_BAR = NamespacedKey.minecraft("world_age");
    private static final long DAY = 24000L;
    private static final Title.Times RECORD_TIMES = Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1));

    private final Fahare plugin;
    private final BossBar bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
    private @Nullable Path dataFile;
    private @Nullable Long dragonRecord;
    // config
    private boolean enabled = true;
    private boolean freezeWhenEmpty = true;

    WorldAge(Fahare plugin) {
        this.plugin = plugin;
    }

    void configure(boolean enabled, boolean freezeWhenEmpty) {
        this.enabled = enabled;
        this.freezeWhenEmpty = freezeWhenEmpty;
    }

    boolean isEnabled() {
        return enabled;
    }

    void enable() {
        dataFile = plugin.getDataPath().resolve("world-age.yml");
        load();
        importFromSkript();
        removeLegacyBar();
        if (!enabled)
            return;
        for (Player player : Bukkit.getOnlinePlayers())
            player.showBossBar(bar);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, $ -> update(), 1, 20);
    }

    private @Nullable World playWorld() {
        // never through Fahare#fakeOverworld(): that would create the world in the middle of a reset
        return Bukkit.getWorld(plugin.fakeOverworldKey());
    }

    /** Days spent in the current world. */
    long days() {
        World world = playWorld();
        return world == null ? 0 : Math.floorDiv(world.getFullTime(), DAY);
    }

    @Nullable Long dragonRecord() {
        return dragonRecord;
    }

    void update() {
        World world = playWorld();
        if (world == null)
            return;
        applyFreeze(world);
        bar.name(GameOver.render(title(days())));
        float progress = (float) Math.floorMod(world.getTime(), DAY) / DAY;
        bar.progress(Math.max(0f, Math.min(1f, progress)));
    }

    private Component title(long days) {
        Component title = translatable(days == 1 ? "fhr.bar.world-age.one" : "fhr.bar.world-age", NamedTextColor.GOLD, text(days));
        if (dragonRecord != null)
            title = title.append(translatable("fhr.bar.world-age.record", NamedTextColor.GOLD, text(dragonRecord)));
        return title;
    }

    private void applyFreeze(World world) {
        if (!freezeWhenEmpty)
            return;
        boolean advance = !Bukkit.getOnlinePlayers().isEmpty();
        // with Paper's time.affects-all-worlds there is one clock for the whole server and it follows
        // the real overworld's rule, so the play world's rule alone is not enough
        for (World target : List.of(world, Fahare.overworld())) {
            if (!Boolean.valueOf(advance).equals(target.getGameRuleValue(GameRules.ADVANCE_TIME)))
                target.setGameRule(GameRules.ADVANCE_TIME, advance);
        }
    }

    void onJoin(Player player) {
        if (!enabled)
            return;
        update();
        player.showBossBar(bar);
    }

    void onQuit(Player player) {
        player.hideBossBar(bar);
    }

    /** A regenerated play world starts at day 0. */
    void onReset(World newWorld) {
        if (!enabled)
            return;
        newWorld.setFullTime(0);
        update();
    }

    void onDragonDeath() {
        if (!enabled)
            return;
        long days = days();
        Component daysText = text(days, NamedTextColor.YELLOW);
        Component message;
        if (dragonRecord == null) {
            dragonRecord = days;
            message = translatable("fhr.chat.dragon.first", NamedTextColor.GOLD, daysText);
        } else if (days < dragonRecord) {
            Component old = text(dragonRecord, NamedTextColor.GRAY);
            dragonRecord = days;
            message = translatable("fhr.chat.dragon.record", NamedTextColor.GOLD, daysText, old);
        } else {
            return;
        }
        save();
        update();
        Bukkit.getServer().sendMessage(message.decorate(TextDecoration.BOLD));
        Component title = translatable("fhr.title.dragon", NamedTextColor.GOLD).decorate(TextDecoration.BOLD);
        Component subtitle = translatable("fhr.title.dragon.subtitle", NamedTextColor.YELLOW, text(days));
        Bukkit.getServer().showTitle(Title.title(GameOver.render(title), GameOver.render(subtitle), RECORD_TIMES));
    }

    /** Rewrites the day count, keeping the time of day. Returns false while the play world is unavailable. */
    boolean setDays(long days) {
        World world = playWorld();
        if (world == null)
            return false;
        world.setFullTime(Math.max(0, days) * DAY + Math.floorMod(world.getTime(), DAY));
        update();
        return true;
    }

    void setDragonRecord(@Nullable Long record) {
        dragonRecord = record;
        save();
        update();
    }

    private void load() {
        if (dataFile == null || !Files.isRegularFile(dataFile))
            return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile.toFile());
        if (data.isLong("dragon-record") || data.isInt("dragon-record"))
            dragonRecord = data.getLong("dragon-record");
    }

    private void save() {
        if (dataFile == null)
            return;
        try {
            Files.createDirectories(dataFile.getParent());
            YamlConfiguration data = new YamlConfiguration();
            data.set("dragon-record", dragonRecord);
            data.save(dataFile.toFile());
        } catch (Exception e) {
            plugin.getComponentLogger().warn(translatable("fhr.log.error.world-age.save"), e);
        }
    }

    /** One-time import of the record kept by the Skript this feature replaces. */
    private void importFromSkript() {
        if (dragonRecord != null)
            return;
        Path csv = plugin.getDataPath().resolveSibling("Skript").resolve("variables.csv");
        if (!Files.isRegularFile(csv))
            return;
        try {
            for (String line : Files.readAllLines(csv, StandardCharsets.UTF_8)) {
                String[] parts = line.split(",");
                if (parts.length < 3 || !parts[0].trim().equals("world.dragon_record") || !parts[1].trim().equals("long"))
                    continue;
                dragonRecord = Long.parseUnsignedLong(parts[2].trim(), 16);
                save();
                plugin.getComponentLogger().info(translatable("fhr.log.info.world-age.import", text(dragonRecord), text(csv.toString())));
                return;
            }
        } catch (Exception e) {
            plugin.getComponentLogger().warn(translatable("fhr.log.error.world-age.import", text(csv.toString())), e);
        }
    }

    /** The vanilla boss bar the Skript drove; it would otherwise linger next to ours. */
    private void removeLegacyBar() {
        KeyedBossBar legacy = Bukkit.getBossBar(LEGACY_BAR);
        if (legacy == null)
            return;
        legacy.removeAll();
        Bukkit.removeBossBar(LEGACY_BAR);
        plugin.getComponentLogger().info(translatable("fhr.log.info.world-age.legacy-bar", text(LEGACY_BAR.toString())));
    }
}
