package name.client;

import name.DifficultyManager;
import name.HarderChallenge;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

public final class ChallengeHud {

    private static final long TICKS_PER_STAGE = 7_200L;
    private static final int MAX_STAGE = 12;

    private static final int CENTER_WIDTH = 210;
    private static final int CENTER_HEIGHT = 72;
    private static final int TOP_MARGIN = 8;

    private static final int SIDE_WIDTH = 154;
    private static final int SIDE_MARGIN = 8;
    private static final int ROW_HEIGHT = 11;

    private static final int STATUS_WIDTH = 132;
    private static final int STATUS_HEIGHT = 94;

    private static Object trackedLevel;
    private static boolean wasDead;
    private static int deathCount;
    private static final long STAGE_ALERT_DURATION_MILLIS = 3_200L;

    private static int lastRenderedStage = -1;
    private static long stageAlertStartedMillis;
    private static long stageAlertUntilMillis;

    private static final String[] MODIFIERS = {
            "Mob Speed I",
            "Endless Night",
            "Giant Zombies",
            "Sharpness I Swords",
            "Half Hunger",
            "Player Slowness",
            "Faster Skeletons",
            "Full Iron Armor",
            "Constant Mob Spawns",
            "Mob Strength II",
            "10s Invisible Mobs",
            "Final Mob Upgrades"
    };

    private static final int[] MODIFIER_COLORS = {
            0xFF48D7FF,
            0xFFFF5C67,
            0xFFB783FF,
            0xFFFF8A3D,
            0xFFFFC857,
            0xFF79A7FF,
            0xFFFF6FAE,
            0xFF9A75FF,
            0xFF52E0A4,
            0xFFFF496C,
            0xFFFFE45C,
            0xFFFFB347
    };

    private ChallengeHud() {
    }

    public static void initialize() {
        Identifier id = Identifier.fromNamespaceAndPath(
                HarderChallenge.MOD_ID,
                "challenge_hud"
        );

        HudElementRegistry.addFirst(id, ChallengeHud::render);
    }

    private static void render(
            GuiGraphicsExtractor graphics,
            DeltaTracker deltaTracker
    ) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null || minecraft.level == null) {
            trackedLevel = null;
            wasDead = false;
            lastRenderedStage = -1;
            stageAlertStartedMillis = 0L;
            stageAlertUntilMillis = 0L;
            BloodMoonClientState.reset();
            HunterClientState.reset();
            return;
        }

        updateDeathCount(minecraft);

        Font font = minecraft.font;
        int screenWidth = graphics.guiWidth();
        int centerX = screenWidth / 2;
        int centerPanelX = centerX - CENTER_WIDTH / 2;

        int stage = clamp(DifficultyManager.getStage(), 0, MAX_STAGE);
        long elapsedTicks = Math.max(0L, DifficultyManager.getTicksElapsed());

        long stageTicks = stage >= MAX_STAGE
                ? TICKS_PER_STAGE
                : elapsedTicks % TICKS_PER_STAGE;

        long remainingTicks = stage >= MAX_STAGE
                ? 0L
                : TICKS_PER_STAGE - stageTicks;

        float progress = stage >= MAX_STAGE
                ? 1.0F
                : stageTicks / (float) TICKS_PER_STAGE;

        updateStageAlert(stage);
        drawCenterPanel(graphics, centerPanelX, TOP_MARGIN, progress, stage);

        String timer = stage >= MAX_STAGE
                ? "FINAL"
                : formatTime(remainingTicks);

        String stageLabel = stage >= MAX_STAGE
                ? "★ FINAL STAGE ★"
                : "★ STAGE " + stage + " ★";

        graphics.centeredText(
                font,
                "MINECRAFT GETS HARDER",
                centerX,
                TOP_MARGIN + 5,
                0xFFB7C0CC
        );

        graphics.centeredText(
                font,
                timer,
                centerX,
                TOP_MARGIN + 17,
                0xFFFFFFFF
        );

        graphics.centeredText(
                font,
                stageLabel,
                centerX,
                TOP_MARGIN + 31,
                stage >= MAX_STAGE ? 0xFFFF6B6B : 0xFFFFC94A
        );

        String nextModifierText;
        int nextModifierColor;
        if (stage >= MAX_STAGE) {
            nextModifierText = "ALL MODIFIERS ACTIVE";
            nextModifierColor = 0xFFFF6B6B;
        } else {
            nextModifierText = "NEXT: " + MODIFIERS[stage] + " - " + formatTime(remainingTicks);
            nextModifierColor = MODIFIER_COLORS[stage];
        }

        graphics.centeredText(
                font,
                nextModifierText,
                centerX,
                TOP_MARGIN + 58,
                nextModifierColor
        );

        int alertY = TOP_MARGIN + CENTER_HEIGHT + 5;
        if (HunterClientState.isActive()) {
            graphics.centeredText(
                    font,
                    "YOU ARE BEING HUNTED.",
                    centerX,
                    alertY,
                    0xFFFF3030
            );
            alertY += 13;
        }

        drawStageChangeAlert(graphics, font, centerX, alertY, stage);

        drawStatusPanel(
                graphics,
                font,
                SIDE_MARGIN,
                TOP_MARGIN,
                stage,
                elapsedTicks
        );

        int sideX = screenWidth - SIDE_WIDTH - SIDE_MARGIN;
        int activeHeight = panelHeightForRows(Math.max(1, stage));

        drawModifierPanel(
                graphics,
                font,
                sideX,
                TOP_MARGIN,
                "ACTIVE MODIFIERS",
                0,
                stage,
                0xFF52E0A4,
                stage == 0 ? "None yet" : null
        );

        int upcomingCount = Math.min(3, MAX_STAGE - stage);
        int upcomingY = TOP_MARGIN + activeHeight + 7;

        drawModifierPanel(
                graphics,
                font,
                sideX,
                upcomingY,
                stage >= MAX_STAGE ? "CHALLENGE STATUS" : "UPCOMING",
                stage,
                upcomingCount,
                stage >= MAX_STAGE ? 0xFFFF6B6B : 0xFF48D7FF,
                stage >= MAX_STAGE ? "All modifiers active" : null
        );
    }

    private static void updateDeathCount(Minecraft minecraft) {
        if (trackedLevel != minecraft.level) {
            trackedLevel = minecraft.level;
            wasDead = false;
            deathCount = 0;
        }

        boolean isDead = minecraft.player.getHealth() <= 0.0F;
        if (isDead && !wasDead) {
            deathCount++;
        }
        wasDead = isDead;
    }

    private static void drawStatusPanel(
            GuiGraphicsExtractor graphics,
            Font font,
            int x,
            int y,
            int stage,
            long elapsedTicks
    ) {
        int accentColor = progressColor(stage);

        graphics.fill(
                x,
                y,
                x + STATUS_WIDTH,
                y + STATUS_HEIGHT,
                0xA8141821
        );

        graphics.fill(
                x,
                y,
                x + 3,
                y + STATUS_HEIGHT,
                accentColor
        );

        graphics.text(font, "CHALLENGE STATUS", x + 9, y + 6, accentColor, true);
        graphics.fill(x + 9, y + 18, x + STATUS_WIDTH - 9, y + 19, 0xFF303846);

        graphics.text(font, "RUN ACTIVE", x + 10, y + 25, 0xFF52E0A4, true);
        graphics.text(font, "Deaths: " + deathCount, x + 10, y + 39, 0xFFFF6B6B, true);
        graphics.text(font, "Difficulty: " + difficultyName(stage), x + 10, y + 53, accentColor, true);
        graphics.text(font, "Stages: " + stage + " / " + MAX_STAGE, x + 10, y + 67, 0xFFFFC94A, true);
        graphics.text(font, "Total Time: " + formatElapsedTime(elapsedTicks), x + 10, y + 81, 0xFFB7C0CC, true);
    }

    private static String difficultyName(int stage) {
        if (stage >= MAX_STAGE) {
            return "FINAL";
        }
        if (stage >= 10) {
            return "EXTREME";
        }
        if (stage >= 7) {
            return "BRUTAL";
        }
        if (stage >= 4) {
            return "HARD";
        }
        if (stage >= 1) {
            return "RISING";
        }
        return "NORMAL";
    }

    private static String formatElapsedTime(long ticks) {
        long totalSeconds = Math.max(0L, ticks) / 20L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0L) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    private static void drawCenterPanel(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            float progress,
            int stage
    ) {
        graphics.fill(
                x,
                y,
                x + CENTER_WIDTH,
                y + CENTER_HEIGHT,
                0xA8141821
        );

        graphics.fill(
                x + 12,
                y,
                x + CENTER_WIDTH - 12,
                y + 2,
                stage >= MAX_STAGE ? 0xFFFF5C67 : 0xFF48D7FF
        );

        drawProgressBar(
                graphics,
                x + 13,
                y + 47,
                CENTER_WIDTH - 26,
                progress,
                progressColor(stage)
        );
    }

    private static void updateStageAlert(int stage) {
        if (lastRenderedStage < 0) {
            lastRenderedStage = stage;
            return;
        }

        if (stage != lastRenderedStage) {
            lastRenderedStage = stage;
            stageAlertStartedMillis = System.currentTimeMillis();
            stageAlertUntilMillis = stageAlertStartedMillis + STAGE_ALERT_DURATION_MILLIS;
        }
    }

    private static void drawStageChangeAlert(
            GuiGraphicsExtractor graphics,
            Font font,
            int centerX,
            int ignoredY,
            int stage
    ) {
        long now = System.currentTimeMillis();
        if (now >= stageAlertUntilMillis || stageAlertStartedMillis <= 0L) {
            return;
        }

        float elapsed = (now - stageAlertStartedMillis) / (float) STAGE_ALERT_DURATION_MILLIS;
        float fadeIn = clamp(elapsed / 0.10F, 0.0F, 1.0F);
        float fadeOut = clamp((1.0F - elapsed) / 0.22F, 0.0F, 1.0F);
        float alpha = Math.min(fadeIn, fadeOut);

        // Quick drop-in with a tiny settle, then a clean fade out.
        float slideProgress = clamp(elapsed / 0.16F, 0.0F, 1.0F);
        float eased = 1.0F - (1.0F - slideProgress) * (1.0F - slideProgress);
        int y = Math.round(-46 + (60 * eased));
        if (slideProgress >= 1.0F) {
            y = 14;
        }

        String modifierText;
        int accentColor;
        if (stage <= 0) {
            modifierText = "CHALLENGE RESET";
            accentColor = 0xFF48D7FF;
        } else if (stage >= MAX_STAGE) {
            modifierText = "ALL MODIFIERS ACTIVE";
            accentColor = 0xFFFF5C67;
        } else {
            modifierText = MODIFIERS[stage - 1].toUpperCase();
            accentColor = MODIFIER_COLORS[stage - 1];
        }

        int width = 286;
        int height = 48;
        int x = centerX - width / 2;
        int backgroundAlpha = Math.round(220 * alpha);
        int lineAlpha = Math.round(255 * alpha);
        int textAlpha = Math.round(255 * alpha);

        int background = (backgroundAlpha << 24) | 0x00141922;
        int accent = (lineAlpha << 24) | (accentColor & 0x00FFFFFF);
        int white = (textAlpha << 24) | 0x00FFFFFF;

        graphics.fill(x, y, x + width, y + height, background);
        graphics.fill(x, y, x + width, y + 3, accent);
        graphics.fill(x, y + height - 2, x + width, y + height, accent);

        graphics.centeredText(
                font,
                stage >= MAX_STAGE ? "⚠ FINAL STAGE ⚠" : "⚠ STAGE " + stage + " ⚠",
                centerX,
                y + 10,
                accent
        );

        graphics.centeredText(
                font,
                stage <= 0 ? modifierText : "NEW MODIFIER: " + modifierText,
                centerX,
                y + 27,
                white
        );
    }

    private static void drawModifierPanel(
            GuiGraphicsExtractor graphics,
            Font font,
            int x,
            int y,
            String title,
            int startIndex,
            int count,
            int accentColor,
            String emptyText
    ) {
        int rows = Math.max(1, count);
        int height = panelHeightForRows(rows);

        graphics.fill(
                x,
                y,
                x + SIDE_WIDTH,
                y + height,
                0xA8141821
        );

        graphics.fill(
                x,
                y,
                x + 3,
                y + height,
                accentColor
        );

        graphics.text(
                font,
                title,
                x + 9,
                y + 6,
                accentColor,
                true
        );

        if (emptyText != null) {
            graphics.text(
                    font,
                    emptyText,
                    x + 10,
                    y + 20,
                    0xFFB7C0CC,
                    true
            );
            return;
        }

        for (int i = 0; i < count; i++) {
            int modifierIndex = startIndex + i;
            int rowY = y + 20 + i * ROW_HEIGHT;
            int color = MODIFIER_COLORS[modifierIndex];

            graphics.fill(
                    x + 10,
                    rowY + 3,
                    x + 14,
                    rowY + 7,
                    color
            );

            graphics.text(
                    font,
                    MODIFIERS[modifierIndex],
                    x + 19,
                    rowY,
                    color,
                    true
            );
        }
    }

    private static int panelHeightForRows(int rows) {
        return 27 + rows * ROW_HEIGHT;
    }

    private static void drawProgressBar(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            int width,
            float progress,
            int fillColor
    ) {
        int filledWidth = Math.round(width * clamp(progress, 0.0F, 1.0F));

        graphics.fill(
                x,
                y,
                x + width,
                y + 5,
                0xFF262D38
        );

        if (filledWidth > 0) {
            graphics.fill(
                    x,
                    y,
                    x + Math.min(width, filledWidth),
                    y + 5,
                    fillColor
            );
        }
    }

    private static int progressColor(int stage) {
        if (stage >= 10) {
            return 0xFFFF5C67;
        }
        if (stage >= 7) {
            return 0xFFFF8A3D;
        }
        if (stage >= 4) {
            return 0xFFB783FF;
        }
        return 0xFF48D7FF;
    }

    private static String formatTime(long ticks) {
        long totalSeconds = (ticks + 19L) / 20L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;

        return String.format("%02d:%02d", minutes, seconds);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
