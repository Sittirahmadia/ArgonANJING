package dev.lvstrng.argon.module.modules.combat;

import dev.lvstrng.argon.event.events.TickListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.KeybindSetting;
import dev.lvstrng.argon.module.setting.MinMaxSetting;
import dev.lvstrng.argon.utils.BlockUtils;
import dev.lvstrng.argon.utils.EncryptedString;
import dev.lvstrng.argon.utils.InventoryUtils;
import dev.lvstrng.argon.utils.MathUtils;
import dev.lvstrng.argon.utils.MouseSimulation;
import dev.lvstrng.argon.utils.WorldUtils;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import org.lwjgl.glfw.GLFW;

/**
 * WebMacro — hold the activate key and right-click a block to:
 *  1. Place a cobweb on the targeted surface.
 *  2. (Optional) Place TNT / Creeper egg on top of the web.
 *  3. (Optional) Pour lava on top of the web.
 *
 * Each stage has its own per-tick delay and the previous hotbar slot is
 * restored after the sequence completes.
 */
public final class WebMacro extends Module implements TickListener {

    // ── Options ────────────────────────────────────────────────────────────
    private final BooleanSetting clickSimulation = new BooleanSetting(
            EncryptedString.of("Click Simulation"), true)
            .setDescription(EncryptedString.of("Simulates a real mouse click for CPS counters"));

    private final BooleanSetting goToPrevSlot = new BooleanSetting(
            EncryptedString.of("Go To Prev Slot"), true)
            .setDescription(EncryptedString.of("Returns to your previous hotbar slot after the sequence"));

    private final BooleanSetting placeTNT = new BooleanSetting(
            EncryptedString.of("Place TNT"), false)
            .setDescription(EncryptedString.of("Places TNT or a Creeper egg on top of the web"));

    private final BooleanSetting placeLava = new BooleanSetting(
            EncryptedString.of("Place Lava"), false)
            .setDescription(EncryptedString.of("Pours lava on top of the web (runs instead of TNT if both are enabled but no TNT found)"));

    private final MinMaxSetting placeDelay = new MinMaxSetting(
            EncryptedString.of("Place Delay"), 0, 10, 1, 0, 2)
            .setDescription(EncryptedString.of("Random tick range to wait before each placement"));

    private final MinMaxSetting switchDelay = new MinMaxSetting(
            EncryptedString.of("Switch Delay"), 0, 10, 1, 0, 2)
            .setDescription(EncryptedString.of("Random tick range to wait before each slot switch"));

    private final KeybindSetting activateKey = new KeybindSetting(
            EncryptedString.of("Activate Key"), GLFW.GLFW_KEY_C, false)
            .setDescription(EncryptedString.of("Hold this key to run the macro"));

    // ── State machine ──────────────────────────────────────────────────────
    private enum Stage {
        IDLE,
        SWITCH_TO_WEB, PLACE_WEB,
        SWITCH_TO_TNT, PLACE_TNT,
        SWITCH_TO_LAVA, PLACE_LAVA,
        RETURN_SLOT
    }

    private Stage stage      = Stage.IDLE;
    private int   prevSlot   = -1;
    private int   delayClock = 0;
    private int   delayTarget = 0;

    // ──────────────────────────────────────────────────────────────────────

    public WebMacro() {
        super(EncryptedString.of("Web Macro"),
                EncryptedString.of("Places cobweb + optional TNT/lava on the targeted block while holding the key"),
                -1,
                Category.COMBAT);
        addSettings(clickSimulation, goToPrevSlot, placeTNT, placeLava,
                placeDelay, switchDelay, activateKey);
    }

    @Override
    public void onEnable() {
        eventManager.add(TickListener.class, this);
        resetState();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(TickListener.class, this);
        resetState();
        super.onDisable();
    }

    // ── Main tick ──────────────────────────────────────────────────────────

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;
        if (mc.getNetworkHandler() == null) return;
        if (mc.currentScreen != null) { resetState(); return; }

        boolean keyDown = GLFW.glfwGetKey(mc.getWindow().getHandle(), activateKey.getKey()) == GLFW.GLFW_PRESS;

        // Key released mid-sequence: abort and restore slot
        if (!keyDown) {
            if (stage != Stage.IDLE) {
                returnSlot();
                resetState();
            }
            return;
        }

        // Must be right-clicking a block
        if (!(mc.crosshairTarget instanceof BlockHitResult blockHit)
                || blockHit.getType() == HitResult.Type.MISS) {
            return;
        }

        // Tick the active delay countdown; pause until it reaches 0
        if (delayClock > 0) { delayClock--; return; }

        switch (stage) {

            case IDLE -> {
                // Only start if crosshair is NOT already on a cobweb
                if (BlockUtils.isBlock(blockHit.getBlockPos(), Blocks.COBWEB)) return;
                if (!InventoryUtils.hasItemInHotbar(i -> i == Items.COBWEB)) return;

                prevSlot  = mc.player.getInventory().selectedSlot;
                stage     = Stage.SWITCH_TO_WEB;
                nextDelay(switchDelay);
            }

            case SWITCH_TO_WEB -> {
                if (!InventoryUtils.selectItemFromHotbar(Items.COBWEB)) {
                    // Ran out of webs
                    returnSlot();
                    resetState();
                    return;
                }
                stage = Stage.PLACE_WEB;
                nextDelay(placeDelay);
            }

            case PLACE_WEB -> {
                if (!BlockUtils.isBlock(blockHit.getBlockPos(), Blocks.COBWEB)) {
                    placeOnBlock(blockHit);
                }
                // Decide next stage
                if (placeTNT.getValue() && hasTNT()) {
                    stage = Stage.SWITCH_TO_TNT;
                } else if (placeLava.getValue()
                        && InventoryUtils.hasItemInHotbar(i -> i == Items.LAVA_BUCKET)
                        && blockHit.getSide() == Direction.UP) {
                    stage = Stage.SWITCH_TO_LAVA;
                } else {
                    stage = goToPrevSlot.getValue() ? Stage.RETURN_SLOT : Stage.IDLE;
                    if (stage == Stage.RETURN_SLOT) nextDelay(switchDelay);
                    else resetState();
                    return;
                }
                nextDelay(switchDelay);
            }

            case SWITCH_TO_TNT -> {
                int tntSlot = getTNTSlot();
                if (tntSlot == -1) {
                    // No TNT — try lava fallback
                    if (placeLava.getValue()
                            && InventoryUtils.hasItemInHotbar(i -> i == Items.LAVA_BUCKET)) {
                        stage = Stage.SWITCH_TO_LAVA;
                        nextDelay(switchDelay);
                    } else {
                        stage = goToPrevSlot.getValue() ? Stage.RETURN_SLOT : Stage.IDLE;
                        if (stage == Stage.RETURN_SLOT) nextDelay(switchDelay);
                        else resetState();
                    }
                    return;
                }
                InventoryUtils.setInvSlot(tntSlot);
                stage = Stage.PLACE_TNT;
                nextDelay(placeDelay);
            }

            case PLACE_TNT -> {
                // Place on top of the web — force UP face
                BlockHitResult upHit = blockHit.withSide(Direction.UP);
                placeOnBlock(upHit);
                if (placeLava.getValue()
                        && InventoryUtils.hasItemInHotbar(i -> i == Items.LAVA_BUCKET)) {
                    stage = Stage.SWITCH_TO_LAVA;
                    nextDelay(switchDelay);
                } else {
                    stage = goToPrevSlot.getValue() ? Stage.RETURN_SLOT : Stage.IDLE;
                    if (stage == Stage.RETURN_SLOT) nextDelay(switchDelay);
                    else resetState();
                }
            }

            case SWITCH_TO_LAVA -> {
                if (!InventoryUtils.selectItemFromHotbar(Items.LAVA_BUCKET)) {
                    stage = goToPrevSlot.getValue() ? Stage.RETURN_SLOT : Stage.IDLE;
                    if (stage == Stage.RETURN_SLOT) nextDelay(switchDelay);
                    else resetState();
                    return;
                }
                stage = Stage.PLACE_LAVA;
                nextDelay(placeDelay);
            }

            case PLACE_LAVA -> {
                // interactItem pours the bucket at the crosshair position
                if (clickSimulation.getValue())
                    MouseSimulation.mouseClick(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                ActionResult result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                if (result.isAccepted() && result.shouldSwingHand())
                    mc.player.swingHand(Hand.MAIN_HAND);

                stage = goToPrevSlot.getValue() ? Stage.RETURN_SLOT : Stage.IDLE;
                if (stage == Stage.RETURN_SLOT) nextDelay(switchDelay);
                else resetState();
            }

            case RETURN_SLOT -> {
                returnSlot();
                resetState();
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Interact with a block face, optionally simulating a mouse click. */
    private void placeOnBlock(BlockHitResult hit) {
        if (clickSimulation.getValue())
            MouseSimulation.mouseClick(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        if (result.isAccepted() && result.shouldSwingHand())
            mc.player.swingHand(Hand.MAIN_HAND);
    }

    /** Returns true when the player has TNT or a Creeper egg in their hotbar. */
    private boolean hasTNT() {
        return InventoryUtils.hasItemInHotbar(i -> i == Items.TNT || i == Items.CREEPER_SPAWN_EGG);
    }

    /** Returns the hotbar slot index of TNT or Creeper egg, -1 if absent. */
    private int getTNTSlot() {
        for (int i = 0; i < 9; i++) {
            var item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.TNT || item == Items.CREEPER_SPAWN_EGG) return i;
        }
        return -1;
    }

    /** Restores the hotbar slot saved before the sequence began. */
    private void returnSlot() {
        if (prevSlot >= 0 && prevSlot <= 8)
            InventoryUtils.setInvSlot(prevSlot);
    }

    /** Assigns a new randomised delay countdown using the given MinMaxSetting. */
    private void nextDelay(MinMaxSetting setting) {
        delayClock  = MathUtils.randomInt(setting.getMinInt(), setting.getMaxInt());
        delayTarget = delayClock;
    }

    private void resetState() {
        stage       = Stage.IDLE;
        prevSlot    = -1;
        delayClock  = 0;
        delayTarget = 0;
    }
}
