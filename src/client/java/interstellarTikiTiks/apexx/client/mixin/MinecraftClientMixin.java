package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.ApexxAuth;
import interstellarTikiTiks.apexx.client.config.ConfigKeybindManager;
import interstellarTikiTiks.apexx.client.config.ConfigManager;
import interstellarTikiTiks.apexx.client.config.hub.ClientSyncManager;
import interstellarTikiTiks.apexx.client.config.hub.ConfigHubManager;
import interstellarTikiTiks.apexx.client.config.hub.WebOnlyModeManager;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.hooks.AttackExecutionAware;
import interstellarTikiTiks.apexx.client.module.hooks.AttackHandleAware;
import interstellarTikiTiks.apexx.client.module.hooks.MaceSmashArmProvider;
import interstellarTikiTiks.apexx.client.module.hooks.NotificationSink;
import interstellarTikiTiks.apexx.client.module.hooks.PreventStateProvider;
import interstellarTikiTiks.apexx.client.util.InputPulseManager;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.Renderer;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.gui.gpuui.GpuClickGuiScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Unique;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.HashMap;
import java.util.Map;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    private void setEncryptedScreen(MinecraftClient client, String className) {
        // Obsolete: Replaced with direct instantiation
    }

    private static final Logger LOGGER = org.slf4j.helpers.NOPLogger.NOP_LOGGER;

    private static final ConfigHubManager LINK_KEY_REGISTRAR = new ConfigHubManager();
    private static String lastRegisteredLinkKey = null;
    private static long lastLinkKeyRegistrationAtMs = 0L;
    private static final long LINK_KEY_REGISTRATION_INTERVAL_MS = 60_000L;

    // Keybind edge state (was a String-keyed map rebuilt with name + ":" + key concatenation for every bind, every tick).
    @Unique
    private final interstellarTikiTiks.apexx.client.util.KeybindEdgeTracker apexx$bindEdges =
            new interstellarTikiTiks.apexx.client.util.KeybindEdgeTracker();
    // Edge-detect state for the GUI-open bind. Kept OUT of wasPressedMap (which is
    // cleared every tick while a screen is open) so holding shift through an ESC-close can't re-open.
    private boolean guiOpenKeyWasDown;

    /** Step macros run in the Premium studio (payload); without it (free payload) there is nothing to run. */
    @Unique
    private static void apexx$tickStudio(MinecraftClient client, boolean allowInputTriggers) {
        interstellarTikiTiks.apexx.client.module.hooks.StudioProvider studio =
                ModuleAccess.getHook(interstellarTikiTiks.apexx.client.module.hooks.StudioProvider.class);
        if (studio == null) return;
        try {
            studio.tickMacros(client, allowInputTriggers);
        } catch (Throwable ignored) {
            // a macro fault must never break the client tick
        }
    }

    /**
     * GUI bind (RIGHT SHIFT by default) edge: opens the menu over the game, or over the title / multiplayer /
     * options / loading screens with that screen as its parent ({@code ClickGuiRouter.canOpenOver}, never while a
     * text field has focus), and closes an open menu. Edge-detected via guiOpenKeyWasDown, updated EVERY tick
     * (unlike wasPressedMap, which is cleared while a screen is open) so holding the key through a close cannot
     * re-open. Client thread, tick head; needs no world or player.
     */
    @Unique
    private void apexx$handleGuiKey(MinecraftClient client) {
        if (client.getWindow() == null) return;
        int guiKey = GLFW.GLFW_KEY_RIGHT_SHIFT;
        Module clickGuiMod = ModuleAccess.getModule("ClickGUI");
        if (clickGuiMod != null) {
            guiKey = clickGuiMod.getKeyCode();
            if (guiKey == -1 || guiKey == 0) {
                guiKey = GLFW.GLFW_KEY_RIGHT_SHIFT;
            }
        }
        boolean guiKeyDown = isInputActive(client.getWindow(), guiKey);
        // A key the menu's keybind capture just consumed (e.g. the new GUI key itself) must not also
        // close the menu through this edge.
        boolean captureOwnsKey = interstellarTikiTiks.apexx.client.gui.ClickGuiRouter.menuOwnsKey(client.currentScreen, guiKey);
        if (guiKeyDown && !guiOpenKeyWasDown && !captureOwnsKey) {
            Screen current = client.currentScreen;
            if (current == null) {
                // The menu opens only while the service-verified grant is live.
                if (client.player != null && interstellarTikiTiks.apexx.client.ApexxAuth.isAuthorized()) {
                    client.setScreen(interstellarTikiTiks.apexx.client.gui.ClickGuiRouter.create());
                }
            } else if (interstellarTikiTiks.apexx.client.gui.ClickGuiRouter.isMenu(current)) {
                // The open key toggles the menu closed as well.
                // The Screen also handles Escape, but this tick edge remains a focus-safe close path.
                current.close();
            } else if (interstellarTikiTiks.apexx.client.ApexxAuth.isAuthorized()
                    && interstellarTikiTiks.apexx.client.gui.ClickGuiRouter.canOpenOver(current)) {
                client.setScreen(interstellarTikiTiks.apexx.client.gui.ClickGuiRouter.create(current));
            }
        }
        guiOpenKeyWasDown = guiKeyDown;
    }

    // Edge state for the bulletproof ESC-close poll (see the tick handler).
    private boolean escCloseWasDown;
    // Attack hooks are allowed to finish an action through vanilla doAttack(). Do not dispatch
    // the same hooks again for that nested call or one module can recurse until the client stack
    // overflows. The nested call itself is left untouched and continues through vanilla normally.
    @Unique
    private boolean apexx$dispatchingAttackHooks;

    @Shadow
    @Nullable
    public ClientPlayerEntity player;

    @Shadow
    public Screen currentScreen;

    @Shadow
    @Nullable
    public net.minecraft.util.hit.HitResult crosshairTarget;

    @Shadow
    private int attackCooldown;

    @Shadow
    public abstract net.minecraft.client.util.Window getWindow();

    @Unique
    private net.minecraft.util.hit.HitResult apexx$preAttackCrosshairTarget;
    @Unique
    private boolean apexx$reroutedAttack;
    @Unique
    private net.minecraft.util.hit.HitResult apexx$preItemUseCrosshairTarget;
    @Unique
    private boolean apexx$reroutedItemUse;

    @Inject(method = "render", at = @At("HEAD"))
    private void onRender(boolean tick, CallbackInfo ci) {
        if (Apexx.isTornDown()) return;
        SystemInputSimulator.processPendingReleases();
        interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer.pollAndExecute((MinecraftClient) (Object) this);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Mouse;tick()V"))
    private void onRenderBeforeMouse(boolean tick, CallbackInfo ci) {
        if (Apexx.isTornDown()) return;
        // Runs after renderTickCounter.beginRenderTick and the client tick loop, immediately before
        // Mouse.tick() (Mouse.updateMouse), so render-frame hooks and MouseDeltaShaper read current
        // tickProgress and post-tick entity positions.
        Apexx apexx = Apexx.getInstance();
        if (apexx != null && apexx.getModuleManager() != null) {
            apexx.getModuleManager().onRenderFrame();
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Mouse;tick()V", shift = At.Shift.AFTER))
    private void onRenderAfterMouse(boolean tick, CallbackInfo ci) {
        if (Apexx.isTornDown()) return;
        // Advance the shared aim engine immediately after Mouse.tick() and before gameRenderer.render,
        // so this frame's physical mouse look delta is folded in with zero frame lag.
        interstellarTikiTiks.apexx.client.util.RotationManager.update(
                ((MinecraftClient) (Object) this).getRenderTickCounter().getTickProgress(true));
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        if (Apexx.isTornDown())
            return;
        SystemInputSimulator.beginClientTick();
        interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer.onTickStart();
        // Window opens before any module work: held action packets and queued slot restores go out
        // here, ahead of this tick's movement packet, in vanilla order.
        interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.onTickHead();
        SystemInputSimulator.processPendingReleases();

        MinecraftClient client = (MinecraftClient) (Object) this;

        interstellarTikiTiks.apexx.client.auth.PayloadLoader.tickDeferredRetry();
        WebOnlyModeManager.onClientTick(client);
        ClientSyncManager.onClientTick(client);
        maybeRegisterLinkKeyOnTick();

        // ── Auth enforcement (fail-closed), per-tick backstop ─────────────────────
        // Whenever there is no live grant (never signed in, or a heartbeat/expiry
        // revoked it), force every module off. Level-triggered (every tick, not only on
        // the authorized→deauthorized edge) so a revoked/expired session can NEVER keep
        // running, even if an edge is missed. Costs nothing while authorized.
        boolean apexxAuthed = ApexxAuth.isAuthorized();
        try {
            Apexx.getInstance().getModuleManager().enforceAuth(apexxAuthed);
        } catch (Throwable ignored) {
            LOGGER.warn("Exception during module deactivation when unauthorized", ignored);
        }
        // Mid-session revocation (ban / un-pay / HWID moved / expiry) drops the grant, so the
        // backstop above turns every module off. There is no in-game sign-in: authorization is
        // decided by the Apexx service before launch, and a revoked session ends at the next one.

        // A loading screen the menu covers still needs its tick (it drives the connection / world load).
        interstellarTikiTiks.apexx.client.gui.ClickGuiRouter.tickCoveredScreen(client);

        if (client.player == null) {
            apexx$bindEdges.clear();
            apexx$tickStudio(client, false);
            // Title, multiplayer, options and loading screens: the GUI key still opens (and closes) the menu.
            apexx$handleGuiKey(client);
            return;
        }
        interstellarTikiTiks.apexx.client.util.combat.MeleeMechanics.onClientTick(client);
        Apexx.getInstance().getModuleManager().onTick();

        // While ANY screen (inventory, chat, GUI) is open, force the movement keybinds released so the
        // player can never walk/strafe/sprint/sneak/jump in a menu — regardless of which module (or a
        // held simulated key) left a keybind pressed. This injection is at tick HEAD, before the player's
        // tickMovement reads the keybinds, so it always wins. (Inventory move is not allowed.)
        if (client.currentScreen != null && client.options != null) {
            client.options.forwardKey.setPressed(false);
            client.options.backKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            client.options.sneakKey.setPressed(false);
            client.options.sprintKey.setPressed(false);
        }

        boolean allowKeybinds = client.currentScreen == null;
        apexx$tickStudio(client, allowKeybinds);
        Module clickGuiMod = ModuleAccess.getModule("ClickGUI");
        apexx$handleGuiKey(client);

        // The active Screen receives the normal Escape input; this edge close is a recovery path for focus changes.
        if (client.currentScreen instanceof GpuClickGuiScreen) {
            boolean escNow = isInputActive(client.getWindow(), GLFW.GLFW_KEY_ESCAPE);
            GpuClickGuiScreen escScreen = (GpuClickGuiScreen) client.currentScreen;
            boolean escCaptured = interstellarTikiTiks.apexx.client.gui.ClickGuiRouter.menuOwnsKey(escScreen, GLFW.GLFW_KEY_ESCAPE);
            if (escNow && !escCloseWasDown && !escCaptured) {
                ((GpuClickGuiScreen) client.currentScreen).close();
            }
            escCloseWasDown = escNow;
        } else {
            escCloseWasDown = false;
        }

        boolean allowModuleKeybinds = client.currentScreen == null;
        if (allowModuleKeybinds) {
            boolean configStateChanged = false;
            for (Module module : Apexx.getInstance().getModuleManager().getModules()) {
                // The ClickGUI key is the menu key (handled above), not a module toggle.
                if (module == clickGuiMod) continue;
                int key = module.getKeyCode();
                if (key == -1 || key == 0)
                    continue;

                boolean isPressed = isInputActive(client.getWindow(), key);
                int key2 = module.getKeyCode2();
                if (key2 != -1 && key2 != 0) {
                    isPressed = isPressed && isInputActive(client.getWindow(), key2);
                }
                boolean wasPressed = apexx$bindEdges.wasDown(module, key, key2);
                if (module.isKeyTriggered()) {
                    // The bind only triggers the action, and only while the module's toggle is on.
                    boolean fire = module.isOneShotRepeat() ? isPressed : (isPressed && !wasPressed);
                    if (fire && module.isEnabled()) {
                        module.trigger();
                    }
                } else if (module.isOneShot()) {
                    boolean fire = module.isOneShotRepeat()
                            ? (isPressed && !module.isEnabled())
                            : (isPressed && !wasPressed && !module.isEnabled());
                    if (fire) {
                        module.setEnabled(true);
                    }
                } else if (module.isHoldMode()) {
                    if (isPressed && !wasPressed) {
                        module.setEnabled(true);
                        configStateChanged = true;
                    } else if (!isPressed && wasPressed) {
                        module.setEnabled(false);
                        configStateChanged = true;
                    }
                } else {
                    if (isPressed && !wasPressed) {
                        module.toggle();
                        configStateChanged = true;
                    }
                }
                apexx$bindEdges.update(module, key, key2, isPressed);
            }

            // Setting-level keybinds: any bindable BooleanSetting that carries its own key
            // (e.g. TriggerBot's "Hit Select") toggles or holds independently of its module's
            // own keybind. Edge-detected through the same wasPressedMap so a held key doesn't
            // retrigger every tick.
            for (Module module : Apexx.getInstance().getModuleManager().getModules()) {
                for (interstellarTikiTiks.apexx.client.module.Setting setting : module.getSettings()) {
                    if (!(setting instanceof interstellarTikiTiks.apexx.client.module.BooleanSetting bs)) continue;
                    if (!bs.isBindable()) continue;
                    int skey = bs.getKeyCode();
                    if (skey == -1 || skey == 0) continue;
                    boolean sPressed = isInputActive(client.getWindow(), skey);
                    boolean sWas = apexx$bindEdges.wasDown(bs, skey, 0);
                    if (bs.isKeyHoldMode()) {
                        if (sPressed != sWas) {
                            bs.setEnabled(sPressed);
                            configStateChanged = true;
                        }
                    } else {
                        if (sPressed && !sWas) {
                            bs.toggle();
                            configStateChanged = true;
                        }
                    }
                    apexx$bindEdges.update(bs, skey, 0, sPressed);
                }
            }

            int friendKey = interstellarTikiTiks.apexx.client.friend.FriendKeybindManager.getInstance().getKeyCode();
            if (friendKey != -1 && friendKey != 0) {
                boolean friendKeyPressed = isInputActive(client.getWindow(), friendKey);
                Object friendBindOwner = interstellarTikiTiks.apexx.client.friend.FriendKeybindManager.getInstance();
                if (friendKeyPressed && !apexx$bindEdges.wasDown(friendBindOwner, friendKey, 0)
                        && client.currentScreen == null) {
                    interstellarTikiTiks.apexx.client.friend.FriendHelper.toggleTargetedPlayer();
                }
                apexx$bindEdges.update(friendBindOwner, friendKey, 0, friendKeyPressed);
            }

            int enemyKey = interstellarTikiTiks.apexx.client.enemy.EnemyKeybindManager.getInstance().getKeyCode();
            if (enemyKey != -1 && enemyKey != 0) {
                boolean enemyKeyPressed = isInputActive(client.getWindow(), enemyKey);
                Object enemyBindOwner = interstellarTikiTiks.apexx.client.enemy.EnemyKeybindManager.getInstance();
                if (enemyKeyPressed && !apexx$bindEdges.wasDown(enemyBindOwner, enemyKey, 0)
                        && client.currentScreen == null) {
                    interstellarTikiTiks.apexx.client.friend.FriendHelper.toggleTargetedEnemy();
                }
                apexx$bindEdges.update(enemyBindOwner, enemyKey, 0, enemyKeyPressed);
            }

            for (ConfigKeybindManager.ConfigKeybind ckb : ConfigKeybindManager.getInstance().getKeybinds()) {
                if (ckb.key1 == -1 || ckb.key1 == 0) continue;
                boolean ckbPressed = isInputActive(client.getWindow(), ckb.key1);
                if (ckb.key2 != -1 && ckb.key2 != 0) {
                    ckbPressed = ckbPressed && isInputActive(client.getWindow(), ckb.key2);
                }
                if (ckbPressed && !ckb.wasPressed) {
                    ConfigManager.getInstance().load(ckb.configName);
                    notifyConfigSwapped(ckb.configName);
                }
                ckb.wasPressed = ckbPressed;
            }

            if (configStateChanged) {
                ConfigManager configManager = ConfigManager.getInstance();
                // Keybind changes already run on the Minecraft thread; persist the exact resulting
                // state now instead of depending on a later debounce tick or graceful shutdown.
                configManager.save(configManager.getLastLoadedConfigName());
                interstellarTikiTiks.apexx.client.config.CloudConfigSync.saveIfChangedNow(
                        configManager.exportCurrentConfigJsonForCloud());
            }

        } else {
            // Continue edge tracking in menus, but never activate a module there.
            for (Module module : Apexx.getInstance().getModuleManager().getModules()) {
                int key = module.getKeyCode(), key2 = module.getKeyCode2();
                // Mouse binds are negative codes; "> 0" dropped them from edge tracking in menus.
                boolean pressed = key != -1 && key != 0 && isInputActive(client.getWindow(), key);
                if (key2 != -1 && key2 != 0) pressed &= isInputActive(client.getWindow(), key2);
                apexx$bindEdges.update(module, key, key2, pressed);
                if (module.isHoldMode() && module.isEnabled()) module.setEnabled(false);
                for (interstellarTikiTiks.apexx.client.module.Setting setting : module.getSettings()) {
                    if (!(setting instanceof interstellarTikiTiks.apexx.client.module.BooleanSetting bs)
                            || !bs.isBindable() || bs.getKeyCode() == -1 || bs.getKeyCode() == 0) continue;
                    boolean down = isInputActive(client.getWindow(), bs.getKeyCode());
                    apexx$bindEdges.update(bs, bs.getKeyCode(), 0, down);
                    if (bs.isKeyHoldMode()) bs.setEnabled(false);
                }
            }
        }
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;tickEntities()V", shift = At.Shift.BEFORE))
    private void onTickPost(CallbackInfo ci) {
        // Symmetric with onTick: halt on sign-out (unloaded) as well as self-destruct, so re-registered
        // modules can't get post-tick execution after the client is torn down.
        if (Apexx.isTornDown())
            return;
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (client.player == null)
            return;
        Apexx.getInstance().getModuleManager().onTickPost();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void apexx$endKeystrokesTick(CallbackInfo ci) {
        if (Apexx.isTornDown())
            return;
        SystemInputSimulator.endClientTick();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTickEnd(CallbackInfo ci) {
        if (Apexx.isTornDown())
            return;
        if (((MinecraftClient) (Object) this).player == null)
            return;
        Apexx.getInstance().getModuleManager().onTickEnd();
    }

    private static void maybeRegisterLinkKeyOnTick() {
        if (ModuleAccess.getModule("ClickGUI") == null) {
            return;
        }
        String key;
        try {
            key = ModuleAccess.invokeString(ModuleAccess.getModule("ClickGUI"), "getLicenseKey", null, new Class<?>[0]);
        } catch (RuntimeException failure) {
            return;
        }
        if (key == null) {
            key = "";
        }
        key = key.trim();
        if (key.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean keyChanged = !key.equals(lastRegisteredLinkKey);
        boolean intervalElapsed = now - lastLinkKeyRegistrationAtMs >= LINK_KEY_REGISTRATION_INTERVAL_MS;
        if (!keyChanged && !intervalElapsed) {
            return;
        }
        lastRegisteredLinkKey = key;
        lastLinkKeyRegistrationAtMs = now;
        final String keyToRegister = key;
        LINK_KEY_REGISTRAR.registerLinkKey(keyToRegister, () -> {}, err -> {});
    }

    /** Pop a notification when a bound config hotkey swaps the active config. */
    private static void notifyConfigSwapped(String configName) {
        try {
            if (Apexx.getInstance() == null || Apexx.getInstance().getModuleManager() == null) {
                return;
            }
            NotificationSink notif = Apexx.getInstance().getModuleManager().getHook(NotificationSink.class);
            if (notif != null) {
                String name = (configName == null || configName.isBlank()) ? "config" : configName;
                notif.show("ConfigBind", "Loaded \"" + name + "\"", "SUCCESS");
            }
        } catch (Throwable ignored) {
            LOGGER.warn("Failed to notify config swap", ignored);
        }
    }

    private boolean shouldHandleKeybindsInScreen(Screen screen) {
        return !(screen instanceof net.minecraft.client.gui.screen.ChatScreen)
                && !(screen instanceof Object);
    }

    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void onDoAttack(CallbackInfoReturnable<Boolean> cir) {
        if (Apexx.isTornDown())
            return;
        if (SystemInputSimulator.shouldSuppressVanillaAttack()) {
            cir.setReturnValue(false);
            return;
        }

        SystemInputSimulator.showModuleClick(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT, true);

        if (interstellarTikiTiks.apexx.client.util.RotationManager.isSilentRotationActive() && this.player != null) {
            this.apexx$preAttackCrosshairTarget = this.crosshairTarget;
            // An attack reaches only as far as vanilla's entity interaction range along the silent angles.
            net.minecraft.util.hit.HitResult silentTarget = interstellarTikiTiks.apexx.client.util.RotationManager
                    .getCrosshairTarget(interstellarTikiTiks.apexx.client.util.RotationManager.getFinalYaw(),
                            interstellarTikiTiks.apexx.client.util.RotationManager.getFinalPitch(),
                            interstellarTikiTiks.apexx.client.util.RotationManager
                                     .silentAttackReach(this.player.getEntityInteractionRange()));
            if (silentTarget != null) {
                this.crosshairTarget = silentTarget;
                this.apexx$reroutedAttack = true;
            }
        }

        if (this.apexx$dispatchingAttackHooks) {
            return;
        }

        this.apexx$dispatchingAttackHooks = true;
        try {
            try {
                InputPulseManager.recordAttackPulse();
            } catch (Throwable failure) {
                LOGGER.warn("Attack input pulse tracking failed", failure);
            }

            for (interstellarTikiTiks.apexx.client.module.hooks.AttackGatingProvider gating : ModuleAccess.getEnabledHooks(interstellarTikiTiks.apexx.client.module.hooks.AttackGatingProvider.class)) {
                if (Boolean.TRUE.equals(ModuleCallbackGuard.call(gating, "attack gate",
                        gating::shouldGateAttackInput, false))) {
                    cir.setReturnValue(false);
                    return;
                }
            }

            for (MaceSmashArmProvider maceArm : ModuleAccess.getEnabledHooks(MaceSmashArmProvider.class)) {
                if (Boolean.TRUE.equals(ModuleCallbackGuard.call(maceArm, "mace arm",
                        maceArm::isSmashArmed, false))) {
                    this.attackCooldown = 0;
                    break;
                }
            }

            for (AttackHandleAware attackHandleAware : ModuleAccess.getEnabledHooks(AttackHandleAware.class)) {
                if (Boolean.TRUE.equals(ModuleCallbackGuard.call(attackHandleAware, "attack handler",
                        attackHandleAware::handleAttack, false))) {
                    cir.setReturnValue(false);
                    return;
                }
            }

            // AutoLunge is a one-shot module that is normally disabled, so its LMB Lunge
            // cannot go through the enabled-hook loop above.
            for (String lungeName : new String[] { "InstantLunge", "AutoLunge" }) {
                interstellarTikiTiks.apexx.client.module.Module lunge = ModuleAccess.getModule(lungeName);
                if (lunge != null && ModuleCallbackGuard.call(lunge, "attack handler",
                        () -> ModuleAccess.invokeBoolean(lunge, "handleAttack", false, new Class<?>[0]), false)) {
                    cir.setReturnValue(false);
                    return;
                }
            }
        } finally {
            this.apexx$dispatchingAttackHooks = false;
        }
    }

    @Inject(method = "doAttack", at = @At("RETURN"))
    private void apexx$afterDoAttackReturn(CallbackInfoReturnable<Boolean> cir) {
        if (this.apexx$reroutedAttack) {
            this.crosshairTarget = this.apexx$preAttackCrosshairTarget;
            this.apexx$reroutedAttack = false;
        }
    }

    @Inject(method = "doAttack", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerInteractionManager;attackEntity(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/entity/Entity;)V",
            shift = At.Shift.AFTER))
    private void apexx$afterVanillaEntityAttack(CallbackInfoReturnable<Boolean> cir) {
        if (Apexx.isTornDown())
            return;
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (!(client.crosshairTarget instanceof net.minecraft.util.hit.EntityHitResult entityHit)) {
            return;
        }
        for (AttackExecutionAware hook : ModuleAccess.getEnabledHooks(AttackExecutionAware.class)) {
            ModuleCallbackGuard.run(hook, "post-attack hook",
                    () -> hook.onVanillaEntityAttack(entityHit.getEntity(), entityHit.getPos()));
        }
    }

    @Unique
    private static boolean apexx$isItemUseBlockedByModule() {
        for (interstellarTikiTiks.apexx.client.module.hooks.ItemUseBlockProvider hook
                : ModuleAccess.getEnabledHooks(interstellarTikiTiks.apexx.client.module.hooks.ItemUseBlockProvider.class)) {
            if (ModuleCallbackGuard.call(hook, "item use block", hook::shouldBlockItemUse, false)) {
                return true;
            }
        }
        return false;
    }

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void onDoItemUse(CallbackInfo ci) {
        if (Apexx.isTornDown())
            return;
        if (SystemInputSimulator.shouldSuppressVanillaUse() || apexx$isItemUseBlockedByModule()) {
            ci.cancel();
            return;
        }
        SystemInputSimulator.showModuleClick(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT, true);
        if (interstellarTikiTiks.apexx.client.util.RotationManager.isSilentRotationActive() && this.player != null) {
            this.apexx$preItemUseCrosshairTarget = this.crosshairTarget;
            net.minecraft.util.hit.HitResult silentTarget = interstellarTikiTiks.apexx.client.util.RotationManager.getSilentCrosshairTarget();
            if (silentTarget != null) {
                this.crosshairTarget = silentTarget;
                this.apexx$reroutedItemUse = true;
            }
        }
        InputPulseManager.recordUsePulse();
    }

    @Inject(method = "doItemUse", at = @At("RETURN"))
    private void apexx$afterDoItemUseReturn(CallbackInfo ci) {
        if (this.apexx$reroutedItemUse) {
            this.crosshairTarget = this.apexx$preItemUseCrosshairTarget;
            this.apexx$reroutedItemUse = false;
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void onClose(CallbackInfo ci) {
        Renderer.get().cleanup();
    }

    @Inject(method = "openGameMenu", at = @At("HEAD"), cancellable = true)
    private void onOpenGameMenu(boolean pauseOnly, CallbackInfo ci) {
        if (Apexx.isTornDown())
            return;

        if (this.currentScreen != null) {
            return;
        }

        PreventStateProvider preventStateProvider = ModuleAccess.getEnabledHook(PreventStateProvider.class);
        if (preventStateProvider != null && preventStateProvider.shouldPreventEscape()) {
            ci.cancel();
        }
    }

    @Inject(method = "onWindowFocusChanged", at = @At("HEAD"), cancellable = true)
    private void onWindowFocusChanged(boolean focused, CallbackInfo ci) {
        if (Apexx.isTornDown())
            return;

        PreventStateProvider preventStateProvider = ModuleAccess.getEnabledHook(PreventStateProvider.class);
        if (preventStateProvider != null && preventStateProvider.shouldPreventTabOut()) {
            if (!focused) {
                ci.cancel();
            } else if (interstellarTikiTiks.apexx.client.util.GlfwPlatform.cursorWarpSupported()) {
                // Re-centre the pointer; skipped on Wayland, where an application cannot warp it.
                long handle = this.getWindow().getHandle();
                int width = this.getWindow().getWidth();
                int height = this.getWindow().getHeight();
                GLFW.glfwSetCursorPos(handle, width / 2.0, height / 2.0);
            }
        }
    }

    @Unique
    private static boolean isInputActive(net.minecraft.client.util.Window window, int key) {
        if (window == null || key == -1 || key == 0) return false;
        if (key < 0) {
            int button = Module.mouseButtonOf(key);
            if (button >= 0 && button <= GLFW.GLFW_MOUSE_BUTTON_LAST) {
                return GLFW.glfwGetMouseButton(window.getHandle(), button) == GLFW.GLFW_PRESS;
            }
            return false;
        }
        if (key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST) {
            return InputUtil.isKeyPressed(window, key);
        }
        return false;
    }

}
