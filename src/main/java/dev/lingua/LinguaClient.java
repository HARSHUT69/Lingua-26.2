package dev.lingua;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class LinguaClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("lingua");

    /** Wo messages jo hum khud bhej rahe hain (dobara translate na ho). */
    private static final Set<String> SKIP = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        LinguaConfig.load();

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.lingua.open", InputConstants.Type.KEYSYM, InputConstants.KEY_K, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.consumeClick()) mc.setScreenAndShow(new LinguaScreen());
        });

        // Server plugins aksar chat ko "system message" bana ke bhejte hain, isliye dono events.
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) onIncoming(msg); });
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) -> onIncoming(msg));

        ClientSendMessageEvents.ALLOW_CHAT.register(LinguaClient::onOutgoing);
    }

    // ---------- incoming ----------
    private static void onIncoming(Component message) {
        LinguaConfig cfg = LinguaConfig.get();
        if (!cfg.incoming || cfg.key().isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        String text = message.getString().trim();
        if (text.length() < 3 || text.startsWith("[T]")) return;
        if (mc.getUser() != null && text.contains("<" + mc.getUser().getName() + ">")) return;
        for (String s : SKIP) if (text.contains(s)) return;
        if (IN_FLIGHT.get() >= 6) return; // spam me API na jalao

        IN_FLIGHT.incrementAndGet();
        Translator.toHinglish(text, true).whenComplete((t, err) -> {
            IN_FLIGHT.decrementAndGet();
            if (err != null || t == null || t.equalsIgnoreCase("SAME") || t.isBlank()) return;
            mc.execute(() -> addChatLine(
                    Component.literal("[T] " + t).withStyle(ChatFormatting.GRAY)));
        });
    }

    // ---------- outgoing ----------
    private static boolean onOutgoing(String msg) {
        if (SKIP.remove(msg)) return true;                 // hamara apna translated msg
        LinguaConfig cfg = LinguaConfig.get();
        if (!cfg.outgoing || cfg.key().isEmpty()) return true;

        if (msg.startsWith("!")) {                         // "!hello" = bina translate kiye bhejo
            String raw = msg.substring(1).trim();
            if (!raw.isEmpty()) {
                SKIP.add(raw);
                Minecraft mc = Minecraft.getInstance();
                if (mc.getConnection() != null) mc.getConnection().sendChat(raw);
            }
            return false;
        }
        translateAndSend(msg);
        return false;                                      // original cancel
    }

    public static void translateAndSend(String msg) {
        Minecraft mc = Minecraft.getInstance();
        LinguaConfig cfg = LinguaConfig.get();
        Translator.fromHinglish(msg, cfg.targetLanguage).whenComplete((t, err) -> mc.execute(() -> {
            if (err != null) {
                info("Translate fail: " + errMsg(err) + "  (bina translate bhejne ke liye ! laga ke likho)");
                return;
            }
            String out = t.length() > 256 ? t.substring(0, 256) : t;
            if (mc.getConnection() == null) return;
            SKIP.add(out);
            mc.getConnection().sendChat(out);
            info("[T] " + msg + "  ->  " + out);
        }));
    }

    public static void info(String s) {
        addChatLine(Component.literal(s).withStyle(ChatFormatting.GRAY));
    }

    /** Sirf tumhari screen pe line dikhata hai, server ko kuch nahi jata. */
    private static void addChatLine(Component c) {
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(c);
    }

    public static String errMsg(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
