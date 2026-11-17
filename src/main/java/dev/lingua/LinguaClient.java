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

    /** Server ka echo (jaise "You whisper to X: ...") dobara translate na ho. */
    private static final Set<String> SKIP = ConcurrentHashMap.newKeySet();
    /** Jo message abhi translate karke bheja, wo wapas hamare hi handler se na guzre (loop na bane). */
    private static final Set<String> LOOP = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static KeyMapping openKey;

    /** /msg <player> <text> jaise commands, jinka last ka text translate hoga. */
    private static final Set<String> WHISPER_WITH_NAME = Set.of("msg", "tell", "w", "whisper", "m", "t");
    /** /r <text> */
    private static final Set<String> WHISPER_REPLY = Set.of("r", "reply");

    @Override
    public void onInitializeClient() {
        LinguaConfig.load();

        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.lingua.open", keyboardType(), InputConstants.KEY_K, KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.consumeClick()) mc.setScreenAndShow(new LinguaScreen());
        });

        // Server plugins aksar chat ko "system message" bana ke bhejte hain, isliye dono events.
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) onIncoming(msg); });
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) -> onIncoming(msg));

        // Translator ON ho to normal chat aur /msg /r wale whisper translate hote hain.
        ClientSendMessageEvents.ALLOW_CHAT.register(LinguaClient::onOutgoingChat);
        ClientSendMessageEvents.ALLOW_COMMAND.register(LinguaClient::onOutgoingCommand);
    }

    /** 26.2 me KEYSYM, 26.3 (SDL3) me KEYBOARD: dono versions pe ek hi code chale. */
    private static InputConstants.Type keyboardType() {
        try {
            return InputConstants.Type.valueOf("KEYBOARD");
        } catch (IllegalArgumentException e) {
            return InputConstants.Type.valueOf("KEYSYM");
        }
    }

    // ---------- incoming: unka chat -> Your language ----------
    private static void onIncoming(Component message) {
        LinguaConfig cfg = LinguaConfig.get();
        if (!cfg.enabled || cfg.key().isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        String text = message.getString().trim();
        if (text.length() < 3 || text.startsWith("[T]")) return;
        if (mc.getUser() != null && text.contains("<" + mc.getUser().getName() + ">")) return;
        for (String s : SKIP) if (text.contains(s)) return;
        if (IN_FLIGHT.get() >= 6) return; // spam me API na jalao

        IN_FLIGHT.incrementAndGet();
        Translator.toMine(text, true).whenComplete((t, err) -> {
            IN_FLIGHT.decrementAndGet();
            if (err != null || t == null || t.equalsIgnoreCase("SAME") || t.isBlank()) return;
            mc.execute(() -> addChatLine(
                    Component.literal("[T] " + t).withStyle(ChatFormatting.GRAY)));
        });
    }

    // ---------- outgoing: normal chat + /msg <player> <text> ----------
    private static boolean onOutgoingChat(String msg) {
        if (LOOP.remove(msg)) return true;                 // hamara apna translated message
        LinguaConfig cfg = LinguaConfig.get();
        if (!cfg.enabled || cfg.key().isEmpty()) return true;
        if (msg.isBlank()) return true;
        translateAndSend(msg, null);
        return false;                                      // original cancel
    }

    /** Command slash ke bina aata hai, jaise "msg Steve bhai kya haal". */
    private static boolean onOutgoingCommand(String command) {
        if (LOOP.remove("/" + command)) return true;       // hamara apna translated command
        LinguaConfig cfg = LinguaConfig.get();
        if (!cfg.enabled || cfg.key().isEmpty()) return true;

        String[] p = command.split(" ", 3);
        String name = p[0].toLowerCase();
        String prefix;
        String text;
        if (WHISPER_WITH_NAME.contains(name) && p.length == 3) {
            prefix = p[0] + " " + p[1] + " ";
            text = p[2];
        } else if (WHISPER_REPLY.contains(name) && command.length() > p[0].length() + 1) {
            prefix = p[0] + " ";
            text = command.substring(p[0].length() + 1);
        } else {
            return true;                                   // baaki commands waise hi jayein
        }
        text = text.trim();
        if (text.isEmpty()) return true;
        translateAndSend(text, prefix);
        return false;                                      // original cancel
    }

    /** GUI ke "Translate + Send" button ke liye (toggle se alag, hamesha translate karke public chat me bhejta hai). */
    public static void translateAndSend(String msg) {
        translateAndSend(msg, null);
    }

    /** cmdPrefix == null -> normal chat; warna command, jaise "msg Steve ". */
    public static void translateAndSend(String msg, String cmdPrefix) {
        Minecraft mc = Minecraft.getInstance();
        LinguaConfig cfg = LinguaConfig.get();
        Translator.fromMine(msg, cfg.targetLanguage).whenComplete((t, err) -> mc.execute(() -> {
            if (err != null) {
                info("Translate fail: " + errMsg(err) + "  (message bheja nahi gaya)");
                return;
            }
            String out = t.length() > 256 ? t.substring(0, 256) : t;
            if (mc.getConnection() == null) return;
            if (SKIP.size() > 50) SKIP.clear();
            SKIP.add(out);                                 // server ka echo dobara translate na ho
            if (cmdPrefix == null) {
                LOOP.add(out);
                mc.getConnection().sendChat(out);
            } else {
                LOOP.add("/" + cmdPrefix + out);
                mc.getConnection().sendCommand(cmdPrefix + out);
            }
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
