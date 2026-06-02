package com.basescanner;

import net.minecraft.event.HoverEvent;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the /f map chat output to find faction base claims.
 *
 * How /f map works in FactionsUUID / OpFactions:
 *   - Each call to /f map sends ~17 lines of colored characters into chat.
 *   - Each character represents one chunk (16x16 blocks).
 *   - The map is centered on the player's current chunk.
 *   - Map radius = 8 chunks each side → 17 characters wide, 17 lines tall.
 *   - Each character has a ChatStyle with:
 *       color  → relationship to your faction
 *       HoverEvent.SHOW_TEXT → faction name
 *
 * Color mapping (Pika OpFactions):
 *   §9 / §1 / §b  =  BLUE  → BASE CLAIM  ← TOP PRIORITY
 *   §f             =  WHITE → Normal claim
 *   §d / §5        =  PINK  → Ally faction
 *   §c / §4        =  RED   → Enemy faction
 *   §a / §2        =  GREEN → Your own faction / wilderness
 */
public class FMapParser {

    // ---------------------------------------------------------------
    // Inner data class
    // ---------------------------------------------------------------

    public static class ClaimInfo {
        public final String factionName;
        public final int worldX;
        public final int worldZ;
        public final String claimType; // "blue", "red", "white", "pink"

        public ClaimInfo(String factionName, int worldX, int worldZ, String claimType) {
            this.factionName = factionName;
            this.worldX = worldX;
            this.worldZ = worldZ;
            this.claimType = claimType;
        }

        @Override
        public String toString() {
            return "[" + claimType.toUpperCase() + "] " + factionName + " @ " + worldX + ", " + worldZ;
        }
    }

    // ---------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------

    private static final int CHUNK_SIZE  = 16;
    private static final int MAP_RADIUS  = 8; // chunks each side
    private static final int MAP_WIDTH   = MAP_RADIUS * 2 + 1; // 17 chars

    // Minimum color codes in a line for it to count as a map line
    private static final int MIN_COLOR_CODES = 6;

    // ---------------------------------------------------------------
    // State
    // ---------------------------------------------------------------

    private final List<IChatComponent> mapLines = new ArrayList<>();
    private boolean collecting = false;

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /**
     * Call this for every incoming chat message while collecting.
     * Automatically detects /f map lines.
     */
    public void processChat(IChatComponent component, String formattedText) {
        if (isMapLine(formattedText)) {
            if (!collecting) {
                collecting = true;
                mapLines.clear();
            }
            mapLines.add(component);
        } else if (collecting && mapLines.size() >= 5) {
            // Non-map line after we started collecting → map is done
            // (only stop early if we got at least 5 rows to avoid false stops)
            collecting = false;
        }
    }

    /**
     * Returns all BLUE base claims found in the collected map lines.
     * @param playerX  player's current X position
     * @param playerZ  player's current Z position
     */
    public List<ClaimInfo> getBlueClaimsWithInfo(double playerX, double playerZ) {
        return getClaimsByType(playerX, playerZ, "blue");
    }

    /**
     * Returns ALL claims of any type (for full logging if desired).
     */
    public List<ClaimInfo> getAllClaims(double playerX, double playerZ) {
        return getClaimsByType(playerX, playerZ, "all");
    }

    public void reset() {
        mapLines.clear();
        collecting = false;
    }

    public boolean isCollecting() {
        return collecting;
    }

    public int getLineCount() {
        return mapLines.size();
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    private List<ClaimInfo> getClaimsByType(double playerX, double playerZ, String filter) {
        List<ClaimInfo> results = new ArrayList<>();
        if (mapLines.isEmpty()) return results;

        int playerChunkX = (int) Math.floor(playerX / CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(playerZ / CHUNK_SIZE);

        // The center row of the map corresponds to the player's chunk Z
        int midRow = mapLines.size() / 2;

        for (int rowIdx = 0; rowIdx < mapLines.size(); rowIdx++) {
            IChatComponent line = mapLines.get(rowIdx);
            int rowOffset = rowIdx - midRow; // negative = north, positive = south

            // Flatten all components in this line (root + all siblings recursively)
            List<IChatComponent> parts = flattenComponent(line);

            // Count total visible chars to find the midpoint column
            int totalCols = countVisibleChars(parts);
            int midCol = totalCols / 2;

            int colPos = 0;
            for (IChatComponent part : parts) {
                String rawText = part.getUnformattedText();
                EnumChatFormatting color = part.getChatStyle().getColor();

                for (int ci = 0; ci < rawText.length(); ci++) {
                    char ch = rawText.charAt(ci);
                    if (ch == ' ') continue; // skip spaces

                    int colOffset = colPos - midCol;

                    String claimType = colorToType(color);

                    boolean wanted = filter.equals("all")
                            || (filter.equals("blue") && claimType.equals("blue"));

                    if (wanted && !claimType.equals("unknown") && !claimType.equals("own")) {
                        String factionName = extractHoverName(part);
                        if (factionName == null || factionName.isEmpty()) {
                            // Fallback: use the character itself if no hover text
                            factionName = "Faction_" + String.valueOf(ch);
                        }

                        int claimChunkX = playerChunkX + colOffset;
                        int claimChunkZ = playerChunkZ + rowOffset;
                        int worldX = claimChunkX * CHUNK_SIZE + 8; // center of chunk
                        int worldZ = claimChunkZ * CHUNK_SIZE + 8;

                        results.add(new ClaimInfo(factionName, worldX, worldZ, claimType));
                    }

                    colPos++;
                }
            }
        }

        return results;
    }

    /**
     * Maps EnumChatFormatting color → claim type string.
     */
    private String colorToType(EnumChatFormatting color) {
        if (color == null) return "unknown";
        switch (color) {
            // ── BLUE / BASE CLAIM ─────────────────────────────────────
            case DARK_BLUE:   // §1
            case BLUE:        // §9
            case AQUA:        // §b
            case DARK_AQUA:   // §3
                return "blue";

            // ── PINK / ALLY ────────────────────────────────────────────
            case LIGHT_PURPLE: // §d
            case DARK_PURPLE:  // §5
                return "pink";

            // ── RED / ENEMY ────────────────────────────────────────────
            case RED:          // §c
            case DARK_RED:     // §4
                return "red";

            // ── WHITE / NORMAL CLAIM ───────────────────────────────────
            case WHITE:        // §f
            case GRAY:         // §7
            case DARK_GRAY:    // §8
                return "white";

            // ── GREEN / YOUR OWN / WILDERNESS ─────────────────────────
            case GREEN:        // §a
            case DARK_GREEN:   // §2
            case YELLOW:       // §e
            case GOLD:         // §6
                return "own";

            default:
                return "unknown";
        }
    }

    /**
     * Extracts the faction name from a component's HoverEvent (SHOW_TEXT).
     * In FactionsUUID, each map character has hover text = faction name.
     */
    private String extractHoverName(IChatComponent component) {
        HoverEvent hover = component.getChatStyle().getChatHoverEvent();
        if (hover != null && hover.getAction() == HoverEvent.Action.SHOW_TEXT) {
            // The hover value is itself an IChatComponent; get its plain text
            String raw = hover.getValue().getUnformattedText().trim();
            // Clean up common prefixes like "Faction: FactionName"
            if (raw.contains(":")) {
                raw = raw.substring(raw.lastIndexOf(':') + 1).trim();
            }
            return raw;
        }
        return null;
    }

    /**
     * Returns true if this chat line looks like a /f map row.
     * Heuristic: count §-prefixed color codes; map lines have many.
     */
    private boolean isMapLine(String formattedText) {
        int count = 0;
        for (int i = 0; i < formattedText.length() - 1; i++) {
            if (formattedText.charAt(i) == '§') {
                count++;
                i++; // skip the format char
            }
        }
        if (count < MIN_COLOR_CODES) return false;

        // Extra guard: normal chat lines contain "> " or player names with ":"
        // Map lines do NOT contain these patterns
        String plain = stripFormatting(formattedText);
        return !plain.contains("> ") && !plain.matches(".*\\w+:.*");
    }

    /** Recursively flattens an IChatComponent tree into a flat list. */
    private List<IChatComponent> flattenComponent(IChatComponent root) {
        List<IChatComponent> out = new ArrayList<>();
        // The root itself carries text (sometimes empty for wrapper components)
        if (root.getUnformattedText() != null && !root.getUnformattedText().isEmpty()) {
            out.add(root);
        }
        for (IChatComponent sibling : root.getSiblings()) {
            out.addAll(flattenComponent(sibling));
        }
        return out;
    }

    private int countVisibleChars(List<IChatComponent> parts) {
        int count = 0;
        for (IChatComponent p : parts) {
            for (char c : p.getUnformattedText().toCharArray()) {
                if (c != ' ') count++;
            }
        }
        return count;
    }

    private String stripFormatting(String text) {
        return text.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }
}
