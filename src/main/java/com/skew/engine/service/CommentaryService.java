package com.skew.engine.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Generates plain-English market commentary using the <strong>Google Gemini 1.5 Flash</strong>
 * via Spring AI ChatClient.
 *
 * <p>Each time a live skew divergence signal fires, the {@link com.skew.engine.consumer.TradeSignalConsumer} 
 * (or other service) can call {@link #generateCommentary} with the current market metrics. 
 * The service builds a concise, analytical prompt and returns a 1-2 sentence interpretation 
 * of what the skew divergence means for options traders.</p>
 */
@Service
public class CommentaryService {

    private static final Logger logger = LoggerFactory.getLogger(CommentaryService.class);

    private final ChatClient chatClient;

    @org.springframework.beans.factory.annotation.Value("${gemini.api-key:}")
    private String geminiApiKey;

    public CommentaryService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public boolean isConfigured() {
        return geminiApiKey != null && !geminiApiKey.isBlank();
    }

    /**
     * Calls Gemini 1.5 Flash to generate a brief market commentary for a skew divergence signal.
     *
     * @param signalType  "BEARISH_DIVERGENCE" or "BULLISH_DIVERGENCE"
     * @param spot        SPX spot price at signal time
     * @param putIv       OTM put implied volatility (e.g. 0.22 = 22%)
     * @param callIv      OTM call implied volatility (e.g. 0.15 = 15%)
     * @param skew        put IV minus call IV (positive = put-call skew)
     * @param spotReturn  2-week spot return (e.g. 0.03 = +3%)
     * @param skewChange  2-week skew change (e.g. 0.01 = +1%)
     * @return A 1-2 sentence plain-English commentary string, or a fallback string on failure.
     */
    public String generateCommentary(String signalType, double spot, double putIv,
                                     double callIv, double skew,
                                     double spotReturn, double skewChange) {
        String prompt = buildPrompt(signalType, spot, putIv, callIv, skew, spotReturn, skewChange);

        try {
            String text = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (text == null || text.isBlank()) {
                return buildRuleBasedCommentary(signalType, spot, skew, spotReturn, skewChange);
            }

            logger.info("Gemini commentary generated for {} signal.", signalType);
            return text.trim();

        } catch (Exception e) {
            logger.error("Gemini API call failed: {} — returning rule-based commentary.", e.getMessage());
            return buildRuleBasedCommentary(signalType, spot, skew, spotReturn, skewChange);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a concise, domain-specific prompt for Gemini.
     * Providing numbers in the prompt lets the model give grounded responses.
     */
    private String buildPrompt(String signalType, double spot, double putIv, double callIv,
                               double skew, double spotReturn, double skewChange) {
        String direction = "BEARISH_DIVERGENCE".equals(signalType)
                ? "rallied +%.2f%% while put-call skew widened +%.2f%%".formatted(spotReturn * 100, skewChange * 100)
                : "declined %.2f%% while put-call skew compressed %.2f%%".formatted(spotReturn * 100, Math.abs(skewChange * 100));

        return """
                You are an institutional options desk strategist specializing in S&P 500 volatility skew, Greek exposure, and dealer hedging mechanics.
                Write a concise, punchy 1-2 sentence dashboard commentary interpreting the following volatility skew divergence signal.
                
                Guidelines:
                - Explain what this divergence reveals about institutional hedging demand, downside tail-risk pricing, or volatility risk premium absorption.
                - Be direct, professional, and analytical. Absolutely NO generic financial disclaimers or filler phrases.
                
                Signal Event: %s
                SPX Spot Level: %.0f
                2-Week Price & Skew Move: %s
                OTM Put IV: %.1f%%  |  OTM Call IV: %.1f%%
                Net Put-Call Skew: %.1f%%
                """.formatted(
                signalType.replace('_', ' '),
                spot,
                direction,
                putIv * 100,
                callIv * 100,
                skew * 100
        );
    }

    /**
     * Fallback commentary generated from simple rules — used when Gemini is unavailable.
     */
    private String buildRuleBasedCommentary(String signalType, double spot, double skew,
                                             double spotReturn, double skewChange) {
        if ("BEARISH_DIVERGENCE".equals(signalType)) {
            return String.format(
                    "Bearish divergence detected at SPX %.0f — spot rallied +%.1f%% but put skew widened +%.1f%%, " +
                    "suggesting institutional hedgers are buying downside protection into the rally.",
                    spot, spotReturn * 100, skewChange * 100);
        } else {
            return String.format(
                    "Bullish divergence detected at SPX %.0f — spot declined %.1f%% but put skew compressed %.1f%%, " +
                    "indicating put-sellers are absorbing fear premium and expecting a recovery.",
                    spot, Math.abs(spotReturn * 100), Math.abs(skewChange * 100));
        }
    }
}
