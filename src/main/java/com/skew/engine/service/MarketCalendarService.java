package com.skew.engine.service;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service to check if the US stock market is open.
 * Handles weekends, US market holidays, and trading hours (9:30 AM - 4:00 PM
 * ET).
 * 
 * NYSE Holidays are CALCULATED dynamically for any year, not hardcoded.
 * This means the service works correctly for 2027, 2028, and beyond.
 * 
 * Used by !strad command to block requests when market is closed
 * and suggest alternative expiry dates.
 */
@Service
public class MarketCalendarService {

    private static final Logger logger = LoggerFactory.getLogger(MarketCalendarService.class);

    // US Eastern time zone (handles EST/EDT automatically)
    private static final ZoneId EASTERN = ZoneId.of("America/New_York");

    // Market hours
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(16, 0);

    // Cache for computed holidays (year -> set of holiday dates)
    private static final ConcurrentHashMap<Integer, Set<LocalDate>> holidayCache = new ConcurrentHashMap<>();

    /**
     * Check if the market is currently open.
     * 
     * @return true if market is open right now
     */
    public boolean isMarketOpen() {
        ZonedDateTime nowET = ZonedDateTime.now(EASTERN);
        return isMarketOpenAt(nowET);
    }

    /**
     * Check if market is open at a specific time.
     * 
     * @param dateTime the time to check
     * @return true if market would be open at that time
     */
    public boolean isMarketOpenAt(ZonedDateTime dateTime) {
        LocalDate date = dateTime.toLocalDate();
        LocalTime time = dateTime.toLocalTime();

        // Check if trading day
        if (!isTradingDay(date)) {
            return false;
        }

        // Check if within trading hours
        return !time.isBefore(MARKET_OPEN) && time.isBefore(MARKET_CLOSE);
    }

    /**
     * Check if a specific date is a trading day.
     * Trading days are weekdays that are not holidays.
     * 
     * @param date the date to check
     * @return true if it's a trading day
     */
    public boolean isTradingDay(LocalDate date) {
        // Check weekend
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }

        // Check holidays (dynamically calculated)
        Set<LocalDate> holidays = getHolidaysForYear(date.getYear());
        if (holidays.contains(date)) {
            return false;
        }

        return true;
    }

    /**
     * Get NYSE holidays for a specific year.
     * Calculates dynamically and caches the result.
     * 
     * NYSE Observed Holidays:
     * - New Year's Day (Jan 1, observed)
     * - MLK Day (3rd Monday of January)
     * - Presidents' Day (3rd Monday of February)
     * - Good Friday (Friday before Easter)
     * - Memorial Day (Last Monday of May)
     * - Juneteenth (June 19, observed)
     * - Independence Day (July 4, observed)
     * - Labor Day (1st Monday of September)
     * - Thanksgiving (4th Thursday of November)
     * - Christmas (Dec 25, observed)
     * 
     * @param year the year to get holidays for
     * @return set of holiday dates
     */
    public Set<LocalDate> getHolidaysForYear(int year) {
        return holidayCache.computeIfAbsent(year, this::calculateHolidaysForYear);
    }

    /**
     * Calculate all NYSE holidays for a given year.
     */
    private Set<LocalDate> calculateHolidaysForYear(int year) {
        Set<LocalDate> holidays = new HashSet<>();

        // New Year's Day (Jan 1) - observed on Friday if Saturday, Monday if Sunday
        holidays.add(observedDate(LocalDate.of(year, 1, 1)));

        // Martin Luther King Jr. Day - 3rd Monday of January
        holidays.add(nthDayOfWeek(year, Month.JANUARY, DayOfWeek.MONDAY, 3));

        // Presidents' Day - 3rd Monday of February
        holidays.add(nthDayOfWeek(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3));

        // Good Friday - calculated from Easter
        holidays.add(calculateGoodFriday(year));

        // Memorial Day - Last Monday of May
        holidays.add(lastDayOfWeek(year, Month.MAY, DayOfWeek.MONDAY));

        // Juneteenth (June 19) - observed
        holidays.add(observedDate(LocalDate.of(year, 6, 19)));

        // Independence Day (July 4) - observed
        holidays.add(observedDate(LocalDate.of(year, 7, 4)));

        // Labor Day - 1st Monday of September
        holidays.add(nthDayOfWeek(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1));

        // Thanksgiving - 4th Thursday of November
        holidays.add(nthDayOfWeek(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4));

        // Christmas (Dec 25) - observed
        holidays.add(observedDate(LocalDate.of(year, 12, 25)));

        logger.debug("Calculated {} NYSE holidays for year {}", holidays.size(), year);
        return holidays;
    }

    /**
     * Get the observed date for a holiday.
     * If the holiday falls on Saturday, it's observed on Friday.
     * If the holiday falls on Sunday, it's observed on Monday.
     */
    private LocalDate observedDate(LocalDate holiday) {
        DayOfWeek day = holiday.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY) {
            return holiday.minusDays(1); // Friday
        } else if (day == DayOfWeek.SUNDAY) {
            return holiday.plusDays(1); // Monday
        }
        return holiday;
    }

    /**
     * Get the nth occurrence of a day of week in a month.
     * E.g., 3rd Monday of January.
     */
    private LocalDate nthDayOfWeek(int year, Month month, DayOfWeek dayOfWeek, int n) {
        LocalDate first = LocalDate.of(year, month, 1);
        LocalDate firstOccurrence = first.with(TemporalAdjusters.firstInMonth(dayOfWeek));
        return firstOccurrence.plusWeeks(n - 1);
    }

    /**
     * Get the last occurrence of a day of week in a month.
     * E.g., last Monday of May.
     */
    private LocalDate lastDayOfWeek(int year, Month month, DayOfWeek dayOfWeek) {
        LocalDate last = LocalDate.of(year, month, 1).with(TemporalAdjusters.lastDayOfMonth());
        return last.with(TemporalAdjusters.previousOrSame(dayOfWeek));
    }

    /**
     * Calculate Good Friday for a given year.
     * Good Friday is the Friday before Easter Sunday.
     * 
     * Uses the Anonymous Gregorian algorithm to calculate Easter.
     */
    private LocalDate calculateGoodFriday(int year) {
        LocalDate easter = calculateEasterSunday(year);
        return easter.minusDays(2); // Friday before Easter Sunday
    }

    /**
     * Calculate Easter Sunday using the Anonymous Gregorian algorithm.
     * This algorithm is accurate for years 1583 to 4099.
     * 
     * @param year the year
     * @return the date of Easter Sunday
     */
    private LocalDate calculateEasterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;

        return LocalDate.of(year, month, day);
    }

    /**
     * Get why the market is closed.
     * 
     * @return human-readable reason, or null if market is open
     */
    public String getClosedReason() {
        ZonedDateTime nowET = ZonedDateTime.now(EASTERN);
        LocalDate today = nowET.toLocalDate();
        LocalTime time = nowET.toLocalTime();
        DayOfWeek day = today.getDayOfWeek();

        if (day == DayOfWeek.SATURDAY) {
            return "📅 Market is closed - it's Saturday";
        }
        if (day == DayOfWeek.SUNDAY) {
            return "📅 Market is closed - it's Sunday";
        }

        Set<LocalDate> holidays = getHolidaysForYear(today.getYear());
        if (holidays.contains(today)) {
            return "🎉 Market is closed for a US market holiday";
        }

        if (time.isBefore(MARKET_OPEN)) {
            return "⏰ Market hasn't opened yet (opens 9:30 AM ET)";
        }
        if (!time.isBefore(MARKET_CLOSE)) {
            return "⏰ Market is closed for the day (closed 4:00 PM ET)";
        }

        return null; // Market is open!
    }

    /**
     * Get the next trading day from today.
     * 
     * @return the next trading day
     */
    public LocalDate getNextTradingDay() {
        return getNextTradingDayAfter(LocalDate.now(EASTERN));
    }

    /**
     * Get the next trading day after a specific date.
     * 
     * @param date the starting date
     * @return the next trading day after that date
     */
    public LocalDate getNextTradingDayAfter(LocalDate date) {
        LocalDate next = date.plusDays(1);
        while (!isTradingDay(next)) {
            next = next.plusDays(1);
        }
        return next;
    }

    /**
     * Suggest valid expiry dates near the requested DTE.
     * Returns a list of suggested DTEs that fall on trading days.
     * 
     * @param requestedDte the originally requested DTE
     * @return list of suggested DTEs with their dates
     */
    public List<ExpiryOption> suggestNearbyExpiries(int requestedDte) {
        List<ExpiryOption> suggestions = new ArrayList<>();
        LocalDate today = LocalDate.now(EASTERN);

        // Find up to 3 valid trading days near the requested DTE
        int startDte = Math.max(0, requestedDte - 1);
        int endDte = requestedDte + 5;

        for (int dte = startDte; dte <= endDte && suggestions.size() < 3; dte++) {
            LocalDate targetDate = today.plusDays(dte);
            if (isTradingDay(targetDate)) {
                suggestions.add(new ExpiryOption(dte, targetDate));
            }
        }

        return suggestions;
    }

    /**
     * Format the market closed message with suggestions.
     * 
     * @param requestedDte the DTE that was requested
     * @return formatted message with suggestions
     */
    public String formatClosedMessage(int requestedDte) {
        StringBuilder sb = new StringBuilder();

        String reason = getClosedReason();
        if (reason != null) {
            sb.append(reason).append("\n\n");
        } else {
            // Check if the requested expiry itself is invalid
            LocalDate expiryDate = LocalDate.now(EASTERN).plusDays(requestedDte);
            if (!isTradingDay(expiryDate)) {
                sb.append("📅 The requested expiry (").append(expiryDate).append(") is not a trading day\n\n");
            }
        }

        sb.append("**Try one of these expiries instead:**\n");

        List<ExpiryOption> suggestions = suggestNearbyExpiries(requestedDte);
        for (ExpiryOption opt : suggestions) {
            sb.append("• `!strad ").append(opt.getDte()).append("` → ")
                    .append(opt.getDate().format(DateTimeFormatter.ofPattern("EEE, MMM d")))
                    .append("\n");
        }

        return sb.toString();
    }

    /**
     * Simple data class for expiry suggestions.
     */
    public static class ExpiryOption {
        private final int dte;
        private final LocalDate date;

        public ExpiryOption(int dte, LocalDate date) {
            this.dte = dte;
            this.date = date;
        }

        public int getDte() {
            return dte;
        }

        public LocalDate getDate() {
            return date;
        }
    }
}
