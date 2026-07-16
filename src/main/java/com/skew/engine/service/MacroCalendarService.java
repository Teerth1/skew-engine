package com.skew.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Macro event & earnings gating — blocks new option entries inside a blackout
 * window (default 48h) before major macro releases (FOMC, CPI, NFP, …) or a
 * ticker's earnings report.
 *
 * <p>Buying single options into a known binary event means paying peak event
 * premium and eating the IV crush after the print; this gate removes that
 * failure mode deterministically, before any AI reasoning happens.</p>
 *
 * <p>Events load from an editable JSON file ({@code data/macro_calendar.json}):</p>
 * <pre>{@code
 * [
 *   {"name": "FOMC Rate Decision", "at": "2026-07-29T14:00", "ticker": null},
 *   {"name": "CPI Release",        "at": "2026-08-12T08:30", "ticker": null},
 *   {"name": "AAPL Earnings",      "at": "2026-07-30T16:30", "ticker": "AAPL"}
 * ]
 * }</pre>
 *
 * <p>Times are interpreted in US/Eastern. Events with a {@code ticker} only
 * block that ticker (index products SPX/SPY/QQQ are never blocked by
 * single-name earnings); macro events ({@code ticker == null}) block everything.
 * The file is re-read at most once per minute so the desk can edit it live.</p>
 */
@Service
public class MacroCalendarService {

    private static final Logger logger = LoggerFactory.getLogger(MacroCalendarService.class);
    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter EVENT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /** One calendar entry. {@code ticker == null} means a market-wide macro event. */
    public record MacroEvent(String name, ZonedDateTime at, String ticker) {}

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<MacroEvent> events = new CopyOnWriteArrayList<>();
    private volatile long lastLoadedAtMillis = 0L;

    @Value("${trading.macro.enabled:true}")
    private boolean enabled;

    @Value("${trading.macro.blackout-hours:48}")
    private int blackoutHours;

    @Value("${trading.macro.calendar-file:data/macro_calendar.json}")
    private String calendarFile;

    /**
     * Returns the blocking event if {@code ticker} is inside a blackout window,
     * or empty when trading is clear. Designed to be called from
     * {@link RiskManagerService#evaluate} as a deterministic gate.
     */
    public Optional<String> blackoutReason(String ticker) {
        if (!enabled) {
            return Optional.empty();
        }
        reloadIfStale();

        ZonedDateTime now = ZonedDateTime.now(ET_ZONE);
        String cleanTicker = ticker == null ? "" : ticker.replace("$", "").toUpperCase(Locale.ROOT);

        for (MacroEvent event : events) {
            if (event.at().isBefore(now)) {
                continue; // already released
            }
            boolean applies = event.ticker() == null
                    || event.ticker().equalsIgnoreCase(cleanTicker);
            if (!applies) {
                continue;
            }
            Duration untilEvent = Duration.between(now, event.at());
            if (untilEvent.toHours() < blackoutHours) {
                return Optional.of("%s in %dh %dm (blackout window: %dh)".formatted(
                        event.name(), untilEvent.toHours(), untilEvent.toMinutesPart(), blackoutHours));
            }
        }
        return Optional.empty();
    }

    /** All upcoming events, for dashboards / Discord commands. */
    public List<MacroEvent> upcomingEvents() {
        reloadIfStale();
        ZonedDateTime now = ZonedDateTime.now(ET_ZONE);
        return events.stream().filter(e -> e.at().isAfter(now)).toList();
    }

    private synchronized void reloadIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastLoadedAtMillis < 60_000) {
            return;
        }
        lastLoadedAtMillis = now;

        File file = new File(calendarFile);
        if (!file.exists()) {
            if (events.isEmpty()) {
                logger.info("MacroCalendarService: no calendar file at {} — macro gate inactive "
                        + "until one is created.", file.getAbsolutePath());
            }
            return;
        }
        try {
            List<java.util.Map<String, String>> raw = objectMapper.readValue(
                    file, objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, java.util.Map.class));
            List<MacroEvent> parsed = raw.stream()
                    .filter(m -> m.get("name") != null && m.get("at") != null)
                    .map(m -> new MacroEvent(
                            m.get("name"),
                            java.time.LocalDateTime.parse(m.get("at"), EVENT_TIME).atZone(ET_ZONE),
                            m.get("ticker")))
                    .toList();
            events.clear();
            events.addAll(parsed);
            logger.debug("MacroCalendarService: loaded {} events from {}", parsed.size(), calendarFile);
        } catch (Exception e) {
            logger.error("MacroCalendarService: failed to parse {}: {}", calendarFile, e.getMessage());
        }
    }
}
