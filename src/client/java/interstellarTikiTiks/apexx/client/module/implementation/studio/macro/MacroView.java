package interstellarTikiTiks.apexx.client.module.implementation.studio.macro;

import interstellarTikiTiks.apexx.client.gui.gpuui.kit.*;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSmoothScroll;
import interstellarTikiTiks.apexx.client.gui.gpuui.kit.UiKit.Style;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics;
import interstellarTikiTiks.apexx.client.module.implementation.studio.macro.MacroDefinition;
import interstellarTikiTiks.apexx.client.module.implementation.studio.macro.MacroDefinition.ActionMode;
import interstellarTikiTiks.apexx.client.module.implementation.studio.macro.MacroDefinition.TargetMode;
import interstellarTikiTiks.apexx.client.module.implementation.studio.macro.MacroDefinition.TriggerMode;
import interstellarTikiTiks.apexx.client.module.implementation.studio.macro.MacroManager;

import java.util.List;

/**
 * Step-macro editor inside the menu: the list of saved macros on the left and a scrolling form on the
 * right (trigger, cooldown, up to 16 steps). Replaces the old standalone Macro Creator screen and
 * validates exactly as it did before saving.
 */
public final class MacroView implements interstellarTikiTiks.apexx.client.module.hooks.StudioProvider.Panel {
    private List<MacroDefinition> macros = List.of();
    private boolean loaded;
    private Runnable openTemplates;
    private MacroDefinition draft;
    private int stepIndex;
    private String status = "";
    private boolean statusIsError;
    private long statusAt;
    private boolean deleteArmed;
    private long deleteArmedAt;

    private final UiSmoothScroll listScroll = new UiSmoothScroll();
    private final UiSmoothScroll formScroll = new UiSmoothScroll();
    private final float[] listRect = new float[4], formRect = new float[4];
    private float formContentH;
    private float lastScale = 1f;

    private final UiTextField name = new UiTextField("Macro name").maxLength(32).allow(UiTextField::isNameChar);
    private final UiTextField cooldown = numeric("150", 5);
    private final UiTextField shieldHold = numeric("80", 4);
    private final UiTextField blockFilter = new UiTextField("minecraft:obsidian").maxLength(80).allow(UiTextField::isItemIdChar);
    private final UiTextField itemId = new UiTextField("minecraft:ender_pearl").maxLength(80).allow(UiTextField::isItemIdChar);
    private final UiTextField slot = numeric("1-9", 1);
    private final UiTextField preDelay = numeric("20", 5);
    private final UiTextField postDelay = numeric("20", 5);

    public MacroView() {
        name.onChange(() -> { if (draft != null) draft.setName(name.value()); });
        cooldown.onChange(() -> { if (draft != null) draft.setCooldownMs(number(cooldown, draft.getCooldownMs())); })
                .onBlur(this::syncFields);
        shieldHold.onChange(() -> { if (draft != null) draft.setShieldHoldMs(number(shieldHold, draft.getShieldHoldMs())); })
                .onBlur(this::syncFields);
        blockFilter.onChange(() -> { if (draft != null) draft.setBlockFilterId(blockFilter.value()); });
        itemId.onChange(() -> { MacroDefinition.Step st = step(); if (st != null) st.setItemId(itemId.value()); });
        slot.onChange(() -> { MacroDefinition.Step st = step(); if (st != null) st.setSlot(number(slot, st.getSlot())); })
                .onBlur(this::syncFields);
        preDelay.onChange(() -> { MacroDefinition.Step st = step(); if (st != null) st.setPreDelayMs(number(preDelay, st.getPreDelayMs())); })
                .onBlur(this::syncFields);
        postDelay.onChange(() -> { MacroDefinition.Step st = step(); if (st != null) st.setPostDelayMs(number(postDelay, st.getPostDelayMs())); })
                .onBlur(this::syncFields);
    }

    public MacroView onOpenTemplates(Runnable action) {
        openTemplates = action;
        return this;
    }

    private static UiTextField numeric(String placeholder, int digits) {
        return new UiTextField(placeholder).maxLength(digits).allow(UiTextField::isDigit);
    }

    private static int number(UiTextField f, int fallback) {
        if (f.isEmpty()) return fallback;
        try {
            return Integer.parseInt(f.value());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private MacroDefinition.Step step() {
        return draft == null ? null : draft.getStep(stepIndex);
    }

    /** Re-reads every text field from the draft (after a step change, blur, or load). */
    private void syncFields() {
        if (draft == null) return;
        name.setValue(draft.getName());
        cooldown.setValue(Integer.toString(draft.getCooldownMs()));
        shieldHold.setValue(Integer.toString(draft.getShieldHoldMs()));
        blockFilter.setValue(draft.getBlockFilterId());
        MacroDefinition.Step st = step();
        if (st != null) {
            itemId.setValue(st.getItemId());
            slot.setValue(Integer.toString(st.getSlot()));
            preDelay.setValue(Integer.toString(st.getPreDelayMs()));
            postDelay.setValue(Integer.toString(st.getPostDelayMs()));
        }
    }

    /** Refresh the saved list from the manager. */
    public void refresh() {
        macros = MacroManager.getInstance().getMacros();
        loaded = true;
    }

    /** Preview/testing hook: show this list and edit one without touching the manager. */
    public void preview(List<MacroDefinition> list, int select) {
        macros = List.copyOf(list);
        loaded = true;
        load(macros.get(select));
    }

    private void load(MacroDefinition m) {
        draft = m.copy();
        stepIndex = 0;
        deleteArmed = false;
        syncFields();
        formScroll.reset();
    }

    private void startNew() {
        MacroDefinition fresh = MacroManager.getInstance().createDefaultMacro();
        load(fresh);
        say("New macro. Fill it in and press Save.", false);
    }

    private void say(String text, boolean error) {
        status = text;
        statusIsError = error;
        statusAt = Long.MIN_VALUE;
    }

    // ------------------------------------------------------------------------------------------

    public boolean scroll(double mx, double my, double amount, long now) {
        if (inside(mx, my, formRect)) { formScroll.wheel(amount, 56f * lastScale, now); return true; }
        if (inside(mx, my, listRect)) { listScroll.wheel(amount, 56f * lastScale, now); return true; }
        return false;
    }

    private static boolean inside(double mx, double my, float[] r) {
        return mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    public void draw(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                     float mx, float my) {
        lastScale = s;
        if (!loaded) refresh();
        if (statusAt == Long.MIN_VALUE) statusAt = host.now();
        float padX = Math.round(22f * s);
        UiKit.title(b, host, "Macros", x + padX, y + Math.round(20f * s), s);
        if (openTemplates != null) {
            UiKit.button(b, host, "Templates / clone", x + padX + Math.round(90f * s), y + Math.round(14f * s),
                    UiKit.buttonWidth("Templates / clone", s), Math.round(30f * s), s,
                    Style.SECONDARY, true, mx, my, openTemplates);
        }
        String count = macros.size() + " / " + 128 + " saved";
        float newW = UiKit.buttonWidth("New macro", s);
        float cw = GpuUiFontMetrics.textWidth(count, 9.5f * s);
        b.text(x + w - padX - newW - Math.round(14f * s) - cw, y + Math.round(24f * s), 9.5f * s, host.muted2Color(), count);
        UiKit.button(b, host, "New macro", x + w - padX - newW, y + Math.round(14f * s), newW, Math.round(30f * s), s,
                Style.PRIMARY, true, mx, my, this::startNew);
        UiKit.divider(b, host, x + padX, y + Math.round(56f * s), w - padX * 2f);

        float top = y + Math.round(72f * s);
        float bottom = y + h - Math.round(16f * s);
        float gap = Math.round(20f * s);
        float listW = Math.round(Math.min(300f * s, (w - padX * 2f) * 0.34f));
        float lx = x + padX, ex = lx + listW + gap, ew = x + w - padX - ex;

        drawList(b, host, lx, top, listW, bottom - top, s, mx, my);
        if (draft == null) {
            UiKit.card(b, host, ex, top, ew, bottom - top, s);
            UiKit.emptyState(b, host, "Pick a macro or create a new one",
                    "Macros run a short sequence of slot swaps and actions from one trigger.",
                    ex, top + (bottom - top) * 0.5f - Math.round(14f * s), ew, s);
        } else {
            drawEditor(b, host, ex, top, ew, bottom - top, s, mx, my);
        }
    }

    // ---- saved list -----------------------------------------------------------------------------

    private void drawList(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                          float mx, float my) {
        listRect[0] = x; listRect[1] = y; listRect[2] = w; listRect[3] = h;
        float rowH = Math.round(54f * s), gap = Math.round(8f * s), pitch = rowH + gap;
        listScroll.update(host.frameDelta(), h, Math.max(0f, macros.size() * pitch - gap), host.now());
        float scroll = listScroll.offset();
        if (macros.isEmpty()) {
            UiKit.emptyState(b, host, "No macros yet", "Press New macro to make one.", x, y + Math.round(30f * s), w, s);
        }
        b.clipPush(x, y, w, h, 0);
        host.beginHitClip(x, y, w, h);
        for (int i = 0; i < macros.size(); i++) {
            float ry = y + i * pitch - scroll;
            if (ry + rowH < y || ry > y + h) continue;
            MacroDefinition m = macros.get(i);
            boolean sel = draft != null && m.getId().equals(draft.getId());
            boolean hover = host.hovered(mx, my, x, ry, w, rowH);
            float r = Math.round(10f * s);
            UiKit.card(b, host, x, ry, w, rowH, s);
            if (hover && !sel) b.rect(x, ry, w, rowH, r, host.controlFill(0x0c));
            if (sel) {
                b.rect(x, ry, w, rowH, r, UiKit.withAlpha(host.accent(), 0x14));
                b.border(x, ry, w, rowH, r, 1f, UiKit.withAlpha(host.accent(), 0xa0));
            }
            float tx = x + Math.round(16f * s);
            b.text(tx, ry + Math.round(11f * s), 12f * s, m.isEnabled() ? host.textColor() : host.muted2Color(),
                    UiKit.fit(m.getName(), w - 40f * s, 12f * s), UiDrawCommand.TextWeight.SEMIBOLD);
            String detail = m.getTriggerMode().getLabel() + "  ·  " + m.getStepCount()
                    + (m.getStepCount() == 1 ? " step" : " steps");
            b.text(tx, ry + Math.round(31f * s), 9.5f * s, host.muted2Color(), UiKit.fit(detail, w - 40f * s, 9.5f * s));
            b.circle(x + w - Math.round(16f * s), ry + rowH * 0.5f, Math.round(3.5f * s),
                    m.isEnabled() ? UiKit.SUCCESS : host.controlFill(0x40));
            host.hit(x, ry, w, rowH, () -> load(m));
        }
        host.endHitClip();
        b.clipPop();
        listScroll.paintBar(b, x + w + Math.round(3f * s), y, h, s, host.textColor(), host.now());
    }

    // ---- editor ---------------------------------------------------------------------------------

    private void drawEditor(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                            float mx, float my) {
        float footerH = Math.round(54f * s);
        float formH = h - footerH;
        formRect[0] = x; formRect[1] = y; formRect[2] = w; formRect[3] = formH;
        formScroll.update(host.frameDelta(), formH, formContentH, host.now());
        float scroll = formScroll.offset();
        float inner = w - Math.round(14f * s);
        float lineH = Math.round(14f * s), fieldH = Math.round(32f * s), sect = Math.round(22f * s);

        b.clipPush(x, y, w, formH, 0);
        host.beginHitClip(x, y, w, formH);
        float cy = y - scroll;
        float start = cy;

        // --- general ---
        float row = lineH + fieldH + Math.round(14f * s);
        boolean extraRow = needsFilter() || draft.getTriggerMode() == TriggerMode.TARGET_SHIELD;
        UiKit.card(b, host, x, cy, inner, Math.round(14f * s) + row * 2f + (extraRow ? row : 0f)
                + lineH + fieldH + Math.round(18f * s), s);
        float p = Math.round(16f * s);
        float fx = x + p, fw = inner - p * 2f;
        cy += Math.round(14f * s);
        UiKit.label(b, host, "Name", fx, cy, s);
        float toggleBlock = Math.round(100f * s);
        name.draw(b, host, fx, cy + lineH, fw - toggleBlock, fieldH, s);
        b.text(fx + fw - toggleBlock + Math.round(14f * s), GpuUiFontMetrics.topInBox(cy + lineH, fieldH, 10f * s), 10f * s, host.mutedColor(), "Enabled",
                UiDrawCommand.TextWeight.SEMIBOLD);
        UiKit.toggle(b, host, fx + fw - Math.round(34f * s), cy + lineH + (fieldH - Math.round(18f * s)) * 0.5f, s,
                draft.isEnabled(), () -> draft.setEnabled(!draft.isEnabled()));
        cy += lineH + fieldH + Math.round(14f * s);

        UiKit.label(b, host, "Trigger", fx, cy, s);
        float third = Math.round((fw - Math.round(16f * s)) / 3f);
        float by = cy + lineH;
        UiKit.button(b, host, draft.getTriggerMode().getLabel(), fx, by, third, fieldH, s, Style.SECONDARY, true, mx, my,
                () -> draft.setTriggerMode(draft.getTriggerMode().next()));
        boolean keybind = draft.getTriggerMode() == TriggerMode.KEYBIND;
        UiKit.button(b, host, "Activation: " + draft.getActivationMode().getLabel(), fx + third + Math.round(8f * s), by,
                third, fieldH, s, Style.SECONDARY, keybind, mx, my,
                () -> draft.setActivationMode(draft.getActivationMode().next()));
        String keyLabel = host.capturingKey() ? "Press a key..." : "Key: " + MacroManager.keyDisplayName(draft.getKeyCode());
        UiKit.button(b, host, keyLabel, fx + (third + Math.round(8f * s)) * 2f, by, third, fieldH, s,
                host.capturingKey() ? Style.PRIMARY : Style.SECONDARY, keybind, mx, my,
                () -> host.captureKey(code -> draft.setKeyCode(code)));
        cy += lineH + fieldH + Math.round(14f * s);

        if (needsFilter()) {
            boolean required = draft.getTriggerMode() == TriggerMode.LOOK_AT_BLOCK;
            UiKit.label(b, host, required ? "Block filter (required)" : "Block filter (optional)", fx, cy, s);
            blockFilter.draw(b, host, fx, cy + lineH, fw, fieldH, s);
            cy += lineH + fieldH + Math.round(14f * s);
        } else if (draft.getTriggerMode() == TriggerMode.TARGET_SHIELD) {
            UiKit.label(b, host, "Shield hold (ms)", fx, cy, s);
            shieldHold.draw(b, host, fx, cy + lineH, Math.round(fw * 0.5f - 8f * s), fieldH, s);
            cy += lineH + fieldH + Math.round(14f * s);
        }

        UiKit.label(b, host, "Cooldown (ms)", fx, cy, s);
        float half = Math.round(fw * 0.5f - 8f * s);
        cooldown.draw(b, host, fx, cy + lineH, half, fieldH, s);
        b.text(fx + half + Math.round(24f * s), GpuUiFontMetrics.topInBox(cy + lineH, fieldH, 10f * s), 10f * s, host.mutedColor(),
                "Restore original slot", UiDrawCommand.TextWeight.SEMIBOLD);
        UiKit.toggle(b, host, fx + fw - Math.round(34f * s), cy + lineH + (fieldH - Math.round(18f * s)) * 0.5f, s,
                draft.isRestoreOriginalSlot(), () -> draft.setRestoreOriginalSlot(!draft.isRestoreOriginalSlot()));
        cy += lineH + fieldH + Math.round(18f * s);

        // --- steps ---
        cy += sect;
        UiKit.label(b, host, "Steps (" + draft.getStepCount() + " / " + MacroDefinition.MAX_STEPS + ")", x + 2f, cy - Math.round(4f * s), s);
        cy += Math.round(14f * s);
        float chip = Math.round(30f * s), cx = x;
        for (int i = 0; i < draft.getStepCount(); i++) {
            final int index = i;
            boolean active = i == stepIndex;
            UiKit.button(b, host, Integer.toString(i + 1), cx, cy, chip, chip, s, active ? Style.PRIMARY : Style.SECONDARY,
                    true, mx, my, () -> { stepIndex = index; syncFields(); });
            cx += chip + Math.round(6f * s);
            if (cx + chip > x + inner - UiKit.buttonWidth("Remove", s) * 2f && i < draft.getStepCount() - 1) {
                cx = x;
                cy += chip + Math.round(6f * s);
            }
        }
        float addW = UiKit.buttonWidth("Add", s), remW = UiKit.buttonWidth("Remove", s);
        UiKit.button(b, host, "Add", cx + Math.round(6f * s), cy, addW, chip, s, Style.GHOST,
                draft.getStepCount() < MacroDefinition.MAX_STEPS, mx, my, () -> {
                    MacroDefinition.Step added = draft.addStepAfter(stepIndex);
                    if (added != null) { stepIndex++; syncFields(); }
                });
        UiKit.button(b, host, "Remove", cx + Math.round(12f * s) + addW, cy, remW, chip, s, Style.DANGER,
                draft.getStepCount() > 1, mx, my, () -> {
                    if (draft.removeStep(stepIndex)) {
                        stepIndex = Math.min(stepIndex, draft.getStepCount() - 1);
                        syncFields();
                    }
                });
        cy += chip + Math.round(12f * s);

        MacroDefinition.Step st = step();
        if (st != null) {
            boolean pressKey = st.getActionMode() == ActionMode.PRESS_KEY;
            float cardH = Math.round(14f * s) + row * (pressKey ? 3f : 2f) + lineH + fieldH + Math.round(18f * s);
            UiKit.card(b, host, x, cy, inner, cardH, s);
            float sy = cy + Math.round(14f * s);
            UiKit.label(b, host, "Target", fx, sy, s);
            float segW = Math.round(220f * s);
            UiKit.segmented(b, host, new String[]{"Item search", "Slot"}, st.getTargetMode() == TargetMode.ITEM ? 0 : 1,
                    fx, sy + lineH, segW, fieldH, s, mx, my, i -> st.setTargetMode(i == 0 ? TargetMode.ITEM : TargetMode.SLOT));
            float valX = fx + segW + Math.round(12f * s);
            float valW = fx + fw - valX;
            if (pressKey) {
                b.text(valX, GpuUiFontMetrics.topInBox(sy + lineH, fieldH, 10f * s), 10f * s, host.muted2Color(), "Not used when pressing a key");
            } else if (st.getTargetMode() == TargetMode.ITEM) {
                itemId.draw(b, host, valX, sy + lineH, valW, fieldH, s);
            } else {
                slot.draw(b, host, valX, sy + lineH, valW, fieldH, s);
            }
            sy += lineH + fieldH + Math.round(14f * s);

            UiKit.label(b, host, "Action", fx, sy, s);
            ActionMode[] modes = ActionMode.values();
            String[] labels = new String[modes.length];
            for (int i = 0; i < modes.length; i++) labels[i] = modes[i].getLabel();
            UiKit.segmented(b, host, labels, st.getActionMode().ordinal(), fx, sy + lineH, fw, fieldH, s, mx, my,
                    i -> st.setActionMode(modes[i]));
            sy += lineH + fieldH + Math.round(14f * s);

            if (pressKey) {
                UiKit.label(b, host, "Key to press", fx, sy, s);
                String kl = host.capturingKey() ? "Press a key..." : MacroManager.keyDisplayName(st.getKeyCode());
                UiKit.button(b, host, kl, fx, sy + lineH, Math.round(160f * s), fieldH, s,
                        host.capturingKey() ? Style.PRIMARY : Style.SECONDARY, true, mx, my,
                        () -> host.captureKey(code -> st.setKeyCode(code)));
                sy += lineH + fieldH + Math.round(14f * s);
            }

            UiKit.label(b, host, "Delay before (ms)", fx, sy, s);
            UiKit.label(b, host, "Delay after (ms)", fx + half + Math.round(16f * s), sy, s);
            preDelay.draw(b, host, fx, sy + lineH, half, fieldH, s);
            postDelay.draw(b, host, fx + half + Math.round(16f * s), sy + lineH, half, fieldH, s);
            cy += cardH + Math.round(18f * s);
        }
        formContentH = cy - start;
        host.endHitClip();
        b.clipPop();
        formScroll.paintBar(b, x + w - Math.round(4f * s), y, formH, s, host.textColor(), host.now());

        drawFooter(b, host, x, y + formH, w, footerH, s, mx, my);
    }

    private boolean needsFilter() {
        TriggerMode t = draft.getTriggerMode();
        return t == TriggerMode.USE_KEY_HOLD || t == TriggerMode.BLOCK_INTERACT || t == TriggerMode.LOOK_AT_BLOCK;
    }

    private void drawFooter(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                            float mx, float my) {
        UiKit.divider(b, host, x, y + Math.round(6f * s), w);
        float by = y + Math.round(14f * s), bh = Math.round(32f * s);
        float saveW = UiKit.buttonWidth("Save macro", s);
        UiKit.button(b, host, "Save macro", x, by, saveW, bh, s, Style.PRIMARY, true, mx, my, this::save);
        boolean saved = macros.stream().anyMatch(m -> m.getId().equals(draft.getId()));
        float cursor = x + saveW + Math.round(8f * s);
        if (saved) {
            boolean armed = deleteArmed && host.now() - deleteArmedAt < 3_500_000_000L;
            float delW = UiKit.buttonWidth("Confirm delete", s);
            UiKit.button(b, host, armed ? "Confirm delete" : "Delete", cursor, by, delW, bh, s, Style.DANGER, true, mx, my, () -> {
                if (armed) delete();
                else { deleteArmed = true; deleteArmedAt = host.now(); }
            });
            cursor += delW + Math.round(14f * s);
        } else {
            cursor += Math.round(6f * s);
        }
        if (!status.isEmpty() && host.now() - statusAt < 6_000_000_000L) {
            b.text(cursor, GpuUiFontMetrics.topInBox(by, bh, 10f * s), 10f * s, statusIsError ? UiKit.DANGER : host.accent(),
                    UiKit.fit(status, x + w - cursor, 10f * s));
        }
    }

    // ---- actions --------------------------------------------------------------------------------

    private void delete() {
        deleteArmed = false;
        if (MacroManager.getInstance().deleteMacro(draft.getId())) {
            say("Macro deleted.", false);
            refresh();
            draft = null;
        } else {
            say("Could not delete the macro.", true);
        }
    }

    private void save() {
        String problem = validate();
        if (problem != null) {
            say(problem, true);
            return;
        }
        MacroDefinition toSave = draft.copy();
        toSave.sanitize();
        if (!MacroManager.getInstance().saveMacro(toSave)) {
            say("Could not save. Check the macro limit (128) and the values.", true);
            return;
        }
        refresh();
        draft = toSave;
        syncFields();
        say("Macro saved.", false);
    }

    /** The same checks the old Macro Creator ran before saving. */
    private String validate() {
        if (draft.getName().isBlank()) return "Give the macro a name.";
        if (draft.getCooldownMs() < 0 || draft.getCooldownMs() > 10000) return "Cooldown must be 0 to 10000 ms.";
        if (draft.getTriggerMode() == TriggerMode.LOOK_AT_BLOCK && draft.getBlockFilterId().isBlank()) {
            return "Look At Block needs a block filter.";
        }
        if (draft.getTriggerMode() == TriggerMode.TARGET_SHIELD
                && (draft.getShieldHoldMs() < 0 || draft.getShieldHoldMs() > 1500)) {
            return "Shield hold must be 0 to 1500 ms.";
        }
        List<MacroDefinition.Step> steps = draft.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            MacroDefinition.Step st = steps.get(i);
            String where = "Step " + (i + 1) + ": ";
            if (st.getActionMode() == ActionMode.PRESS_KEY) {
                if (st.getKeyCode() == -1 || st.getKeyCode() == 0) return where + "pick the key to press.";
            } else if (st.getTargetMode() == TargetMode.ITEM) {
                if (st.getItemId().isBlank()) return where + "enter an item id.";
            } else if (st.getSlot() < 1 || st.getSlot() > 9) {
                return where + "slot must be 1 to 9.";
            }
            if (st.getPreDelayMs() < 0 || st.getPreDelayMs() > 5000 || st.getPostDelayMs() < 0 || st.getPostDelayMs() > 5000) {
                return where + "delays must be 0 to 5000 ms.";
            }
        }
        return null;
    }
}
