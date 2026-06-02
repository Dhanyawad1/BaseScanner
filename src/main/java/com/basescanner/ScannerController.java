package com.basescanner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

import java.util.List;

/**
 * Core controller.
 *
 * TOGGLE:  Mouse Button 4 (back button) — press once to START, press again to STOP.
 *
 * STATE MACHINE:
 *   IDLE
 *    └─(MB4 press)──► FLYING_TO_POSITION
 *                          │
 *                    (arrived at grid point)
 *                          │
 *                     WAIT_BEFORE_MAP  (15 ticks, let chunks load)
 *                          │
 *                     COLLECTING_MAP   (send /f map, wait 50 ticks)
 *                          │
 *                     PROCESSING_MAP   (parse blue claims → Discord)
 *                          │
 *                   ┌──────┴──────┐
 *             (more left)    (all done)
 *                  │               │
 *          FLYING_TO_POSITION     IDLE
 */
public class ScannerController {

    private enum ScanState {
        IDLE, FLYING_TO_POSITION, WAIT_BEFORE_MAP, COLLECTING_MAP, PROCESSING_MAP
    }

    // ── Settings ────────────────────────────────────────────────────
    private static final int    FLY_Y        = 245;   // scan height
    private static final double FLY_SPEED    = 0.18;  // blocks/tick (~3.6 b/s, safe 2x)
    private static final double ARRIVAL_DIST = 4.0;   // blocks — counts as "arrived"
    private static final int    WAIT_TICKS   = 15;    // ticks after arrival before /f map
    private static final int    COLLECT_TICKS = 50;   // ticks to collect /f map output

    // ── State ───────────────────────────────────────────────────────
    private ScanState state = ScanState.IDLE;
    private GridNavigator grid;
    private final FMapParser parser = new FMapParser();

    private String currentDimension = "OVERWORLD";
    private int waitTicksLeft    = 0;
    private int collectTicksLeft = 0;

    // Mouse Button 4 toggle — track previous state to detect press edge
    private boolean mb4Prev = false;
    // Cooldown so one physical press doesn't fire multiple times
    private int mb4Cooldown = 0;

    private static final Minecraft mc = Minecraft.getMinecraft();

    // ── Forge Events ────────────────────────────────────────────────

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;

        // ── Mouse Button 4 toggle detection ─────────────────────────
        if (mb4Cooldown > 0) mb4Cooldown--;

        boolean mb4Now = Mouse.isButtonDown(3); // LWJGL: button 3 = MB4 (back)
        if (mb4Now && !mb4Prev && mb4Cooldown == 0) {
            mb4Cooldown = 10; // 10 tick debounce
            if (state == ScanState.IDLE) {
                startScan();
            } else {
                stopScan();
            }
        }
        mb4Prev = mb4Now;

        // ── State machine ────────────────────────────────────────────
        switch (state) {
            case FLYING_TO_POSITION: tickFlying();     break;
            case WAIT_BEFORE_MAP:    tickWait();       break;
            case COLLECTING_MAP:     tickCollecting(); break;
            case PROCESSING_MAP:     tickProcessing(); break;
            default: break;
        }
    }

    @SubscribeEvent
    public void onChatReceived(ClientChatReceivedEvent event) {
        if (state != ScanState.COLLECTING_MAP) return;
        parser.processChat(event.message, event.message.getFormattedText());
    }

    // ── State machine ticks ──────────────────────────────────────────

    private void tickFlying() {
        int[] target = grid.getCurrentPosition();
        if (target == null) { finishScan(); return; }

        EntityClientPlayerMP player = mc.thePlayer;
        player.capabilities.isFlying = true;
        player.sendPlayerAbilities();

        double dx = target[0] - player.posX;
        double dz = target[1] - player.posZ;
        double dy = FLY_Y - player.posY;
        double horizDist = Math.sqrt(dx * dx + dz * dz);

        // Y correction
        if (Math.abs(dy) > 2.0) {
            player.motionY = clamp(dy * 0.1, -FLY_SPEED, FLY_SPEED);
        } else {
            player.motionY = 0;
            if (Math.abs(dy) < 0.5) player.posY = FLY_Y;
        }

        // Horizontal movement
        if (horizDist > ARRIVAL_DIST) {
            double factor = Math.min(1.0, horizDist / 20.0); // ease near target
            player.motionX = (dx / horizDist) * FLY_SPEED * factor;
            player.motionZ = (dz / horizDist) * FLY_SPEED * factor;
        } else {
            player.motionX = 0;
            player.motionZ = 0;
            player.motionY = 0;
            state = ScanState.WAIT_BEFORE_MAP;
            waitTicksLeft = WAIT_TICKS;
        }
    }

    private void tickWait() {
        if (--waitTicksLeft <= 0) {
            parser.reset();
            mc.thePlayer.sendChatMessage("/f map");
            state = ScanState.COLLECTING_MAP;
            collectTicksLeft = COLLECT_TICKS;
        }
    }

    private void tickCollecting() {
        if (--collectTicksLeft <= 0) {
            state = ScanState.PROCESSING_MAP;
        }
    }

    private void tickProcessing() {
        EntityClientPlayerMP player = mc.thePlayer;
        int nearX = (int) player.posX;
        int nearZ = (int) player.posZ;

        // Get all blue base claims visible from this position
        List<FMapParser.ClaimInfo> claims =
                parser.getBlueClaimsWithInfo(player.posX, player.posZ);

        for (FMapParser.ClaimInfo claim : claims) {
            // Send to Discord — use PLAYER coords as "nearby" (within 6 chunk radius)
            DiscordWebhook.sendBaseFound(claim.factionName, nearX, nearZ, currentDimension);
            sendMsg("§b[BASE] §f" + claim.factionName + " §7near §e" + nearX + ", " + nearZ);
        }

        // Advance to next grid position
        int[] next = grid.getNextPosition();
        if (next == null) {
            finishScan();
        } else {
            state = ScanState.FLYING_TO_POSITION;
            // Progress update every 100 positions
            if (grid.getCurrentIndex() % 100 == 0) {
                sendMsg("§7[Scanner] §e" + grid.getProgressPercent()
                        + "% §7(" + grid.getCurrentIndex()
                        + "/" + grid.getTotalPositions() + ")");
            }
        }
    }

    // ── Commands ─────────────────────────────────────────────────────

    private void startScan() {
        // Detect dimension from world provider
        if (mc.theWorld != null) {
            int dimId = mc.theWorld.provider.getDimensionId();
            currentDimension = (dimId == 1) ? "END" : "OVERWORLD";
        }

        grid = new GridNavigator();
        parser.reset();
        state = ScanState.FLYING_TO_POSITION;

        sendMsg("§a[Scanner] STARTED — " + currentDimension
                + " | " + grid.getTotalPositions() + " positions");
        sendMsg("§7Press §fMouse 4 §7again to stop.");
        DiscordWebhook.sendStatus("Scan started on **" + currentDimension + "**");
    }

    private void stopScan() {
        state = ScanState.IDLE;
        sendMsg("§c[Scanner] STOPPED at position "
                + (grid != null ? grid.getCurrentIndex() : 0)
                + " — press MB4 to restart.");
        DiscordWebhook.sendStatus("Scan stopped at position "
                + (grid != null ? grid.getCurrentIndex() : 0));
    }

    private void finishScan() {
        state = ScanState.IDLE;
        sendMsg("§a[Scanner] SCAN COMPLETE!");
        DiscordWebhook.sendStatus("✅ **Scan complete** on " + currentDimension);
    }

    // ── Utilities ────────────────────────────────────────────────────

    private void sendMsg(String text) {
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(text));
        }
    }

    private double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
