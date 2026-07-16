package com.skew.engine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Automatically grabs and archives full Charles Schwab API option chain snapshots
 * to local disk as structured JSON files for future ML training and quantitative backtesting.
 */
@Service
public class SchwabDataGrabberService {

    private static final Logger logger = LoggerFactory.getLogger(SchwabDataGrabberService.class);
    private static final String DEFAULT_ARCHIVE_DIR = "data/schwab_snapshots";

    private final SchwabApiService schwabApiService;
    private final ObjectMapper objectMapper;
    private final Path archivePath;

    @Value("${schwab.grabber.enabled:true}")
    private boolean grabberEnabled;

    @Value("${schwab.grabber.symbols:$SPX,SPY,QQQ}")
    private String targetSymbols;

    public SchwabDataGrabberService(SchwabApiService schwabApiService) {
        this.schwabApiService = schwabApiService;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.archivePath = Paths.get(DEFAULT_ARCHIVE_DIR);
        initDirectory();
    }

    private void initDirectory() {
        try {
            if (!Files.exists(archivePath)) {
                Files.createDirectories(archivePath);
                logger.info("Created Schwab snapshot archive directory at: {}", archivePath.toAbsolutePath());
            }
        } catch (IOException e) {
            logger.error("Failed to create snapshot directory {}: {}", archivePath, e.getMessage());
        }
    }

    /**
     * Archives a raw option chain response map to disk with a timestamped filename.
     */
    public synchronized String archiveSnapshot(String symbol, Map<String, Object> chainData) {
        if (chainData == null || chainData.isEmpty()) {
            return null;
        }

        try {
            initDirectory();
            String cleanSymbol = symbol.replace("$", "").trim().toUpperCase();
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = "%s_chain_%s.json".formatted(cleanSymbol, timestamp);
            File targetFile = archivePath.resolve(filename).toFile();

            // Add metadata wrapper
            Map<String, Object> wrappedData = new LinkedHashMap<>();
            wrappedData.put("capturedAt", LocalDateTime.now().toString());
            wrappedData.put("symbol", cleanSymbol);
            wrappedData.put("data", chainData);

            objectMapper.writeValue(targetFile, wrappedData);
            logger.info("📦 Archived Schwab option chain snapshot for {} -> {} ({} KB)",
                    cleanSymbol, filename, targetFile.length() / 1024);
            return targetFile.getAbsolutePath();
        } catch (Exception e) {
            logger.error("Failed to archive Schwab snapshot for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Actively fetches and archives the latest option chain for a given symbol from Schwab.
     */
    public String grabAndArchive(String symbol) {
        if (!schwabApiService.isAuthorized()) {
            logger.warn("Cannot grab Schwab data for {}: API not authorized.", symbol);
            return null;
        }
        try {
            Map<String, Object> chain = schwabApiService.getOptionsChain(symbol);
            return archiveSnapshot(symbol, chain);
        } catch (Exception e) {
            logger.error("Error grabbing Schwab data for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Scheduled task to grab full snapshots every 5 minutes during market hours.
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void scheduledGrab() {
        if (!grabberEnabled || !schwabApiService.isAuthorized()) {
            return;
        }
        logger.debug("Running scheduled Schwab API data grabber...");
        String[] symbols = targetSymbols.split(",");
        for (String sym : symbols) {
            grabAndArchive(sym.trim());
        }
    }

    /**
     * Lists all stored snapshot filenames and metadata.
     */
    public List<Map<String, Object>> listSnapshots() {
        try {
            if (!Files.exists(archivePath)) {
                return Collections.emptyList();
            }
            return Files.list(archivePath)
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(p -> -p.toFile().lastModified()))
                    .limit(100) // Return 100 most recent
                    .map(p -> {
                        File f = p.toFile();
                        Map<String, Object> meta = new HashMap<>();
                        meta.put("filename", f.getName());
                        meta.put("sizeBytes", f.length());
                        meta.put("sizeKb", f.length() / 1024);
                        meta.put("modifiedAt", new Date(f.lastModified()).toString());
                        return meta;
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Failed to list snapshots: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public boolean isGrabberEnabled() { return grabberEnabled; }
    public void setGrabberEnabled(boolean enabled) { this.grabberEnabled = enabled; }
}
