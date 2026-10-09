package dev.qixils.fahare;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;

/**
 * The "game over" sequence that runs between the fatal death and the world reset: an announcement,
 * an explosion at every player, the remaining players killed so that everyone spectates their own
 * crater, an on-screen countdown, and finally {@link Fahare#reset()}.
 * <p>
 * Everything here runs on the main thread through the global region scheduler. With a delay of 0
 * and an explosion power of 0 the sequence collapses to the upstream behaviour (immediate reset).
 */
final class GameOver {

    private static final Sound THUNDER = Sound.sound(Key.key("minecraft", "entity.lightning_bolt.thunder"), Sound.Source.MASTER, 1f, 1f);
    private static final Sound TICK = Sound.sound(Key.key("minecraft", "block.note_block.pling"), Sound.Source.MASTER, 1f, 1f);
    private static final Title.Times ANNOUNCEMENT_TIMES = Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1));
    private static final Title.Times COUNTDOWN_TIMES = Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100));

    private final Fahare plugin;
    private final Map<UUID, Location> deathSpots = new HashMap<>();
    private boolean pending = false;
    private boolean announceNewWorld = false;
    // config
    private int delaySeconds = 30;
    private int countdownSeconds = 5;
    private float explosionPower = 40f;

    GameOver(Fahare plugin) {
        this.plugin = plugin;
    }

    void configure(int delaySeconds, int countdownSeconds, double explosionPower) {
        this.delaySeconds = Math.max(0, delaySeconds);
        this.countdownSeconds = Math.max(0, Math.min(countdownSeconds, this.delaySeconds));
        this.explosionPower = (float) Math.max(0, explosionPower);
    }

    /** Whether anything beyond the upstream immediate reset is configured. */
    boolean isActive() {
        return delaySeconds > 0 || explosionPower > 0f;
    }

    /** Whether a sequence is running, i.e. the reset is scheduled but has not started yet. */
    boolean isPending() {
        return pending;
    }

    /** Remembers where a player died so that they respawn (and the explosion happens) right there. */
    void recordDeath(Player player) {
        deathSpots.put(player.getUniqueId(), player.getLocation().clone());
    }

    @Nullable Location deathSpot(Player player) {
        return deathSpots.get(player.getUniqueId());
    }

    /**
     * Starts the sequence for the given victim ({@code null} when the trigger was not a single death).
     * Falls through to an immediate reset when nothing is configured.
     */
    void begin(@Nullable Player victim) {
        if (pending || plugin.isResetting())
            return;
        if (delaySeconds <= 0 && explosionPower <= 0f) {
            plugin.reset();
            return;
        }
        pending = true;

        // Announce
        Component delay = text(delaySeconds);
        Component chat;
        Component subtitle;
        if (victim != null) {
            Component name = victim.name().color(NamedTextColor.RED);
            chat = translatable("fhr.chat.game-over", NamedTextColor.DARK_RED, name).decorate(TextDecoration.BOLD);
            subtitle = translatable("fhr.title.game-over.subtitle", NamedTextColor.RED, name, delay);
            plugin.getComponentLogger().info(translatable("fhr.log.game-over", victim.name(), delay));
        } else {
            chat = translatable("fhr.chat.game-over.all", NamedTextColor.DARK_RED).decorate(TextDecoration.BOLD);
            subtitle = translatable("fhr.title.game-over.subtitle.all", NamedTextColor.RED, delay);
            plugin.getComponentLogger().info(translatable("fhr.log.game-over.all", delay));
        }
        Bukkit.getServer().sendMessage(chat);
        if (delaySeconds > 0)
            Bukkit.getServer().sendMessage(translatable("fhr.chat.game-over.delay", NamedTextColor.RED, delay));

        Audience everyone = players();
        Component title = translatable("fhr.title.game-over", NamedTextColor.DARK_RED).decorate(TextDecoration.BOLD);
        everyone.showTitle(Title.title(render(title), render(subtitle), ANNOUNCEMENT_TIMES));
        everyone.playSound(THUNDER);

        // Blow everyone up where they stand (or where they died) and make sure nobody survives it
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            boolean alive = !player.isDead() && player.getHealth() > 0 && player.getGameMode() != GameMode.SPECTATOR;
            Location spot = alive ? player.getLocation().clone() : deathSpots.getOrDefault(player.getUniqueId(), player.getLocation().clone());
            if (alive)
                deathSpots.put(player.getUniqueId(), spot);
            explode(spot);
            if (alive && !player.isDead() && player.getHealth() > 0)
                player.setHealth(0);
        }

        // Countdown titles, then the reset
        if (countdownSeconds > 0) {
            for (int n = countdownSeconds; n >= 1; n--) {
                int number = n;
                schedule((delaySeconds - n) * 20L, () -> showCountdown(number));
            }
        }
        schedule(delaySeconds * 20L, this::finish);
    }

    private void explode(Location spot) {
        World world = spot.getWorld();
        if (explosionPower <= 0f || world == null)
            return;
        world.createExplosion(spot, explosionPower, false, true);
    }

    private void showCountdown(int number) {
        if (!pending)
            return;
        Audience everyone = players();
        Component subtitle = translatable("fhr.title.countdown", NamedTextColor.RED);
        everyone.showTitle(Title.title(text(number, NamedTextColor.GOLD, TextDecoration.BOLD), render(subtitle), COUNTDOWN_TIMES));
        everyone.playSound(TICK);
    }

    private void finish() {
        if (!pending)
            return;
        pending = false;
        deathSpots.clear();
        announceNewWorld = true;
        plugin.reset();
    }

    /** Called by the plugin once the regenerated world is ready and everyone has been moved into it. */
    void onResetComplete() {
        if (!announceNewWorld)
            return;
        announceNewWorld = false;
        Component title = translatable("fhr.title.new-world", NamedTextColor.GREEN).decorate(TextDecoration.BOLD);
        Component subtitle = translatable("fhr.title.new-world.subtitle", NamedTextColor.GRAY);
        players().showTitle(Title.title(render(title), render(subtitle), ANNOUNCEMENT_TIMES));
    }

    /** Drops any pending sequence, e.g. when a manual reset starts first. */
    void cancel() {
        pending = false;
        deathSpots.clear();
    }

    private void schedule(long delayTicks, Runnable task) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, $ -> task.run(), Math.max(1L, delayTicks));
    }

    private static Audience players() {
        return Audience.audience(Bukkit.getOnlinePlayers());
    }

    /** Titles and boss bars are not translated per player by the server, so render them here. */
    static Component render(Component component) {
        return GlobalTranslator.render(component, Locale.US);
    }
}
