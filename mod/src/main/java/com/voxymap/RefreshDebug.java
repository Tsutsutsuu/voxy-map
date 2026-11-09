package com.voxymap;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Optional chat output used while testing the refresh pipeline.
 *
 * <p>26.2 moved the chat component from {@code Gui.getChat()} to {@code Gui.hud.getChat()}; the
 * stonecutter build replaces the accessor for 26.1.2. Messages are always queued to the client
 * thread and only sent when {@code debugRefreshChat} is enabled.</p>
 */
public final class RefreshDebug {
    private RefreshDebug() {
    }

    public static boolean enabled() {
        return VoxyMapConfig.DEBUG_REFRESH_CHAT.get();
    }

    public static boolean verbose() {
        return enabled() && VoxyMapConfig.DEBUG_REFRESH_VERBOSE.get();
    }

    /** Queues one "[voxymap] ..." line to the client chat (no-op when disabled). */
    public static void chat(String text, ChatFormatting color) {
        if (!enabled() || text == null || text.isEmpty()) return;
        Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            try {
                // 1.21.1: the chat component hangs directly off Gui (26.2 moved it under Gui.hud)
                Minecraft.getInstance().gui.getChat().addMessage(
                        Component.literal("[voxymap] " + text).withStyle(color));
            } catch (Throwable ignored) {
                // debug output must never break a refresh
            }
        });
    }
}
