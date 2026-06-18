package com.skew.engine.service;

import com.skew.engine.service.GexService.GexResult;
import com.skew.engine.service.GexService.GexRow;
import com.skew.engine.domain.GexSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Service
public class GexChartGenerator {

    // Canvas Settings
    private static final int WIDTH = 800;   // Total width of the vertical image
    private static final int ROW_HEIGHT = 20; // Height per strike row
    private static final int COLUMN_WIDTH = 30; // Width per strike column (horizontal mode)
    private static final int HORIZONTAL_HEIGHT = 600; // Fixed height for horizontal chart
    private static final int PADDING = 40;  // Padding on sides
    private static final int MARGIN_TOP = 60; // Space for header
    private static final int MARGIN_BOTTOM = 60; 
    
    // Colors mimicking the dark dashboard theme
    private static final Color BG_COLOR = new Color(17, 17, 17);         
    private static final Color GRID_COLOR = new Color(50, 50, 50);       
    private static final Color TEXT_COLOR = new Color(200, 200, 200);    
    private static final Color CALL_GEX_COLOR = new Color(138, 43, 226); 
    private static final Color PUT_GEX_COLOR = new Color(255, 140, 0);   
    private static final Color SPOT_LINE_COLOR = new Color(0, 191, 255); 
    private static final Color VANNA_COLOR = new Color(0, 255, 127); // Spring Green
    private static final Color CHARM_COLOR = new Color(255, 50, 50); // Bright Red

    /**
     * Entry method. Routes to either horizontal or vertical renderer.
     */
    public byte[] generateChart(GexResult result, boolean isHorizontal) {
        if (isHorizontal) {
            return generateHorizontalChart(result);
        } else {
            return generateVerticalChart(result);
        }
    }

    private String formatGex(double gex) {
        if (Math.abs(gex) >= 1_000_000_000) {
            return String.format("%+.2fB", gex / 1_000_000_000.0);
        } else if (Math.abs(gex) >= 1_000_000) {
            return String.format("%+.2fM", gex / 1_000_000.0);
        } else if (Math.abs(gex) >= 1_000) {
            return String.format("%+.2fK", gex / 1_000.0);
        } else {
            return String.format("%+.0f", gex);
        }
    }

    private boolean isIndex(String symbol) {
        String upper = symbol.toUpperCase();
        return upper.contains("SPX") || upper.contains("NDX") || upper.contains("RUT") 
            || upper.equals("SPY") || upper.equals("QQQ") || upper.equals("IWM")
            || upper.startsWith("$");
    }

    private byte[] generateHorizontalChart(GexResult result) {
        try {
            boolean index = isIndex(result.symbol);
            double rangePct = index ? 0.020 : 0.120; // 2% for indices, 12% for stocks
            double range = result.spotPrice * rangePct;
            
            double minV = result.spotPrice - range;
            double maxV = result.spotPrice + range;
 
            // Ensure walls are visible if they are within a reasonable "outer bound" (e.g. 40%)
            if (result.callWall > result.spotPrice && result.callWall < result.spotPrice * 1.4) {
                maxV = Math.max(maxV, result.callWall + (result.spotPrice * 0.01));
            }
            if (result.putWall < result.spotPrice && result.putWall > result.spotPrice * 0.6) {
                minV = Math.min(minV, result.putWall - (result.spotPrice * 0.01));
            }

            final double finalMin = minV;
            final double finalMax = maxV;

            java.util.List<GexRow> filteredRows = result.rows.stream()
                .filter(r -> r.strike >= finalMin && r.strike <= finalMax)
                .collect(java.util.stream.Collectors.toList());

            if (filteredRows.isEmpty()) {
                filteredRows = result.rows; 
            }

            // Reverse to ensure ascending order for X-axis (lowest strike -> highest strike)
            if (filteredRows.size() > 1 && filteredRows.get(0).strike > filteredRows.get(filteredRows.size() - 1).strike) {
                java.util.Collections.reverse(filteredRows); 
            }

            double maxGexAbs = 0;
            for (GexRow row : filteredRows) {
                maxGexAbs = Math.max(maxGexAbs, Math.abs(row.netGex));
            }
            maxGexAbs *= 1.1; 

            int numStrikes = filteredRows.size();
            int width = PADDING * 2 + (numStrikes * COLUMN_WIDTH);
            int height = HORIZONTAL_HEIGHT;

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = image.createGraphics();

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g2.setColor(BG_COLOR);
            g2.fillRect(0, 0, width, height);

            int centerY = height / 2;
            g2.setColor(GRID_COLOR);
            g2.drawLine(PADDING, centerY, width - PADDING, centerY);

            g2.setFont(new Font("Consolas", Font.BOLD, 12));

            double pixelsPerGex = (double)(centerY - MARGIN_TOP) / maxGexAbs;

            double maxVannaAbs = 0;
            double maxCharmAbs = 0;
            for (GexRow row : filteredRows) {
                double netVanna = row.callVanna + row.putVanna;
                double netCharm = row.callCharm + row.putCharm;
                maxVannaAbs = Math.max(maxVannaAbs, Math.abs(netVanna));
                maxCharmAbs = Math.max(maxCharmAbs, Math.abs(netCharm));
            }
            maxVannaAbs = Math.max(1.0, maxVannaAbs * 1.1);
            maxCharmAbs = Math.max(1.0, maxCharmAbs * 1.1);
            
            double pixelsPerVanna = (double)(centerY - MARGIN_TOP) / maxVannaAbs;
            double pixelsPerCharm = (double)(centerY - MARGIN_TOP) / maxCharmAbs;
            
            // Legend
            g2.setColor(VANNA_COLOR);
            g2.fillOval(width - 100, 20, 8, 8);
            g2.setColor(TEXT_COLOR);
            g2.drawString("Vanna", width - 85, 28);
            
            g2.setColor(CHARM_COLOR);
            g2.fillOval(width - 100, 40, 8, 8);
            g2.setColor(TEXT_COLOR);
            g2.drawString("Charm", width - 85, 48);

            // Display Walls in Header
            double cwGex = 0;
            double pwGex = 0;
            for (GexRow r : result.rows) {
                if (r.strike == result.callWall) cwGex = r.netGex;
                if (r.strike == result.putWall) pwGex = r.netGex;
            }
            g2.setColor(CALL_GEX_COLOR);
            g2.drawString(String.format("Call Wall: %.0f (%s)", result.callWall, formatGex(cwGex)), PADDING, 20);
            g2.setColor(PUT_GEX_COLOR);
            g2.drawString(String.format("Put Wall: %.0f (%s)", result.putWall, formatGex(pwGex)), PADDING, 40);

            GexRow closestRow = null;
            double minDist = Double.MAX_VALUE;
            for (GexRow r : filteredRows) {
                double dist = Math.abs(r.strike - result.spotPrice);
                if (dist < minDist) {
                    minDist = dist;
                    closestRow = r;
                }
            }

            int x = PADDING;

            for (GexRow row : filteredRows) {
                int barHeight = (int) (Math.abs(row.netGex) * pixelsPerGex);
                int barWidth = COLUMN_WIDTH - 4;
                int barX = x + 2; 

                if (row.netGex >= 0) {
                    g2.setColor(CALL_GEX_COLOR);
                    g2.fillRect(barX, centerY - barHeight, barWidth, barHeight);
                } else {
                    g2.setColor(PUT_GEX_COLOR);
                    g2.fillRect(barX, centerY, barWidth, barHeight);
                }

                // Draw Vanna & Charm Dots
                double netVanna = row.callVanna + row.putVanna;
                double netCharm = row.callCharm + row.putCharm;
                
                int vannaY = centerY - (int)(netVanna * pixelsPerVanna);
                int charmY = centerY - (int)(netCharm * pixelsPerCharm);
                
                g2.setColor(VANNA_COLOR);
                g2.fillOval(barX + barWidth / 2 - 3, vannaY - 3, 6, 6);
                
                g2.setColor(CHARM_COLOR);
                g2.fillOval(barX + barWidth / 2 - 3, charmY - 3, 6, 6);

                // Draw strike label rotated vertically
                g2.setColor(TEXT_COLOR);
                String strikeStr = String.format("%.0f", row.strike);
                
                AffineTransform orig = g2.getTransform();
                g2.translate(x + 10, height - 15);
                g2.rotate(-Math.PI / 4); // 45 degrees
                g2.drawString(strikeStr, 0, 0);
                g2.setTransform(orig);

                if (row == closestRow) {
                    g2.setColor(SPOT_LINE_COLOR);
                    g2.drawLine(x + COLUMN_WIDTH / 2, MARGIN_TOP, x + COLUMN_WIDTH / 2, height - MARGIN_BOTTOM + 20);
                    g2.drawString(String.format("SPOT: %.2f", result.spotPrice), x + 10, MARGIN_TOP - 10);
                }
                
                if (result.hasZeroFlip && row.strike == result.zeroFlip) {
                    g2.setColor(Color.YELLOW);
                    g2.drawLine(x + COLUMN_WIDTH / 2, MARGIN_TOP, x + COLUMN_WIDTH / 2, height - MARGIN_BOTTOM + 20);
                    g2.drawString("ZERO FLIP", x + 10, height - MARGIN_BOTTOM + 35);
                }

                x += COLUMN_WIDTH; 
            }

            g2.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private byte[] generateVerticalChart(GexResult result) {
        try {
            boolean index = isIndex(result.symbol);
            double rangePct = index ? 0.020 : 0.120;
            double range = result.spotPrice * rangePct;

            double minV = result.spotPrice - range;
            double maxV = result.spotPrice + range;

            if (result.callWall > result.spotPrice && result.callWall < result.spotPrice * 1.4) {
                maxV = Math.max(maxV, result.callWall + (result.spotPrice * 0.01));
            }
            if (result.putWall < result.spotPrice && result.putWall > result.spotPrice * 0.6) {
                minV = Math.min(minV, result.putWall - (result.spotPrice * 0.01));
            }

            final double finalMin = minV;
            final double finalMax = maxV;

            java.util.List<GexRow> filteredRows = result.rows.stream()
                .filter(r -> r.strike >= finalMin && r.strike <= finalMax)
                .collect(java.util.stream.Collectors.toList());

            if (filteredRows.isEmpty()) {
                filteredRows = result.rows; 
            }

            double maxGexAbs = 0;
            for (GexRow row : filteredRows) {
                maxGexAbs = Math.max(maxGexAbs, Math.abs(row.netGex));
            }
            maxGexAbs *= 1.1; 

            int numStrikes = filteredRows.size();
            int height = MARGIN_TOP + MARGIN_BOTTOM + (numStrikes * ROW_HEIGHT);

            BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = image.createGraphics();

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g2.setColor(BG_COLOR);
            g2.fillRect(0, 0, WIDTH, height);

            int centerX = WIDTH / 2;
            g2.setColor(GRID_COLOR);
            g2.drawLine(centerX, MARGIN_TOP, centerX, height - MARGIN_BOTTOM);

            g2.setFont(new Font("Consolas", Font.BOLD, 12));

            double pixelsPerGex = (centerX - PADDING) / maxGexAbs;

            double maxVannaAbs = 0;
            double maxCharmAbs = 0;
            for (GexRow row : filteredRows) {
                double netVanna = row.callVanna + row.putVanna;
                double netCharm = row.callCharm + row.putCharm;
                maxVannaAbs = Math.max(maxVannaAbs, Math.abs(netVanna));
                maxCharmAbs = Math.max(maxCharmAbs, Math.abs(netCharm));
            }
            maxVannaAbs = Math.max(1.0, maxVannaAbs * 1.1);
            maxCharmAbs = Math.max(1.0, maxCharmAbs * 1.1);
            
            double pixelsPerVanna = (centerX - PADDING) / maxVannaAbs;
            double pixelsPerCharm = (centerX - PADDING) / maxCharmAbs;
            
            // Legend
            g2.setColor(VANNA_COLOR);
            g2.fillOval(WIDTH - PADDING - 80, 20, 8, 8);
            g2.setColor(TEXT_COLOR);
            g2.drawString("Vanna", WIDTH - PADDING - 65, 28);
            
            g2.setColor(CHARM_COLOR);
            g2.fillOval(WIDTH - PADDING - 80, 40, 8, 8);
            g2.setColor(TEXT_COLOR);
            g2.drawString("Charm", WIDTH - PADDING - 65, 48);

            // Display Walls in Header
            double v_cwGex = 0;
            double v_pwGex = 0;
            for (GexRow r : result.rows) {
                if (r.strike == result.callWall) v_cwGex = r.netGex;
                if (r.strike == result.putWall) v_pwGex = r.netGex;
            }
            g2.setColor(CALL_GEX_COLOR);
            g2.drawString(String.format("Call Wall: %.0f (%s)", result.callWall, formatGex(v_cwGex)), PADDING, 20);
            g2.setColor(PUT_GEX_COLOR);
            g2.drawString(String.format("Put Wall: %.0f (%s)", result.putWall, formatGex(v_pwGex)), PADDING, 40);

            GexRow closestRow = null;
            double minDist = Double.MAX_VALUE;
            for (GexRow r : filteredRows) {
                double dist = Math.abs(r.strike - result.spotPrice);
                if (dist < minDist) {
                    minDist = dist;
                    closestRow = r;
                }
            }

            int y = MARGIN_TOP;
            for (GexRow row : filteredRows) {
                int barWidth = (int) (Math.abs(row.netGex) * pixelsPerGex);
                int barHeight = ROW_HEIGHT - 4;
                int barY = y + 2; 

                if (row.netGex >= 0) {
                    g2.setColor(CALL_GEX_COLOR);
                    g2.fillRect(centerX, barY, barWidth, barHeight);
                } else {
                    g2.setColor(PUT_GEX_COLOR);
                    g2.fillRect(centerX - barWidth, barY, barWidth, barHeight);
                }

                // Draw Vanna & Charm Dots
                double netVanna = row.callVanna + row.putVanna;
                double netCharm = row.callCharm + row.putCharm;
                
                int vannaX = centerX + (int)(netVanna * pixelsPerVanna);
                int charmX = centerX + (int)(netCharm * pixelsPerCharm);
                
                g2.setColor(VANNA_COLOR);
                g2.fillOval(vannaX - 3, barY + barHeight / 2 - 3, 6, 6);
                
                g2.setColor(CHARM_COLOR);
                g2.fillOval(charmX - 3, barY + barHeight / 2 - 3, 6, 6);

                g2.setColor(TEXT_COLOR);
                g2.drawString(String.format("%.0f", row.strike), PADDING / 2, y + 14);

                if (row == closestRow) {
                    g2.setColor(SPOT_LINE_COLOR);
                    g2.drawLine(PADDING, y + ROW_HEIGHT / 2, WIDTH - PADDING, y + ROW_HEIGHT / 2);
                    g2.drawString(String.format("SPOT: %.2f", result.spotPrice), WIDTH - PADDING * 3, y + 14);
                }
                
                if (result.hasZeroFlip && row.strike == result.zeroFlip) {
                    g2.setColor(Color.YELLOW);
                    g2.drawLine(PADDING, y + ROW_HEIGHT / 2, WIDTH - PADDING, y + ROW_HEIGHT / 2);
                    g2.drawString("ZERO FLIP", PADDING + 40, y + 14);
                }

                y += ROW_HEIGHT; 
            }

            g2.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public byte[] generateIntradayHeatmap(java.util.List<GexSnapshot> history, ObjectMapper objectMapper) {
        if (history == null || history.isEmpty()) return null;
        try {
            int numSnapshots = history.size();
            int cellWidth = 4;
            int cellHeight = 4;
            
            // Determine strike range from the first snapshot
            GexSnapshot first = history.get(0);
            double baseSpot = first.getSpotPrice();
            double minStrike = baseSpot * 0.97; // +/- 3%
            double maxStrike = baseSpot * 1.03;
            
            // Get all unique strikes in that range
            java.util.List<Double> yStrikes = new java.util.ArrayList<>();
            for (double s = Math.floor(minStrike); s <= Math.ceil(maxStrike); s += 5.0) {
                yStrikes.add(s);
            }
            java.util.Collections.reverse(yStrikes); // High strikes at the top
            
            int width = Math.max(800, PADDING * 2 + (numSnapshots * cellWidth));
            int height = PADDING * 2 + (yStrikes.size() * cellHeight);

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = image.createGraphics();

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(BG_COLOR);
            g2.fillRect(0, 0, width, height);
            
            double globalMaxGex = 0;

            // 1. Parse all snapshots AND find global max Gex for coloring
            java.util.List<java.util.List<GexRow>> allRows = new java.util.ArrayList<>();
            for (GexSnapshot snap : history) {
                java.util.List<GexRow> rows = objectMapper.readValue(snap.getStrikeDataJson(), new TypeReference<java.util.List<GexRow>>(){});
                allRows.add(rows);
                for (GexRow r : rows) {
                    if (r.strike >= minStrike && r.strike <= maxStrike) {
                        globalMaxGex = Math.max(globalMaxGex, Math.abs(r.netGex));
                    }
                }
            }
            
            if (globalMaxGex == 0) globalMaxGex = 1;

            // 2. Plot the Heatmap
            for (int col = 0; col < numSnapshots; col++) {
                int x = PADDING + (col * cellWidth);
                java.util.List<GexRow> rows = allRows.get(col);
                
                // Map rows for quick lookup
                java.util.Map<Double, Double> strikeToGex = new java.util.HashMap<>();
                for (GexRow r : rows) strikeToGex.put(r.strike, r.netGex);
                
                for (int rowIdx = 0; rowIdx < yStrikes.size(); rowIdx++) {
                    double currentStrike = yStrikes.get(rowIdx);
                    int y = PADDING + (rowIdx * cellHeight);
                    
                    double netGex = strikeToGex.getOrDefault(currentStrike, 0.0);
                    
                    // Determine Color intensity (0.0 to 1.0)
                    float intensity = (float) Math.min(1.0, Math.abs(netGex) / globalMaxGex);
                    intensity = (float) Math.pow(intensity, 0.4); // gamma correction
                    
                    if (netGex > 0) {
                        g2.setColor(new Color(138/255f * intensity, 43/255f * intensity, 226/255f * intensity));
                    } else if (netGex < 0) {
                        g2.setColor(new Color(255/255f * intensity, 140/255f * intensity, 0/255f * intensity));
                    } else {
                        g2.setColor(BG_COLOR);
                    }
                    g2.fillRect(x, y, cellWidth, cellHeight);
                }
                
                // Draw Spot Price
                double spot = history.get(col).getSpotPrice();
                if (spot >= minStrike && spot <= maxStrike) {
                    double percentY = (maxStrike - spot) / (maxStrike - minStrike);
                    int spotY = PADDING + (int) (percentY * (yStrikes.size() * cellHeight));
                    g2.setColor(Color.WHITE);
                    g2.fillRect(x, spotY, cellWidth, 2);
                }
                
                // Draw time marker every 30 snapshots
                if (col % 30 == 0) {
                    g2.setColor(GRID_COLOR);
                    g2.drawLine(x, PADDING, x, height - PADDING);
                }
            }

            // Draw Y-axis labels
            g2.setColor(TEXT_COLOR);
            g2.setFont(new Font("Consolas", Font.PLAIN, 10));
            int yTicks = 10;
            for (int i = 0; i <= yTicks; i++) {
                int idx = i * (yStrikes.size() - 1) / yTicks;
                double strike = yStrikes.get(idx);
                int yPos = PADDING + (idx * cellHeight);
                g2.drawString(String.format("%.0f", strike), PADDING / 4, yPos + 4);
                g2.drawString(String.format("%.0f", strike), width - (int)(PADDING * 0.8), yPos + 4);
            }

            g2.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
