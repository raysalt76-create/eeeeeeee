package interstellarTikiTiks.apexx.client.module.implementation.studio.macro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MacroDefinition {
    public static final int MAX_STEPS = 16;

    public enum TargetMode {
        ITEM("Item Search"),
        SLOT("Slot");

        private final String label;

        TargetMode(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public TargetMode next() {
            return this == ITEM ? SLOT : ITEM;
        }

        public static TargetMode fromString(String value) {
            if (value == null) {
                return ITEM;
            }
            for (TargetMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return ITEM;
        }
    }

    public enum ActionMode {
        USE_ITEM("Use Item"),
        ATTACK("Attack"),
        USE_AND_ATTACK("Use + Attack"),
        PRESS_KEY("Press Key");

        private final String label;

        ActionMode(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public ActionMode next() {
            return switch (this) {
                case USE_ITEM -> ATTACK;
                case ATTACK -> USE_AND_ATTACK;
                case USE_AND_ATTACK -> PRESS_KEY;
                case PRESS_KEY -> USE_ITEM;
            };
        }

        public static ActionMode fromString(String value) {
            if (value == null) {
                return USE_ITEM;
            }
            for (ActionMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return USE_ITEM;
        }
    }

    public enum ActivationMode {
        PRESS("Press"),
        HOLD("Hold");

        private final String label;

        ActivationMode(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public ActivationMode next() {
            return this == PRESS ? HOLD : PRESS;
        }

        public static ActivationMode fromString(String value) {
            if (value == null) {
                return PRESS;
            }
            for (ActivationMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return PRESS;
        }
    }

    public enum TriggerMode {
        KEYBIND("Keybind"),
        USE_KEY_HOLD("Use Key Hold"),
        BLOCK_INTERACT("Block Place/Use"),
        LOOK_AT_BLOCK("Look At Block"),
        TARGET_SHIELD("Target Shield");

        private final String label;

        TriggerMode(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public TriggerMode next() {
            return switch (this) {
                case KEYBIND -> USE_KEY_HOLD;
                case USE_KEY_HOLD -> BLOCK_INTERACT;
                case BLOCK_INTERACT -> LOOK_AT_BLOCK;
                case LOOK_AT_BLOCK -> TARGET_SHIELD;
                case TARGET_SHIELD -> KEYBIND;
            };
        }

        public static TriggerMode fromString(String value) {
            if (value == null) {
                return KEYBIND;
            }
            for (TriggerMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return KEYBIND;
        }
    }

    public static final class Step {
        private TargetMode targetMode = TargetMode.ITEM;
        private String itemId = "minecraft:ender_pearl";
        private int slot = 1;
        private ActionMode actionMode = ActionMode.USE_ITEM;
        private int keyCode = -1;
        private int preDelayMs = 20;
        private int postDelayMs = 20;

        public Step() {
        }

        public Step(Step other) {
            if (other == null) {
                return;
            }
            this.targetMode = other.targetMode;
            this.itemId = other.itemId;
            this.slot = other.slot;
            this.actionMode = other.actionMode;
            this.keyCode = other.keyCode;
            this.preDelayMs = other.preDelayMs;
            this.postDelayMs = other.postDelayMs;
        }

        public Step copy() {
            return new Step(this);
        }

        public void sanitize() {
            if (targetMode == null) {
                targetMode = TargetMode.ITEM;
            }
            if (actionMode == null) {
                actionMode = ActionMode.USE_ITEM;
            }
            itemId = sanitizeIdentifier(itemId);
            slot = clamp(slot, 1, 9);
            if (keyCode == 0 || keyCode > GLFW.GLFW_KEY_LAST || keyCode < -(100 + GLFW.GLFW_MOUSE_BUTTON_LAST) || (keyCode < -1 && keyCode > -100)) {
                keyCode = -1;
            }
            preDelayMs = clamp(preDelayMs, 0, 5000);
            postDelayMs = clamp(postDelayMs, 0, 5000);
        }

        public JsonObject toJson() {
            sanitize();
            JsonObject json = new JsonObject();
            json.addProperty("targetMode", targetMode.name());
            json.addProperty("itemId", itemId);
            json.addProperty("slot", slot);
            json.addProperty("actionMode", actionMode.name());
            json.addProperty("stepKeyCode", keyCode);
            json.addProperty("preDelayMs", preDelayMs);
            json.addProperty("postDelayMs", postDelayMs);
            return json;
        }

        public static Step fromJson(JsonObject json) {
            Step step = new Step();
            if (json == null) {
                step.sanitize();
                return step;
            }
            if (json.has("targetMode")) {
                step.targetMode = TargetMode.fromString(json.get("targetMode").getAsString());
            }
            if (json.has("itemId")) {
                step.itemId = json.get("itemId").getAsString();
            }
            if (json.has("slot")) {
                step.slot = json.get("slot").getAsInt();
            }
            if (json.has("actionMode")) {
                step.actionMode = ActionMode.fromString(json.get("actionMode").getAsString());
            }
            if (json.has("stepKeyCode")) {
                step.keyCode = json.get("stepKeyCode").getAsInt();
            }
            if (json.has("preDelayMs")) {
                step.preDelayMs = json.get("preDelayMs").getAsInt();
            } else if (json.has("switchDelayMs")) {
                step.preDelayMs = json.get("switchDelayMs").getAsInt();
            }
            if (json.has("postDelayMs")) {
                step.postDelayMs = json.get("postDelayMs").getAsInt();
            } else if (json.has("restoreDelayMs")) {
                step.postDelayMs = json.get("restoreDelayMs").getAsInt();
            }
            step.sanitize();
            return step;
        }

        public TargetMode getTargetMode() {
            return targetMode;
        }

        public void setTargetMode(TargetMode targetMode) {
            this.targetMode = targetMode;
        }

        public String getItemId() {
            return itemId;
        }

        public void setItemId(String itemId) {
            this.itemId = itemId;
        }

        public int getSlot() {
            return slot;
        }

        public void setSlot(int slot) {
            this.slot = slot;
        }

        public ActionMode getActionMode() {
            return actionMode;
        }

        public void setActionMode(ActionMode actionMode) {
            this.actionMode = actionMode;
        }

        public int getKeyCode() {
            return keyCode;
        }

        public void setKeyCode(int keyCode) {
            this.keyCode = keyCode;
        }

        public int getPreDelayMs() {
            return preDelayMs;
        }

        public void setPreDelayMs(int preDelayMs) {
            this.preDelayMs = preDelayMs;
        }

        public int getPostDelayMs() {
            return postDelayMs;
        }

        public void setPostDelayMs(int postDelayMs) {
            this.postDelayMs = postDelayMs;
        }
    }

    private String id = UUID.randomUUID().toString();
    private String name = "Macro";
    private boolean enabled = true;
    private int keyCode = -1;
    private TriggerMode triggerMode = TriggerMode.KEYBIND;
    private ActivationMode activationMode = ActivationMode.PRESS;
    private String blockFilterId = "";
    private int shieldHoldMs = 80;
    private int cooldownMs = 150;
    private boolean restoreOriginalSlot = true;
    private final List<Step> steps = new ArrayList<>();

    public MacroDefinition() {
        steps.add(new Step());
    }

    public MacroDefinition(MacroDefinition other) {
        if (other == null) {
            return;
        }
        this.id = other.id;
        this.name = other.name;
        this.enabled = other.enabled;
        this.keyCode = other.keyCode;
        this.triggerMode = other.triggerMode;
        this.activationMode = other.activationMode;
        this.blockFilterId = other.blockFilterId;
        this.shieldHoldMs = other.shieldHoldMs;
        this.cooldownMs = other.cooldownMs;
        this.restoreOriginalSlot = other.restoreOriginalSlot;
        for (Step step : other.steps) {
            this.steps.add(step.copy());
        }
        if (this.steps.isEmpty()) {
            this.steps.add(new Step());
        }
    }

    public MacroDefinition copy() {
        return new MacroDefinition(this);
    }

    public void sanitize() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        } else if (id.length() > 64) {
            id = id.substring(0, 64);
        }

        name = sanitizeName(name);
        if (name.isBlank()) {
            name = "Macro";
        }

        if (activationMode == null) {
            activationMode = ActivationMode.PRESS;
        }
        if (triggerMode == null) {
            triggerMode = TriggerMode.KEYBIND;
        }
        blockFilterId = sanitizeIdentifierAllowEmpty(blockFilterId);
        shieldHoldMs = clamp(shieldHoldMs, 0, 1500);
        cooldownMs = clamp(cooldownMs, 0, 10000);
        if (keyCode == 0 || keyCode > GLFW.GLFW_KEY_LAST || keyCode < -(100 + GLFW.GLFW_MOUSE_BUTTON_LAST) || (keyCode < -1 && keyCode > -100)) {
            keyCode = -1;
        }

        if (steps.isEmpty()) {
            steps.add(new Step());
        }
        if (steps.size() > MAX_STEPS) {
            while (steps.size() > MAX_STEPS) {
                steps.remove(steps.size() - 1);
            }
        }
        for (Step step : steps) {
            if (step != null) {
                step.sanitize();
            }
        }
        steps.removeIf(step -> step == null);
        if (steps.isEmpty()) {
            steps.add(new Step());
        }
    }

    private static String sanitizeName(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 32 || c == 127) {
                continue;
            }
            if (c == '"' || c == '\'' || c == '\\') {
                continue;
            }
            builder.append(c);
        }
        String sanitized = builder.toString().trim();
        if (sanitized.length() > 32) {
            sanitized = sanitized.substring(0, 32);
        }
        return sanitized;
    }

    private static String sanitizeIdentifier(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "minecraft:ender_pearl";
        }
        if (normalized.length() > 80) {
            normalized = normalized.substring(0, 80);
        }
        return normalized;
    }

    private static String sanitizeIdentifierAllowEmpty(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.length() > 80) {
            normalized = normalized.substring(0, 80);
        }
        return normalized;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public JsonObject toJson() {
        sanitize();
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("name", name);
        json.addProperty("enabled", enabled);
        json.addProperty("keyCode", keyCode);
        json.addProperty("triggerMode", triggerMode.name());
        json.addProperty("activationMode", activationMode.name());
        json.addProperty("blockFilterId", blockFilterId);
        json.addProperty("shieldHoldMs", shieldHoldMs);
        json.addProperty("cooldownMs", cooldownMs);
        json.addProperty("restoreOriginalSlot", restoreOriginalSlot);
        JsonArray stepsArray = new JsonArray();
        for (Step step : steps) {
            stepsArray.add(step.toJson());
        }
        json.add("steps", stepsArray);
        return json;
    }

    public static MacroDefinition fromJson(JsonObject json) {
        MacroDefinition macro = new MacroDefinition();
        if (json == null) {
            macro.sanitize();
            return macro;
        }

        if (json.has("id")) {
            macro.id = json.get("id").getAsString();
        }
        if (json.has("name")) {
            macro.name = json.get("name").getAsString();
        }
        if (json.has("enabled")) {
            macro.enabled = json.get("enabled").getAsBoolean();
        }
        if (json.has("keyCode")) {
            macro.keyCode = json.get("keyCode").getAsInt();
        }
        if (json.has("triggerMode")) {
            macro.triggerMode = TriggerMode.fromString(json.get("triggerMode").getAsString());
        }
        if (json.has("activationMode")) {
            macro.activationMode = ActivationMode.fromString(json.get("activationMode").getAsString());
        }
        if (json.has("blockFilterId")) {
            macro.blockFilterId = json.get("blockFilterId").getAsString();
        }
        if (json.has("shieldHoldMs")) {
            macro.shieldHoldMs = json.get("shieldHoldMs").getAsInt();
        }
        if (json.has("cooldownMs")) {
            macro.cooldownMs = json.get("cooldownMs").getAsInt();
        }
        if (json.has("restoreOriginalSlot")) {
            macro.restoreOriginalSlot = json.get("restoreOriginalSlot").getAsBoolean();
        }
        macro.steps.clear();
        if (json.has("steps") && json.get("steps").isJsonArray()) {
            JsonArray stepArray = json.getAsJsonArray("steps");
            for (JsonElement element : stepArray) {
                if (!element.isJsonObject()) {
                    continue;
                }
                macro.steps.add(Step.fromJson(element.getAsJsonObject()));
                if (macro.steps.size() >= MAX_STEPS) {
                    break;
                }
            }
        } else {
            Step legacy = new Step();
            if (json.has("targetMode")) {
                legacy.setTargetMode(TargetMode.fromString(json.get("targetMode").getAsString()));
            }
            if (json.has("itemId")) {
                legacy.setItemId(json.get("itemId").getAsString());
            }
            if (json.has("slot")) {
                legacy.setSlot(json.get("slot").getAsInt());
            }
            if (json.has("actionMode")) {
                legacy.setActionMode(ActionMode.fromString(json.get("actionMode").getAsString()));
            }
            if (json.has("switchDelayMs")) {
                legacy.setPreDelayMs(json.get("switchDelayMs").getAsInt());
            }
            if (json.has("restoreDelayMs")) {
                legacy.setPostDelayMs(json.get("restoreDelayMs").getAsInt());
            }
            macro.steps.add(legacy);
        }
        macro.sanitize();
        return macro;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getKeyCode() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = keyCode;
    }

    public TriggerMode getTriggerMode() {
        return triggerMode;
    }

    public void setTriggerMode(TriggerMode triggerMode) {
        this.triggerMode = triggerMode;
    }

    public ActivationMode getActivationMode() {
        return activationMode;
    }

    public void setActivationMode(ActivationMode activationMode) {
        this.activationMode = activationMode;
    }

    public String getBlockFilterId() {
        return blockFilterId;
    }

    public void setBlockFilterId(String blockFilterId) {
        this.blockFilterId = blockFilterId;
    }

    public int getShieldHoldMs() {
        return shieldHoldMs;
    }

    public void setShieldHoldMs(int shieldHoldMs) {
        this.shieldHoldMs = shieldHoldMs;
    }

    public int getCooldownMs() {
        return cooldownMs;
    }

    public void setCooldownMs(int cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    public boolean isRestoreOriginalSlot() {
        return restoreOriginalSlot;
    }

    public void setRestoreOriginalSlot(boolean restoreOriginalSlot) {
        this.restoreOriginalSlot = restoreOriginalSlot;
    }

    public List<Step> getSteps() {
        return steps;
    }

    public int getStepCount() {
        return steps.size();
    }

    public Step getStep(int index) {
        if (index < 0 || index >= steps.size()) {
            return null;
        }
        return steps.get(index);
    }

    public Step addStepAfter(int index) {
        Step step = new Step();
        int insertIndex = Math.max(0, Math.min(index + 1, steps.size()));
        if (steps.size() >= MAX_STEPS) {
            return null;
        }
        steps.add(insertIndex, step);
        return step;
    }

    public boolean removeStep(int index) {
        if (steps.size() <= 1 || index < 0 || index >= steps.size()) {
            return false;
        }
        steps.remove(index);
        return true;
    }

    public boolean moveStepUp(int index) {
        if (index <= 0 || index >= steps.size()) {
            return false;
        }
        Step step = steps.remove(index);
        steps.add(index - 1, step);
        return true;
    }

    public boolean moveStepDown(int index) {
        if (index < 0 || index >= steps.size() - 1) {
            return false;
        }
        Step step = steps.remove(index);
        steps.add(index + 1, step);
        return true;
    }

    public Step duplicateStep(int index) {
        if (steps.size() >= MAX_STEPS || index < 0 || index >= steps.size()) {
            return null;
        }
        Step copy = steps.get(index).copy();
        steps.add(index + 1, copy);
        return copy;
    }

    public Step insertStep(int index, Step step) {
        if (steps.size() >= MAX_STEPS || step == null) {
            return null;
        }
        int insertIndex = Math.max(0, Math.min(index, steps.size()));
        steps.add(insertIndex, step);
        return step;
    }

    public void copyFrom(MacroDefinition other) {
        if (other == null) {
            return;
        }
        this.name = other.name;
        this.enabled = other.enabled;
        this.keyCode = other.keyCode;
        this.triggerMode = other.triggerMode;
        this.activationMode = other.activationMode;
        this.blockFilterId = other.blockFilterId;
        this.shieldHoldMs = other.shieldHoldMs;
        this.cooldownMs = other.cooldownMs;
        this.restoreOriginalSlot = other.restoreOriginalSlot;
        this.steps.clear();
        for (Step s : other.steps) {
            this.steps.add(s.copy());
        }
        if (this.steps.isEmpty()) {
            this.steps.add(new Step());
        }
    }

    public Step createDefaultStep() {
        return new Step();
    }
}
