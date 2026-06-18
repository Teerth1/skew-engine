package com.skew.engine.bot;

import java.awt.Color;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.skew.engine.domain.Strategy;
import com.skew.engine.domain.StrategyType;
import com.skew.engine.domain.Leg;
import com.skew.engine.domain.CommandLog;
import com.skew.engine.repository.CommandLogRepository;
import com.skew.engine.domain.GexSnapshot;
import com.skew.engine.repository.GexSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

import jakarta.annotation.PostConstruct;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;

import com.skew.engine.service.BlackScholesService;
import com.skew.engine.service.MarketDataService;
import com.skew.engine.service.MassiveDataService;
import com.skew.engine.service.StrategyService;
import com.skew.engine.service.IndicatorService;
import com.skew.engine.service.SchwabApiService;
import com.skew.engine.service.MarketCalendarService;
import com.skew.engine.service.GexService;
import com.skew.engine.service.GexChartGenerator;

/**
 * Service class for Discord bot integration.
 */
@Service
public class DiscordBotService extends ListenerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(DiscordBotService.class);
    
    @Value("${discord.bot.token}")
    private String botToken;

    @Value("${discord.bot.channel}")
    private String channelId;

    @Value("${discord.bot.guild}")
    private String guildId;

    private final BlackScholesService bsService;
    private final CommandParserService parserService;
    private final MarketDataService marketService;
    private final MassiveDataService massiveService;
    private final StrategyService strategyService;
    private final CommandLogRepository commandLogRepo;
    private final IndicatorService indicatorService;
    private final SchwabApiService schwabService;
    private final MarketCalendarService marketCalendarService;

    private JDA jda;

    private final GexService gexService;
    private final GexChartGenerator gexChartGenerator;
    private final GexSnapshotRepository gexSnapshotRepository;
    private final ObjectMapper objectMapper;

    public DiscordBotService(BlackScholesService bsService, CommandParserService parserService,
            MarketDataService marketDataService, MassiveDataService massiveService,
            StrategyService strategyService, CommandLogRepository commandLogRepo,
            IndicatorService indicatorService, SchwabApiService schwabService,
            MarketCalendarService marketCalendarService, GexService gexService,
            GexChartGenerator gexChartGenerator, GexSnapshotRepository gexSnapshotRepository,
            ObjectMapper objectMapper) { 
        this.bsService = bsService;
        this.parserService = parserService;
        this.marketService = marketDataService;
        this.massiveService = massiveService;
        this.strategyService = strategyService;
        this.commandLogRepo = commandLogRepo;
        this.indicatorService = indicatorService;
        this.schwabService = schwabService;
        this.marketCalendarService = marketCalendarService; 
        this.gexService = gexService;
        this.gexChartGenerator = gexChartGenerator;
        this.gexSnapshotRepository = gexSnapshotRepository;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void startBot() throws InterruptedException {
        if (botToken == null || botToken.trim().isEmpty() || botToken.equals("YOUR_DISCORD_BOT_TOKEN")) {
            logger.warn("Discord bot token is missing or placeholder. Discord bot will not start.");
            return;
        }
        
        try {
            this.jda = JDABuilder.createDefault(botToken)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(this)
                    .setActivity(Activity.watching("Market Trends"))
                    .build();

            jda.awaitReady();

            if (guildId != null && !guildId.trim().isEmpty()) {
                var guild = jda.getGuildById(guildId);
                if (guild != null) {
                    guild.updateCommands().addCommands(
                            Commands.slash("stock", "Check a stock price")
                                    .addOption(OptionType.STRING, "ticker", "The symbol (e.g. AAPL)", true),

                            Commands.slash("optionprice", "Calculate Black-Scholes option price")
                                    .addOption(OptionType.NUMBER, "stockprice", "Current stock price", true)
                                    .addOption(OptionType.NUMBER, "strikeprice", "Option strike price", true)
                                    .addOption(OptionType.INTEGER, "daystoexpire", "Days to expiration", true)
                                    .addOption(OptionType.NUMBER, "volatility", "Stock volatility (e.g. 0.2 for 20%)", true),

                            Commands.slash("price", "Check a product deal")
                                    .addOption(OptionType.STRING, "product", "Product name", true),

                            Commands.slash("buy", "Quickly add a contract (e.g. NVDA 150c 30d)")
                                    .addOption(OptionType.STRING, "contract", "Format: Ticker Strike+Type Days (e.g. NVDA 150c 30d)", true)
                                    .addOption(OptionType.NUMBER, "price", "The price you paid (e.g. 1.50)", true),
                            
                            Commands.slash("portfolio", "View your active positions"),

                            Commands.slash("sell", "Close a specific position by ID")
                                    .addOption(OptionType.INTEGER, "id", "Position ID from /portfolio", true),

                            Commands.slash("sellall", "Close all positions for a ticker")
                                    .addOption(OptionType.STRING, "ticker", "Ticker symbol", true),

                            Commands.slash("analyze", "Analyze your portfolio or a specific contract")
                                    .addOption(OptionType.STRING, "query", "Optional: Contract (e.g. NVDA 150c 30d). Leave empty to analyze portfolio", false)
                                    .addOption(OptionType.NUMBER, "volatility", "Optional: Custom volatility (default 0.4)", false),

                            Commands.slash("view", "View another user's portfolio")
                                    .addOption(OptionType.USER, "user", "Select a user", true),

                            Commands.slash("liquidity", "Check liquidity for a specific contract")
                                    .addOption(OptionType.STRING, "contract", "Contract (e.g. NVDA 150c 30d)", true),

                            Commands.slash("spread", "Open/close a spread (fly, vertical, straddle)")
                                    .addOption(OptionType.STRING, "type", "FLY, VERTICAL, STRADDLE", true)
                                    .addOption(OptionType.STRING, "action", "OPEN or CLOSE", true)
                                    .addOption(OptionType.STRING, "ticker", "Underlying (SPX, SPY, etc.)", true)
                                    .addOption(OptionType.INTEGER, "wing1", "Lower strike", true)
                                    .addOption(OptionType.INTEGER, "wing2", "Upper strike", true)
                                    .addOption(OptionType.STRING, "option_type", "c (call) or p (put)", true)
                                    .addOption(OptionType.INTEGER, "dte", "Days to expiration (0 for 0DTE)", true)
                                    .addOption(OptionType.NUMBER, "cost", "Net debit/credit (optional)", false),

                            Commands.slash("stats", "View bot usage statistics and capacity"),

                            Commands.slash("gex", "Show Gamma Exposure map for a ticker (SPX, SPY, etc.)")
                                    .addOption(OptionType.STRING, "ticker", "Symbol (e.g. SPY, SPX)", true)
                                    .addOption(OptionType.STRING, "layout", "H or V (optional)", false)
                                    .addOption(OptionType.INTEGER, "dte", "Target DTE (e.g. 0 for 0DTE, optional)", false),

                            Commands.slash("intraday", "Show Intraday GEX Heatmap")
                                    .addOption(OptionType.STRING, "ticker", "Symbol (e.g. SPY, SPX)", true),

                            Commands.slash("ic", "Open an iron condor (4 strikes)")
                                    .addOption(OptionType.STRING, "action", "OPEN or CLOSE", true)
                                    .addOption(OptionType.STRING, "ticker", "Underlying (SPX, SPY)", true)
                                    .addOption(OptionType.INTEGER, "put_buy", "Long put strike (lowest)", true)
                                    .addOption(OptionType.INTEGER, "put_sell", "Short put strike", true)
                                    .addOption(OptionType.INTEGER, "call_sell", "Short call strike", true)
                                    .addOption(OptionType.INTEGER, "call_buy", "Long call strike (highest)", true)
                                    .addOption(OptionType.INTEGER, "dte", "Days to expiration", true)
                                    .addOption(OptionType.NUMBER, "cost", "Net credit (optional)", false),

                            Commands.slash("indicator", "Get mean reversion indicators for a ticker")
                                    .addOption(OptionType.STRING, "ticker", "Stock symbol (e.g. SPY)", true),

                            Commands.slash("straddle", "Open a straddle (call + put at same strike)")
                                    .addOption(OptionType.STRING, "action", "OPEN or CLOSE", true)
                                    .addOption(OptionType.STRING, "ticker", "Underlying (SPY, QQQ, etc.)", true)
                                    .addOption(OptionType.INTEGER, "strike", "Strike price for both legs", true)
                                    .addOption(OptionType.INTEGER, "dte", "Days to expiration", true)
                                    .addOption(OptionType.NUMBER, "cost", "Net debit paid (optional)", false),

                            Commands.slash("vertical", "Open a vertical spread (2 strikes)")
                                    .addOption(OptionType.STRING, "action", "OPEN or CLOSE", true)
                                    .addOption(OptionType.STRING, "ticker", "Underlying (SPY, QQQ, etc.)", true)
                                    .addOption(OptionType.STRING, "type", "CALL or PUT", true)
                                    .addOption(OptionType.INTEGER, "long_strike", "Strike you BUY", true)
                                    .addOption(OptionType.INTEGER, "short_strike", "Strike you SELL", true)
                                    .addOption(OptionType.INTEGER, "dte", "Days to expiration", true)
                                    .addOption(OptionType.NUMBER, "cost", "Net debit/credit (optional)", false),

                            Commands.slash("fly", "Open a butterfly spread (3 strikes)")
                                    .addOption(OptionType.STRING, "action", "OPEN or CLOSE", true)
                                    .addOption(OptionType.STRING, "ticker", "Underlying (SPY, QQQ, etc.)", true)
                                    .addOption(OptionType.STRING, "type", "CALL or PUT", true)
                                    .addOption(OptionType.INTEGER, "low", "Lower wing strike (BUY)", true)
                                    .addOption(OptionType.INTEGER, "mid", "Middle strike (SELL x2)", true)
                                    .addOption(OptionType.INTEGER, "high", "Upper wing strike (BUY)", true)
                                    .addOption(OptionType.INTEGER, "dte", "Days to expiration", true)
                                    .addOption(OptionType.NUMBER, "cost", "Net debit paid (optional)", false)
                    ).queue();
                } else {
                    logger.error("Guild with ID {} not found. Commands not updated.", guildId);
                }
            }
        } catch (Exception e) {
            logger.error("Error starting Discord bot: {}", e.getMessage(), e);
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        logCommand(event.getName(), event.getUser().getName());

        if (event.getName().equals("stock")) {
            stockSlash(event);
        } else if (event.getName().equals("price")) {
            priceSlash(event);
        } else if (event.getName().equals("optionprice")) {
            optionPriceSlash(event);
        } else if (event.getName().equals("buy")) {
            buySlash(event);
        } else if (event.getName().equals("portfolio")) {
            portfolioSlash(event);
        } else if (event.getName().equals("sell")) {
            sellSlash(event);
        } else if (event.getName().equals("sellall")) {
            sellAllSlash(event);
        } else if (event.getName().equals("analyze")) {
            analyzerSlash(event);
        } else if (event.getName().equals("view")) {
            viewSlash(event);
        } else if (event.getName().equals("liquidity")) {
            liquiditySlash(event);
        } else if (event.getName().equals("spread")) {
            spreadSlash(event);
        } else if (event.getName().equals("stats")) {
            statsSlash(event);
        } else if (event.getName().equals("ic")) {
            icSlash(event);
        } else if (event.getName().equals("indicator")) {
            indicatorSlash(event);
        } else if (event.getName().equals("straddle")) {
            straddleSlash(event);
        } else if (event.getName().equals("vertical")) {
            verticalSlash(event);
        } else if (event.getName().equals("fly")) {
            flySlash(event);
        } else if (event.getName().equals("gex")) {
            gexSlash(event);
        } else if (event.getName().equals("intraday")) {
            intradaySlash(event);
        }
    }

    @Scheduled(cron = "0 29 9 * * MON-FRI", zone = "America/New_York")
    public void alertPreMarket() {
        sendStraddleAlert(0);
    }

    @Scheduled(cron = "0 32 9 * * MON-FRI", zone = "America/New_York")
    public void alertMarketOpen() {
        sendStraddleAlert(0);
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "America/New_York")
    public void tokenKeepalive() {
        logger.info("Running daily token keepalive...");
        try {
            schwabService.refreshAccessToken();
            logger.info("Token keepalive succeeded.");
        } catch (Exception e) {
            logger.error("Token keepalive FAILED - manual intervention may be required!", e);
        }
    }

    private void sendStraddleAlert(int dte) {
        if (jda == null || channelId == null || channelId.trim().isEmpty()) return;
        TextChannel channel = jda.getTextChannelById(channelId);

        if (channel != null) {
            channel.sendMessage("⏰ **Automated Market Alert** for " + dte + " DTE:").queue();

            Optional<SchwabApiService.SPXStraddle> straddleData = schwabService.getSpxStraddle(dte);

            if (straddleData.isPresent()) {
                SchwabApiService.SPXStraddle straddle = straddleData.get();
                String response = formatStraddleResponse(straddle);
                channel.sendMessage(response).queue();
            } else {
                channel.sendMessage("❌ Failed to fetch automated straddle.").queue();
            }
        }
    }

    private void logCommand(String command, String userId) {
        try {
            commandLogRepo.save(new CommandLog(command, userId));
        } catch (Exception e) {
            logger.error("Failed to log command to database: {}", e.getMessage());
        }
    }

    private void statsSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue();

        try {
            LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
            LocalDateTime startOfWeek = LocalDate.now().minusDays(7).atStartOfDay();

            long commandsToday = commandLogRepo.countToday(startOfToday);
            long commandsThisWeek = commandLogRepo.countByTimestampAfter(startOfWeek);
            long totalCommands = commandLogRepo.count();
            long uniqueUsers = commandLogRepo.countDistinctUsers();

            long capacityPerDay = 10000;

            EmbedBuilder eb = new EmbedBuilder();
            eb.setTitle("📊 Bot Statistics & Capacity");
            eb.setColor(Color.decode("#3498db"));

            eb.addField("📈 Usage",
                    "Today: **" + commandsToday + "** commands\n" +
                            "This Week: **" + commandsThisWeek + "** commands\n" +
                            "All Time: **" + totalCommands + "** commands",
                    true);

            eb.addField("⚡ System Capacity",
                    "Daily Limit: **" + String.format("%,d", capacityPerDay) + "** commands\n" +
                            "Active Users: **" + uniqueUsers + "**\n" +
                            "Status: **🟢 Healthy**",
                    true);

            java.util.List<Object[]> topCommands = commandLogRepo.countByCommandGrouped();
            if (!topCommands.isEmpty()) {
                StringBuilder topSb = new StringBuilder();
                int shown = 0;
                for (Object[] row : topCommands) {
                    if (shown >= 5)
                        break;
                    topSb.append("/" + row[0] + ": " + row[1] + "\n");
                    shown++;
                }
                eb.addField("🏆 Top Commands", topSb.toString(), false);
            }

            eb.setFooter("Built to scale • Railway + PostgreSQL");
            event.getHook().sendMessageEmbeds(eb.build()).queue();

        } catch (Exception e) {
            event.getHook().sendMessage("❌ Error fetching stats: " + e.getMessage()).queue();
        }
    }

    private void indicatorSlash(SlashCommandInteractionEvent event) {
        String ticker = event.getOption("ticker").getAsString().toUpperCase();
        event.deferReply().queue();

        try {
            java.util.Map<String, Object> data = indicatorService.getAllIndicators(ticker);

            Double zscore = (Double) data.get("zscore");
            String signal = (String) data.get("signal");
            Double halfLife = (Double) data.get("half_life");
            Double acf = (Double) data.get("acf");

            Color embedColor;
            if ("OVERSOLD".equals(signal)) {
                embedColor = Color.GREEN;
            } else if ("OVERBOUGHT".equals(signal)) {
                embedColor = Color.RED;
            } else {
                embedColor = Color.GRAY;
            }

            EmbedBuilder eb = new EmbedBuilder();
            eb.setTitle("📊 Mean Reversion Indicators: " + ticker);
            eb.setColor(embedColor);

            String zEmoji = zscore < -2 ? "🟢" : (zscore > 2 ? "🔴" : "⚪");
            eb.addField("Z-Score", zEmoji + " " + String.format("%.2f", zscore) + " (" + signal + ")", true);

            String hlEmoji = halfLife < 50 ? "✅" : "⚠️";
            eb.addField("Half-Life", hlEmoji + " " + String.format("%.1f", halfLife) + " bars", true);

            String acfEmoji = acf < -0.05 ? "📉 Mean Reverting" : (acf > 0.05 ? "📈 Trending" : "➡️ Neutral");
            eb.addField("ACF Lag-1", String.format("%.4f", acf) + " " + acfEmoji, false);

            eb.setFooter("Powered by Python Indicators API");
            event.getHook().sendMessageEmbeds(eb.build()).queue();

        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            event.getHook().sendMessage("❌ Error: " + errMsg + "\n⚠️ Lambda API may be initializing. Try again in a few seconds.").queue();
        }
    }

    private void gexSlash(SlashCommandInteractionEvent event) {
        String ticker = event.getOption("ticker").getAsString().toUpperCase();
        
        boolean isHorizontal = false;
        if (event.getOption("layout") != null) {
            String layoutStr = event.getOption("layout").getAsString().toUpperCase();
            if (layoutStr.equals("H") || layoutStr.equals("HORIZONTAL")) {
                isHorizontal = true;
            }
        }

        Integer targetDte = null;
        if (event.getOption("dte") != null) {
            targetDte = event.getOption("dte").getAsInt();
        }
        
        event.deferReply().queue();

        try {
            Optional<com.fasterxml.jackson.databind.JsonNode> chainOpt = schwabService.getFullOptionChain(ticker);

            if (chainOpt.isEmpty()) {
                event.getHook().sendMessage("❌ Could not fetch option chain for **" + ticker + "**.\nCheck the ticker symbol or try again in a moment.").queue();
                return;
            }

            Optional<GexService.GexResult> resultOpt = gexService.calculateGex(chainOpt.get(), ticker, targetDte);

            if (resultOpt.isEmpty()) {
                event.getHook().sendMessage("❌ GEX calculation failed for **" + ticker + "**.\nThe option chain may not have gamma data available.").queue();
                return;
            }

            byte[] imageBytes = gexChartGenerator.generateChart(resultOpt.get(), isHorizontal);
            if (imageBytes != null) {
                net.dv8tion.jda.api.utils.FileUpload file = net.dv8tion.jda.api.utils.FileUpload.fromData(imageBytes, "gex_" + ticker + ".png");
                
                EmbedBuilder eb = new EmbedBuilder();
                eb.setColor(java.awt.Color.decode("#111111"));
                eb.setTitle(ticker + " Gamma Exposure Profile");
                eb.setImage("attachment://gex_" + ticker + ".png");
                eb.setFooter("Powered by Graphics2D");
                
                event.getHook().sendMessageEmbeds(eb.build()).addFiles(file).queue();
            } else {
                event.getHook().sendMessage("❌ Failed to generate GEX chart image.").queue();
            }

        } catch (Exception e) {
            logger.error("Error in /gex command for {}", ticker, e);
            event.getHook().sendMessage("❌ Unexpected error: " + e.getMessage()).queue();
        }
    }

    private void intradaySlash(SlashCommandInteractionEvent event) {
        String ticker = event.getOption("ticker").getAsString().toUpperCase();
        event.deferReply().queue();

        try {
            LocalDate today = LocalDate.now();
            LocalDateTime startOfDay = today.atStartOfDay();
            LocalDateTime endOfDay = today.atTime(LocalTime.MAX);

            java.util.List<GexSnapshot> history = gexSnapshotRepository
                .findByTickerAndTimestampBetweenOrderByTimestampAsc(ticker, startOfDay, endOfDay);

            if (history == null || history.isEmpty()) {
                event.getHook().sendMessage("❌ No intraday GEX data available for " + ticker + " yet today.").queue();
                return;
            }

            byte[] imageBytes = gexChartGenerator.generateIntradayHeatmap(history, objectMapper);
            
            if (imageBytes != null) {
                net.dv8tion.jda.api.utils.FileUpload file = net.dv8tion.jda.api.utils.FileUpload.fromData(imageBytes, "intraday_" + ticker + ".png");
                
                EmbedBuilder eb = new EmbedBuilder();
                eb.setColor(java.awt.Color.decode("#111111"));
                eb.setTitle(ticker + " Intraday GEX Heatmap");
                eb.setDescription(history.size() + " snapshots tracked today.");
                eb.setImage("attachment://intraday_" + ticker + ".png");
                eb.setFooter("Powered by DealAggregator Analytics");
                
                event.getHook().sendMessageEmbeds(eb.build()).addFiles(file).queue();
            } else {
                event.getHook().sendMessage("❌ Failed to generate intraday heatmap.").queue();
            }

        } catch (Exception e) {
            logger.error("Error in /intraday command for {}", ticker, e);
            event.getHook().sendMessage("❌ Unexpected error: " + e.getMessage()).queue();
        }
    }

    private String formatGexLadder(GexService.GexResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("```\n");
        sb.append("[ ").append(result.symbol).append(" GAMMA MAP ]\n");
        sb.append(String.format("Spot: $%.2f%n", result.spotPrice));
        sb.append(String.format("🟢 Call Wall: $%.0f  |  🔴 Put Wall: $%.0f", result.callWall, result.putWall));
        if (result.hasZeroFlip) {
            sb.append(String.format("  |  ⚡ Zero Flip: $%.0f", result.zeroFlip));
        }
        sb.append("\n\n");
        sb.append(String.format("  %-8s | %-13s | %s%n", "Strike", "Net GEX", ""));
        sb.append("------------------------------------------\n");

        GexService.GexRow spotRow = null;
        double minDist = Double.MAX_VALUE;
        for (GexService.GexRow row : result.rows) {
            double dist = Math.abs(row.strike - result.spotPrice);
            if (dist < minDist) { minDist = dist; spotRow = row; }
        }

        for (GexService.GexRow row : result.rows) {
            boolean nearSpot = Math.abs(row.strike - result.spotPrice) <= result.spotPrice * 0.02;
            boolean isMilestone = row.label != null;
            if (!nearSpot && !isMilestone) continue;

            double gexBillions = row.netGex / 1_000_000_000.0;
            String gexStr = String.format("%+.2fB", gexBillions);

            String label = isMilestone ? row.label : (row == spotRow ? "<-- SPOT" : "");
            sb.append(String.format("  %-8.2f | %-13s | %s%n", row.strike, gexStr, label));
        }

        sb.append("```");
        return sb.toString();
    }

    private void stockSlash(SlashCommandInteractionEvent event) {
        String ticker = event.getOption("ticker").getAsString();
        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle("📊 Market Data: " + ticker);
        eb.setColor(java.awt.Color.CYAN);
        eb.setImage("https://charts2.finviz.com/chart.ashx?t=" + ticker);
        eb.setFooter("Real-time Data via DealAggregator");

        event.replyEmbeds(eb.build()).queue();
    }

    private void priceSlash(SlashCommandInteractionEvent event) {
        String query = event.getOption("product").getAsString();
        event.reply("🔍 Searching database for deals on: **" + query + "**...").setEphemeral(true).queue();
    }

    private void liquiditySlash(SlashCommandInteractionEvent event) {
        String query = event.getOption("contract").getAsString();
        event.deferReply(false).queue();

        try {
            CommandParserService.ParsedOption opt = parserService.parse(query);
            Optional<MassiveDataService.OptionSnapshot> snapshotOpt = massiveService.getOptionSnapshot(opt.ticker, opt.strike, opt.type, opt.days);

            if (snapshotOpt.isPresent()) {
                MassiveDataService.OptionSnapshot snap = snapshotOpt.get();
                String rating = "❓ UNKNOWN";
                if (snap.getOpenInterest() > 5000 && snap.getVolume() > 500)
                    rating = "✅ EXCELLENT";
                else if (snap.getOpenInterest() > 1000)
                    rating = "⚠️ GOOD";
                else
                    rating = "🔴 POOR";

                EmbedBuilder eb = new EmbedBuilder();
                eb.setTitle("📈 Liquidity Check: " + query);
                eb.setColor(Color.CYAN);
                eb.addField("Volume", String.valueOf(snap.getVolume()), true);
                eb.addField("Open Interest", String.valueOf(snap.getOpenInterest()), true);
                eb.addField("Rating", rating, false);
                eb.setFooter("Spread: $" + String.format("%.2f", (snap.getAsk() - snap.getBid())));

                event.getHook().sendMessageEmbeds(eb.build()).queue();
            } else {
                event.getHook().sendMessage("❌ No data found for this contract.").queue();
            }
        } catch (Exception e) {
            event.getHook().sendMessage("❌ Error: " + e.getMessage()).queue();
        }
    }

    private void sellSlash(SlashCommandInteractionEvent event) {
        long id = event.getOption("id").getAsLong();
        String userId = event.getUser().getName();
        event.deferReply().queue();

        try {
            List<Strategy> userStrategies = strategyService.getOpenStrategies(userId);
            Strategy target = null;

            for (Strategy s : userStrategies) {
                if (s.getId() == id) {
                    target = s;
                    break;
                }
            }

            if (target != null) {
                strategyService.closeStrategy(id);
                event.getHook().sendMessage("✅ **Closed Strategy #" + id + ":** " + target.getTicker() + " (" + target.getStrategy() + ") - " + target.getLegs().size() + " leg(s)").queue();
            } else {
                event.getHook().sendMessage("❌ Strategy #" + id + " not found or doesn't belong to you.").queue();
            }
        } catch (Exception e) {
            event.getHook().sendMessage("❌ Error closing strategy: " + e.getMessage()).queue();
        }
    }

    private void buySlash(SlashCommandInteractionEvent event) {
        String query = event.getOption("contract").getAsString();
        double price = event.getOption("price").getAsDouble();
        String userId = event.getUser().getName();
        event.deferReply().queue();

        try {
            CommandParserService.ParsedOption opt = parserService.parse(query);
            LocalDate expiration = LocalDate.now().plusDays(opt.days);

            Leg leg = new Leg(opt.type, opt.strike, expiration, price, 1);
            Strategy strategy = strategyService.openStrategy(userId, "SINGLE", opt.ticker, List.of(leg));

            event.getHook().sendMessage("✅ **Position Opened:** " + opt.ticker + " $" + opt.strike + " " + opt.type.toUpperCase() + " (Exp: " + expiration + ") @ $" + price + "\n📋 Strategy ID: " + strategy.getId()).queue();
        } catch (Exception e) {
            event.getHook().sendMessage("❌ Error: " + e.getMessage() + "\nTry format: `NVDA 150c 30d`").queue();
        }
    }

    private void spreadSlash(SlashCommandInteractionEvent event) {
        String typeInput = event.getOption("type").getAsString().toUpperCase();
        String action = event.getOption("action").getAsString().toUpperCase();
        String ticker = event.getOption("ticker").getAsString().toUpperCase();
        int wing1 = event.getOption("wing1").getAsInt();
        int wing2 = event.getOption("wing2").getAsInt();
        String optionType = event.getOption("option_type").getAsString().toLowerCase();
        int dte = event.getOption("dte").getAsInt();
        Double netCost = event.getOption("cost") != null ? event.getOption("cost").getAsDouble() : null;
        String userId = event.getUser().getName();

        event.deferReply().queue();

        try {
            if (action.equals("CLOSE")) {
                event.getHook().sendMessage("❌ CLOSE not implemented yet. Use /sell <id>").queue();
                return;
            }

            StrategyType strategyType = StrategyType.fromString(typeInput);
            LocalDate expiration = LocalDate.now().plusDays(dte);
            String optSuffix = optionType.equals("p") ? "p" : "c";
            String[] strikes;

            if (strategyType == StrategyType.FLY) {
                strikes = new String[] { wing1 + optSuffix, wing2 + optSuffix };
            } else if (strategyType == StrategyType.VERTICAL) {
                strikes = new String[] { wing1 + optSuffix, wing2 + optSuffix };
            } else if (strategyType == StrategyType.STRADDLE) {
                strikes = new String[] { wing1 + optSuffix };
            } else {
                throw new IllegalArgumentException("Unsupported type: " + typeInput);
            }

            ArrayList<Leg> legs = generateLegs(strategyType, strikes, expiration);
            Strategy strategy = strategyService.openStrategy(userId, strategyType.name(), ticker, legs, netCost);

            String response = buildSpreadResponse(strategyType, ticker, legs, netCost != null ? netCost : 0.0, expiration, strategy.getId());
            event.getHook().sendMessage(response).queue();

        } catch (Exception e) {
            event.getHook().sendMessage("❌ Error: " + e.getMessage() + "\nFormat: `/spread fly open SPX 6800 6850 c 0 5.80`").queue();
        }
    }

    private void icSlash(SlashCommandInteractionEvent event) {
        String action = event.getOption("action").getAsString().toUpperCase();
        String ticker = event.getOption("ticker").getAsString().toUpperCase();
        int putBuy = event.getOption("put_buy").getAsInt();
        int putSell = event.getOption("put_sell").getAsInt();
        int callSell = event.getOption("call_sell").getAsInt();
        int callBuy = event.getOption("call_buy").getAsInt();
        int dte = event.getOption("dte").getAsInt();
        Double netCost = event.getOption("cost") != null ? event.getOption("cost").getAsDouble() : null;
        String userId = event.getUser().getName();

        event.deferReply().queue();

        try {
            if (action.equals("CLOSE")) {
                event.getHook().sendMessage("❌ CLOSE not implemented yet. Use /sell <id>").queue();
                return;
            }

            LocalDate expiration = LocalDate.now().plusDays(dte);

            ArrayList<Leg> legs = new ArrayList<>();
            legs.add(new Leg("put", (double) putBuy, expiration, 0.0, 1));
            legs.add(new Leg("put", (double) putSell, expiration, 0.0, -1));
            legs.add(new Leg("call", (double) callSell, expiration, 0.0, -1));
            legs.add(new Leg("call", (double) callBuy, expiration, 0.0, 1));

            Strategy strategy = strategyService.openStrategy(userId, "IRON_CONDOR", ticker, legs, netCost);

            String response = buildSpreadResponse(StrategyType.IRON_CONDOR, ticker, legs, netCost != null ? netCost : 0.0, expiration, strategy.getId());
            event.getHook().sendMessage(response).queue();

        } catch (Exception e) {
            event.getHook().sendMessage("❌ Error: " + e.getMessage() + "\nFormat: `/ic open SPX 6700 6750 6850 6900 0 2.50`").queue();
        }
    }

    private String buildSpreadResponse(StrategyType type, String ticker, ArrayList<Leg> legs,
            double netCost, LocalDate expiration, Long strategyId) {
        StringBuilder sb = new StringBuilder();
        sb.append("✅ **" + type + " Opened:** " + ticker + "\n");

        for (Leg leg : legs) {
            String direction = leg.getQuantity() > 0 ? "📈 LONG" : "📉 SHORT";
            int qty = Math.abs(leg.getQuantity());
            String qtyStr = qty > 1 ? " x" + qty : "";
            sb.append(direction + qtyStr + " $" + leg.getStrikePrice().intValue() + " " + leg.getOptionType().toUpperCase() + "\n");
        }

        sb.append("💰 Net Cost: $" + String.format("%.2f", netCost) + "\n");
        long dte = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), expiration);
        sb.append("📅 Expires: " + expiration + " (" + dte + "DTE)\n");
        sb.append("📋 Strategy ID: " + strategyId);

        return sb.toString();
    }

    private ArrayList<Leg> generateLegs(StrategyType type, String[] strikes, LocalDate expiration) {
        ArrayList<Leg> legs = new ArrayList<>();

        switch (type) {
            case FLY:
                validateStrikeCount(strikes, 2, "Butterfly");
                legs.addAll(generateButterfly(strikes[0], strikes[1], expiration));
                break;

            case VERTICAL:
                validateStrikeCount(strikes, 2, "Vertical spread");
                legs.addAll(generateVertical(strikes[0], strikes[1], expiration));
                break;

            case STRADDLE:
                validateStrikeCount(strikes, 1, "Straddle");
                legs.addAll(generateStraddle(strikes[0], expiration));
                break;

            case IRON_CONDOR:
                validateStrikeCount(strikes, 4, "Iron Condor");
                legs.addAll(generateIronCondor(strikes, expiration));
                break;

            default:
                throw new IllegalArgumentException("Unsupported strategy type: " + type);
        }

        return legs;
    }

    private void validateStrikeCount(String[] strikes, int required, String strategyName) {
        if (strikes.length < required) {
            throw new IllegalArgumentException(strategyName + " needs " + required + " strike(s)");
        }
    }

    private ArrayList<Leg> generateButterfly(String lowStrike, String highStrike, LocalDate expiration) {
        ArrayList<Leg> legs = new ArrayList<>();

        double low = parseStrikeValue(lowStrike);
        double high = parseStrikeValue(highStrike);
        double middle = (low + high) / 2;
        String optionType = parseOptionType(lowStrike);

        legs.add(new Leg(optionType, low, expiration, 0.0, 1));
        legs.add(new Leg(optionType, middle, expiration, 0.0, -2));
        legs.add(new Leg(optionType, high, expiration, 0.0, 1));

        return legs;
    }

    private ArrayList<Leg> generateVertical(String lowStrike, String highStrike, LocalDate expiration) {
        ArrayList<Leg> legs = new ArrayList<>();

        double low = parseStrikeValue(lowStrike);
        double high = parseStrikeValue(highStrike);
        String optionType = parseOptionType(lowStrike);

        legs.add(new Leg(optionType, low, expiration, 0.0, 1));
        legs.add(new Leg(optionType, high, expiration, 0.0, -1));

        return legs;
    }

    private ArrayList<Leg> generateStraddle(String strike, LocalDate expiration) {
        ArrayList<Leg> legs = new ArrayList<>();
        double strikePrice = parseStrikeValue(strike);
        legs.add(new Leg("call", strikePrice, expiration, 0.0, 1));
        legs.add(new Leg("put", strikePrice, expiration, 0.0, 1));
        return legs;
    }

    private ArrayList<Leg> generateIronCondor(String[] strikes, LocalDate expiration) {
        ArrayList<Leg> legs = new ArrayList<>();

        double s1 = parseStrikeValue(strikes[0]);
        double s2 = parseStrikeValue(strikes[1]);
        double s3 = parseStrikeValue(strikes[2]);
        double s4 = parseStrikeValue(strikes[3]);

        legs.add(new Leg("put", s1, expiration, 0.0, 1));
        legs.add(new Leg("put", s2, expiration, 0.0, -1));
        legs.add(new Leg("call", s3, expiration, 0.0, -1));
        legs.add(new Leg("call", s4, expiration, 0.0, 1));

        return legs;
    }

    private double parseStrikeValue(String strikeStr) {
        String cleaned = strikeStr.toLowerCase().replaceAll("[cp]", "");
        return Double.parseDouble(cleaned);
    }

    private String parseOptionType(String strikeStr) {
        if (strikeStr.toLowerCase().endsWith("c")) {
            return "call";
        } else if (strikeStr.toLowerCase().endsWith("p")) {
            return "put";
        }
        throw new IllegalArgumentException("Strike must end with 'c' or 'p': " + strikeStr);
    }

    private void sellAllSlash(SlashCommandInteractionEvent event) {
        String ticker = event.getOption("ticker").getAsString().toUpperCase();
        String userId = event.getUser().getName();
        event.deferReply().queue();

        try {
            List<Strategy> strategies = strategyService.getOpenStrategies(userId);
            int closedCount = 0;

            for (Strategy s : strategies) {
                if (s.getTicker().equalsIgnoreCase(ticker)) {
                    strategyService.closeStrategy(s.getId());
                    closedCount++;
                }
            }

            if (closedCount > 0) {
                event.getHook().sendMessage("✅ Closed all positions for **" + ticker + "** (" + closedCount + " strategies)").queue();
            } else {
                event.getHook().sendMessage("❌ No positions found for **" + ticker + "**").queue();
            }
        } catch (Exception e) {
            event.getHook().sendMessage("❌ Error: " + e.getMessage()).queue();
        }
    }

    private void portfolioSlash(SlashCommandInteractionEvent event) {
        String userId = event.getUser().getName();
        event.deferReply().queue();

        List<Strategy> strategies = strategyService.getOpenStrategies(userId);
        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle("💼 " + userId + "'s Portfolio");
        eb.setColor(Color.decode("#2ecc71"));

        if (strategies.isEmpty()) {
            eb.setDescription("No active positions. Use `/buy` to add one.");
        } else {
            StringBuilder sb = new StringBuilder();
            for (Strategy s : strategies) {
                sb.append("**#" + s.getId() + " " + s.getTicker() + "** (" + s.getStrategy() + ")\n");
                boolean isMultiLeg = s.getLegs().size() > 1;

                for (Leg leg : s.getLegs()) {
                    String legDir = leg.getQuantity() > 0 ? "📈" : "📉";
                    int qty = Math.abs(leg.getQuantity());
                    String qtyStr = qty > 1 ? " x" + qty : "";

                    if (isMultiLeg) {
                        sb.append(legDir + qtyStr + " $" + leg.getStrikePrice().intValue() + " " + leg.getOptionType().toUpperCase() + " (Exp: " + leg.getExpiration() + ")\n");
                    } else {
                        sb.append(legDir + " $" + leg.getStrikePrice() + " " + leg.getOptionType().toUpperCase() + " @ $" + leg.getEntryPrice() + " (Exp: " + leg.getExpiration() + ")\n");
                    }
                }

                if (isMultiLeg && s.getNetCost() != null) {
                    sb.append("💰 Net Debit: $" + String.format("%.2f", s.getNetCost()) + "\n");
                }
                sb.append("\n");
            }

            eb.setDescription(sb.toString());
            eb.setFooter("Total positions: " + strategies.size() + " | Use /sell <id> to close");
        }
        event.getHook().sendMessageEmbeds(eb.build()).queue();
    }

    private void optionPriceSlash(SlashCommandInteractionEvent event) {
        double stockPrice = event.getOption("stockprice").getAsDouble();
        double strikePrice = event.getOption("strikeprice").getAsDouble();
        int days = event.getOption("daystoexpire").getAsInt();
        double volatility = event.getOption("volatility").getAsDouble();

        double timeInYears = days / 365.0;
        double riskFreeRate = 0.05;

        double fairValue = bsService.blackScholes(stockPrice, strikePrice, timeInYears, volatility, riskFreeRate, "call");

        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle("🧮 Option Fair Value Calculator");
        eb.setColor(java.awt.Color.ORANGE);

        eb.addField("Market Data", "Stock: $" + stockPrice + "\nStrike: $" + strikePrice + "\nVol: " + (volatility * 100) + "%", false);
        eb.addField("Theoretical Call Price", "$" + String.format("%.2f", fairValue), false);
        eb.setFooter("Black-Scholes Model via DealAggregator");
        eb.setTimestamp(java.time.Instant.now());

        event.replyEmbeds(eb.build()).queue();
    }

    private void analyzerSlash(SlashCommandInteractionEvent event) {
        String userId = event.getUser().getName();
        double volatility = 0.4;
        if (event.getOption("volatility") != null) {
            volatility = event.getOption("volatility").getAsDouble();
        }

        if (event.getOption("query") == null) {
            event.deferReply().queue();

            try {
                List<Strategy> strategies = strategyService.getOpenStrategies(userId);

                if (strategies.isEmpty()) {
                    event.getHook().sendMessage("❌ You have no positions to analyze. Use `/buy` to add contracts!").queue();
                    return;
                }

                EmbedBuilder eb = new EmbedBuilder();
                eb.setTitle("📊 Portfolio Analysis for " + userId);
                eb.setColor(Color.decode("#9b59b6"));

                StringBuilder analysis = new StringBuilder();
                final double vol = volatility;

                for (Strategy s : strategies) {
                    for (Leg leg : s.getLegs()) {
                        try {
                            double currentPrice = marketService.getPrice(s.getTicker());
                            if (currentPrice > 0) {
                                long daysToExp = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), leg.getExpiration());

                                double fairValue = bsService.blackScholes(
                                        currentPrice,
                                        leg.getStrikePrice(),
                                        daysToExp / 365.0,
                                        vol,
                                        0.05,
                                        leg.getOptionType().toLowerCase());

                                double plPerContract = fairValue - leg.getEntryPrice();
                                double plPercent = (plPerContract / leg.getEntryPrice()) * 100;

                                String plEmoji = plPerContract >= 0 ? "🟢" : "🔴";
                                String plSign = plPerContract >= 0 ? "+" : "";

                                analysis.append(String.format(
                                        "**%s $%.0f %s** (Strategy #%d)\n" +
                                                "Stock: $%.2f | Fair Value: $%.2f\n" +
                                                "Entry: $%.2f | P&L: %s$%.2f (%s%.1f%%) %s\n\n",
                                        s.getTicker(),
                                        leg.getStrikePrice(),
                                        leg.getOptionType().toUpperCase(),
                                        s.getId(),
                                        currentPrice,
                                        fairValue,
                                        leg.getEntryPrice(),
                                        plSign,
                                        plPerContract,
                                        plSign,
                                        plPercent,
                                        plEmoji));
                            }
                        } catch (Exception e) {
                            analysis.append(String.format("**%s** - Could not analyze\n\n", s.getTicker()));
                        }
                    }
                }

                eb.setDescription(analysis.toString());
                eb.setFooter("Analysis uses Black-Scholes with IV=" + (volatility * 100) + "%");
                event.getHook().sendMessageEmbeds(eb.build()).queue();
            } catch (Exception e) {
                event.getHook().sendMessage("❌ Error: " + e.getMessage()).queue();
            }

        } else {
            String query = event.getOption("query").getAsString();
            event.deferReply().queue();

            try {
                CommandParserService.ParsedOption opt = parserService.parse(query);
                double currentPrice = marketService.getPrice(opt.ticker);
                if (currentPrice == 0.0) {
                    event.getHook().sendMessage("❌ Could not fetch price for **" + opt.ticker + "**.").queue();
                    return;
                }
                double fairValue = bsService.blackScholes(currentPrice, opt.strike, opt.days / 365.0, volatility, 0.05, opt.type);

                EmbedBuilder eb = new EmbedBuilder();
                eb.setTitle("🚀 Fast Analysis: " + opt.ticker + " $" + opt.strike + " " + opt.type.toUpperCase());
                eb.setColor(Color.MAGENTA);
                eb.addField("Live Stock Price", "$" + currentPrice, true);
                eb.addField("Fair Value", "$" + String.format("%.2f", fairValue), true);
                eb.setFooter("Using volatility: " + (volatility * 100) + "%");
                event.getHook().sendMessageEmbeds(eb.build()).queue();
            } catch (Exception e) {
                event.getHook().sendMessage("❌ Error: " + e.getMessage() + "\nTry format: `NVDA 150c 30d`").queue();
            }
        }
    }

    private void viewSlash(SlashCommandInteractionEvent event) {
        String username = event.getOption("user").getAsUser().getName();
        event.deferReply().queue();

        try {
            List<Strategy> strategies = strategyService.getOpenStrategies(username);
            EmbedBuilder eb = new EmbedBuilder();
            eb.setTitle("💼 " + username + "'s Portfolio");
            eb.setColor(Color.decode("#3498db"));

            if (strategies.isEmpty()) {
                eb.setDescription("No active positions.");
            } else {
                StringBuilder sb = new StringBuilder();
                for (Strategy s : strategies) {
                    sb.append("**#" + s.getId() + " " + s.getTicker() + "** (" + s.getStrategy() + ")\n");
                    boolean isMultiLeg = s.getLegs().size() > 1;

                    for (Leg leg : s.getLegs()) {
                        String legDir = leg.getQuantity() > 0 ? "📈" : "📉";
                        int qty = Math.abs(leg.getQuantity());
                        String qtyStr = qty > 1 ? " x" + qty : "";

                        if (isMultiLeg) {
                            sb.append(legDir + qtyStr + " $" + leg.getStrikePrice().intValue() + " " + leg.getOptionType().toUpperCase() + " (Exp: " + leg.getExpiration() + ")\n");
                        } else {
                            sb.append(legDir + " $" + leg.getStrikePrice() + " " + leg.getOptionType().toUpperCase() + " @ $" + leg.getEntryPrice() + " (Exp: " + leg.getExpiration() + ")\n");
                        }
                    }

                    if (isMultiLeg && s.getNetCost() != null) {
                        sb.append("💰 Net Debit: $" + String.format("%.2f", s.getNetCost()) + "\n");
                    }
                    sb.append("\n");
                }
                eb.setDescription(sb.toString());
            }
            event.getHook().sendMessageEmbeds(eb.build()).queue();
        } catch (Exception e) {
            event.getHook().sendMessage("❌ Error: " + e.getMessage()).queue();
        }
    }

    private void straddleSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue();

        try {
            String action = event.getOption("action").getAsString().toUpperCase();
            String ticker = event.getOption("ticker").getAsString().toUpperCase();
            int strike = event.getOption("strike").getAsInt();
            int dte = event.getOption("dte").getAsInt();
            double cost = event.getOption("cost") != null ? event.getOption("cost").getAsDouble() : 0.0;

            LocalDate expiration = LocalDate.now().plusDays(dte);
            String userId = event.getUser().getName();

            if (action.equals("OPEN")) {
                ArrayList<Leg> legs = generateStraddle(String.valueOf(strike), expiration);
                for (Leg leg : legs) {
                    leg.setEntryPrice(cost / 2.0);
                }

                Strategy strategy = strategyService.openStrategy(userId, "STRADDLE", ticker, legs, cost);
                String response = buildSpreadResponse(StrategyType.STRADDLE, ticker, legs, cost, expiration, strategy.getId());
                event.getHook().sendMessage(response).queue();
            } else {
                event.getHook().sendMessage("Use /portfolio and /sell <id> to close positions").queue();
            }
        } catch (Exception e) {
            event.getHook().sendMessage("❌ Error: " + e.getMessage()).queue();
        }
    }

    private void verticalSlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue();

        try {
            String action = event.getOption("action").getAsString().toUpperCase();
            String ticker = event.getOption("ticker").getAsString().toUpperCase();
            String type = event.getOption("type").getAsString().toLowerCase();
            int longStrike = event.getOption("long_strike").getAsInt();
            int shortStrike = event.getOption("short_strike").getAsInt();
            int dte = event.getOption("dte").getAsInt();
            double cost = event.getOption("cost") != null ? event.getOption("cost").getAsDouble() : 0.0;

            LocalDate expiration = LocalDate.now().plusDays(dte);
            String userId = event.getUser().getName();

            if (action.equals("OPEN")) {
                String shortStrikeStr = shortStrike + (type.equalsIgnoreCase("call") ? "c" : "p");
                String longStrikeStr = longStrike + (type.equalsIgnoreCase("call") ? "c" : "p");

                ArrayList<Leg> legs = generateVertical(longStrikeStr, shortStrikeStr, expiration);
                Strategy strategy = strategyService.openStrategy(userId, "VERTICAL", ticker, legs, cost);

                String response = buildSpreadResponse(StrategyType.VERTICAL, ticker, legs, cost, expiration, strategy.getId());
                event.getHook().sendMessage(response).queue();
            } else {
                event.getHook().sendMessage("Use /portfolio and /sell <id> to close positions").queue();
            }
        } catch (Exception e) {
            event.getHook().sendMessage("❌ Error: " + e.getMessage()).queue();
        }
    }

    private void flySlash(SlashCommandInteractionEvent event) {
        event.deferReply().queue();

        try {
            String action = event.getOption("action").getAsString().toUpperCase();
            String ticker = event.getOption("ticker").getAsString().toUpperCase();
            String type = event.getOption("type").getAsString().toLowerCase();
            int low = event.getOption("low").getAsInt();
            int high = event.getOption("high").getAsInt();
            int dte = event.getOption("dte").getAsInt();
            double cost = event.getOption("cost") != null ? event.getOption("cost").getAsDouble() : 0.0;

            LocalDate expiration = LocalDate.now().plusDays(dte);
            String userId = event.getUser().getName();

            if (action.equals("OPEN")) {
                String lowStr = low + (type.equalsIgnoreCase("call") ? "c" : "p");
                String highStr = high + (type.equalsIgnoreCase("call") ? "c" : "p");

                ArrayList<Leg> legs = generateButterfly(lowStr, highStr, expiration);
                Strategy strategy = strategyService.openStrategy(userId, "FLY", ticker, legs, cost);

                String response = buildSpreadResponse(StrategyType.FLY, ticker, legs, cost, expiration, strategy.getId());
                event.getHook().sendMessage(response).queue();
            } else {
                event.getHook().sendMessage("Use /portfolio and /sell <id> to close positions").queue();
            }
        } catch (Exception e) {
            event.getHook().sendMessage("❌ Error: " + e.getMessage()).queue();
        }
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot())
            return;

        String message = event.getMessage().getContentRaw().trim();

        if (message.toLowerCase().startsWith("!strad") || message.toLowerCase().startsWith("!vol")) {
            handleStradCommand(event, message);
        }

        if (message.toLowerCase().startsWith("!gex")) {
            handleGexCommand(event, message);
        }
    }

    private void handleStradCommand(MessageReceivedEvent event, String message) {
        try {
            String[] parts = message.split("\\s+");
            int dte = 0;

            if (parts.length >= 2) {
                try {
                    dte = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    event.getChannel().sendMessage("Invalid DTE. Usage: `!strad <dte>` (e.g. `!strad 0`)").queue();
                    return;
                }
            }

            if (!marketCalendarService.isMarketOpen()) {
                String closedMessage = marketCalendarService.formatClosedMessage(dte);
                event.getChannel().sendMessage(closedMessage).queue();
                return;
            }

            LocalDate expiryDate = LocalDate.now().plusDays(dte);
            if (!marketCalendarService.isTradingDay(expiryDate)) {
                String invalidExpiryMessage = "📅 **" + expiryDate + "** is not a trading day.\n\n**Try one of these instead:**\n";
                for (MarketCalendarService.ExpiryOption opt : marketCalendarService.suggestNearbyExpiries(dte)) {
                    invalidExpiryMessage += "• `!strad " + opt.getDte() + "` → " +
                            opt.getDate().format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d")) + "\n";
                }
                event.getChannel().sendMessage(invalidExpiryMessage).queue();
                return;
            }

            Optional<SchwabApiService.SPXStraddle> straddleData = schwabService.getSpxStraddle(dte);

            if (straddleData.isEmpty()) {
                event.getChannel().sendMessage("❌ Could not fetch SPX options data from Schwab. Please ensure the API is configured correctly.").queue();
                return;
            }

            SchwabApiService.SPXStraddle straddle = straddleData.get();
            String response = formatStraddleResponse(straddle);
            event.getChannel().sendMessage(response).queue();

        } catch (Exception e) {
            event.getChannel().sendMessage("❌ Error: " + e.getMessage()).queue();
        }
    }

    private String formatStraddleResponse(SchwabApiService.SPXStraddle straddle) {
        return String.format(
                "**Date:** %s\n**Straddle:** $%.2f\n**Strike Used:** %.0f\n**Spot Price:** $%.2f\n" +
                        "**Call IV:** %.2f%%  |  **Put IV:** %.2f%%\n**Avg IV:** %.2f%%",
                straddle.getExpirationDate(),
                straddle.getStraddlePrice(),
                straddle.getStrike(),
                straddle.getUnderlyingPrice(),
                straddle.getCallIV(),
                straddle.getPutIV(),
                straddle.getAverageIV());
    }

    private void handleGexCommand(MessageReceivedEvent event, String message) {
        String[] parts = message.split("\\s+");
        String ticker = parts.length >= 2 ? parts[1].toUpperCase() : "SPY";

        try {
            Optional<com.fasterxml.jackson.databind.JsonNode> chainOpt = schwabService.getFullOptionChain(ticker);

            if (chainOpt.isEmpty()) {
                event.getChannel().sendMessage("❌ Could not fetch option chain for **" + ticker + "**").queue();
                return;
            }

            Optional<GexService.GexResult> resultOpt = gexService.calculateGex(chainOpt.get(), ticker, null);

            if (resultOpt.isEmpty()) {
                event.getChannel().sendMessage("❌ GEX calculation failed for **" + ticker + "**").queue();
                return;
            }

            event.getChannel().sendMessage(formatGexLadder(resultOpt.get())).queue();

        } catch (Exception e) {
            logger.error("Error in !gex command for {}", ticker, e);
            event.getChannel().sendMessage("❌ Error: " + e.getMessage()).queue();
        }
    }
}
