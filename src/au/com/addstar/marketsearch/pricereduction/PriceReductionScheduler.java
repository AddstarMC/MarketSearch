package au.com.addstar.marketsearch.pricereduction;

import au.com.addstar.marketsearch.MarketSearch;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.logging.Level;

/**
 * Schedules the daily price reduction run at the configured local time. After each fire it
 * reschedules itself for the next day.
 */
public class PriceReductionScheduler {

    private static final long TICKS_PER_SECOND = 20L;

    private final MarketSearch plugin;
    private final PriceReductionManager manager;
    private final PriceReductionConfig config;
    private BukkitTask task;

    public PriceReductionScheduler(MarketSearch plugin, PriceReductionManager manager,
                                   PriceReductionConfig config) {
        this.plugin = plugin;
        this.manager = manager;
        this.config = config;
    }

    public void start() {
        scheduleNext();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void scheduleNext() {
        LocalTime target = parseRunTime(config.getRunTime());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = LocalDateTime.of(LocalDate.now(), target);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        long delayTicks = Math.max(1L, Duration.between(now, next).getSeconds() * TICKS_PER_SECOND);
        plugin.getLogger().info("Price reduction scheduled for " + next + " (in "
                + Duration.between(now, next).toMinutes() + " minutes)");

        task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                manager.startRun(false, null);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Scheduled price reduction failed to start", e);
            } finally {
                scheduleNext();
            }
        }, delayTicks);
    }

    private LocalTime parseRunTime(String value) {
        try {
            return LocalTime.parse(value, DateTimeFormatter.ofPattern("H:mm"));
        } catch (DateTimeParseException e) {
            plugin.getLogger().warning("Invalid price-reduction run-time '" + value + "', defaulting to 02:00");
            return LocalTime.of(2, 0);
        }
    }
}
