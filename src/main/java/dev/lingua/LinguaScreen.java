package dev.lingua;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

public class LinguaScreen extends Screen {
    private static final List<String> LANGS = List.of(
            "Japanese", "Chinese (Simplified)", "Chinese (Traditional)", "Korean", "English",
            "Spanish", "Portuguese", "Russian", "German", "French", "Turkish", "Arabic",
            "Indonesian", "Thai", "Vietnamese", "Polish", "Italian", "Filipino");

    private EditBox keyBox, inputBox;
    private Button inBtn, outBtn, langBtn;
    private volatile String preview = "";
    private volatile String back = "";

    public LinguaScreen() { super(Component.literal("Lingua Translator")); }

    @Override
    protected void init() {
        LinguaConfig c = LinguaConfig.get();
        int w = 260, x = width / 2 - w / 2, y = height / 2 - 95;

        inBtn = addRenderableWidget(Button.builder(Component.empty(), b -> {
            c.incoming = !c.incoming; LinguaConfig.save(); refresh();
        }).bounds(x, y, w, 20).build());

        outBtn = addRenderableWidget(Button.builder(Component.empty(), b -> {
            c.outgoing = !c.outgoing; LinguaConfig.save(); refresh();
        }).bounds(x, y + 24, w, 20).build());

        langBtn = addRenderableWidget(Button.builder(Component.empty(), b -> {
            int i = LANGS.indexOf(c.targetLanguage);
            c.targetLanguage = LANGS.get((i + 1) % LANGS.size());
            refresh();
        }).bounds(x, y + 48, w, 20).build());

        keyBox = new EditBox(font, x, y + 78, w, 20, Component.literal("API key"));
        keyBox.setMaxLength(300);
        String saved = c.key();
        keyBox.setHint(Component.literal(saved.isEmpty()
                ? "API key yahan paste karo (Gemini ya Anthropic)"
                : "Key saved (****" + saved.substring(Math.max(0, saved.length() - 4)) + ") - badalni ho to paste karo"));
        addRenderableWidget(keyBox);

        inputBox = new EditBox(font, x, y + 108, w, 20, Component.literal("Message"));
        inputBox.setMaxLength(200);
        inputBox.setHint(Component.literal("Hinglish me likho, e.g. bhai kya chal raha hai"));
        addRenderableWidget(inputBox);

        addRenderableWidget(Button.builder(Component.literal("Preview"), b -> doPreview())
                .bounds(x, y + 134, w / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Translate + Send"), b -> doSend())
                .bounds(x + w / 2 + 2, y + 134, w / 2 - 2, 20).build());

        refresh();
        setInitialFocus(inputBox);
    }

    private static Component onOff(String label, boolean on) {
        return Component.literal(label + ": ")
                .append(Component.literal(on ? "ON" : "OFF").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private void refresh() {
        LinguaConfig c = LinguaConfig.get();
        inBtn.setMessage(onOff("Unka chat -> Hinglish", c.incoming));
        outBtn.setMessage(onOff("Mera chat/whisper -> unki language", c.outgoing));
        langBtn.setMessage(Component.literal("Unki language: " + c.targetLanguage + "  (click = badlo)"));
    }

    private void applyKey() {
        String k = keyBox.getValue().trim();
        if (!k.isEmpty()) LinguaConfig.get().apiKey = k;
        LinguaConfig.save();
    }

    private void doPreview() {
        String text = inputBox.getValue().trim();
        if (text.isEmpty()) return;
        applyKey();
        preview = "Translate ho raha hai...";
        back = "";
        Translator.fromHinglish(text, LinguaConfig.get().targetLanguage)
                .thenCompose(t -> { preview = t; return Translator.toHinglish(t, false); })
                .whenComplete((bk, err) -> {
                    if (err != null) { preview = "Error: " + LinguaClient.errMsg(err); back = ""; }
                    else back = bk;
                });
    }

    private void doSend() {
        String text = inputBox.getValue().trim();
        if (text.isEmpty()) return;
        applyKey();
        LinguaClient.translateAndSend(text);
        inputBox.setValue("");
        preview = ""; back = "";
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int w = 260, x = width / 2 - w / 2, y = height / 2 - 95;
        g.centeredText(font, title, width / 2, y - 14, 0xFFFFFFFF);

        int ty = y + 162;
        if (!preview.isEmpty()) {
            for (FormattedCharSequence line : font.split(FormattedText.of("Wo ye dekhenge: " + preview), w)) {
                g.text(font, line, x, ty, 0xFF8CFF8C); ty += 10;
            }
        }
        if (!back.isEmpty()) {
            for (FormattedCharSequence line : font.split(FormattedText.of("Wapas Hinglish me: " + back), w)) {
                g.text(font, line, x, ty, 0xFFAAAAAA); ty += 10;
            }
        }
    }

    @Override
    public void onClose() {
        applyKey();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
