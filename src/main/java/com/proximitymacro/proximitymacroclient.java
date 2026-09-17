package com.proximitymacro;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import org.lwjgl.glfw.GLFW;
import java.util.List;

public class ProximityMacroClient implements ClientModInitializer {

    private static KeyBinding toggleKey;
    private boolean isMacroEnabled = false;
    
    private MacroState currentState = MacroState.IDLE;
    private Entity currentTarget = null;
    private int tickDelayCounter = 0;

    private enum MacroState {
        IDLE,
        PRESS_V_1,
        WAIT_1,
        PRESS_F,
        LEFT_CLICK,
        PRESS_Q,
        WAIT_2,
        PRESS_V_2,
        DISARMED
    }

    @Override
    public void onInitializeClient() {
        // Register the global GUI toggle key (Default: K)
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.proximitymacro.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.proximitymacro"
        ));

        // Attach the FSM evaluator to the terminal phase of the client tick loop
        ClientTickEvents.END_CLIENT_TICK.register(this::evaluateFSM);

        // Intercept physical left-click events for the manual reset protocol
        ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> {
            if (this.currentState == MacroState.DISARMED) {
                this.currentState = MacroState.IDLE;
                if (client.player != null) {
                    client.player.sendMessage(Text.of("§a[Macro] System Re-Armed."), true);
                }
            }
            // Yield execution back to the native engine pipeline
            return false; 
        });
    }

    private void evaluateFSM(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        // Process hardware toggle input
        while (toggleKey.wasPressed()) {
            isMacroEnabled = !isMacroEnabled;
            client.player.sendMessage(Text.of("§e[Macro] Status: " + (isMacroEnabled ? "§aON" : "§cOFF")), true);
            if (!isMacroEnabled) {
                currentState = MacroState.IDLE; 
            }
        }

        if (!isMacroEnabled) return;

        // FSM Execution Matrix
        switch (currentState) {
            case IDLE:
                // Leverage spatial hashing via AABB expansion
                Box searchBox = client.player.getBoundingBox().expand(3.0D);
                List<Entity> nearbyEntities = client.world.getOtherEntities(client.player, searchBox, entity -> 
                    entity instanceof LivingEntity && !entity.isSpectator()
                );

                if (!nearbyEntities.isEmpty()) {
                    // Strict Euclidean validation
                    for (Entity entity : nearbyEntities) {
                        if (client.player.distanceTo(entity) <= 3.0F) {
                            currentTarget = entity;
                            currentState = MacroState.PRESS_V_1;
                            break;
                        }
                    }
                }
                break;

            case PRESS_V_1:
                injectRawKeystroke(client, GLFW.GLFW_KEY_V);
                tickDelayCounter = 0;
                currentState = MacroState.WAIT_1;
                break;

            case WAIT_1:
                tickDelayCounter++;
                if (tickDelayCounter >= 1) {
                    currentState = MacroState.PRESS_F;
                }
                break;

            case PRESS_F:
                injectRawKeystroke(client, GLFW.GLFW_KEY_F);
                currentState = MacroState.LEFT_CLICK;
                break;

            case LEFT_CLICK:
                if (currentTarget != null) {
                    // Direct packet-level interaction invocation
                    client.interactionManager.attackEntity(client.player, currentTarget);
                    client.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                }
                currentState = MacroState.PRESS_Q;
                break;

            case PRESS_Q:
                injectRawKeystroke(client, GLFW.GLFW_KEY_Q);
                tickDelayCounter = 0;
                currentState = MacroState.WAIT_2;
                break;

            case WAIT_2:
                tickDelayCounter++;
                if (tickDelayCounter >= 1) {
                    currentState = MacroState.PRESS_V_2;
                }
                break;

            case PRESS_V_2:
                injectRawKeystroke(client, GLFW.GLFW_KEY_V);
                currentState = MacroState.DISARMED;
                client.player.sendMessage(Text.of("§c[Macro] Disarmed. Awaiting manual Left-Click override."), true);
                break;

            case DISARMED:
                // Passive evaluation state pending ClientPreAttackCallback interrupt
                break;
        }
    }

    /**
     * Synthesizes a raw hardware interrupt within the GLFW input router.
     */
    private void injectRawKeystroke(MinecraftClient client, int glfwKeyCode) {
        InputUtil.Key key = InputUtil.fromKeyCode(glfwKeyCode, -1);
        KeyBinding.onKeyPressed(key);
        KeyBinding.setKeyPressed(key, false);
    }
}
