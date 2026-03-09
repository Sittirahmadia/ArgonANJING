package dev.lvstrng.argon.module.modules.combat;

import dev.lvstrng.argon.event.events.AttackListener;
import dev.lvstrng.argon.event.events.ItemUseListener;
import dev.lvstrng.argon.event.events.PlayerTickListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.utils.*;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

/**
 * AutoDtap — crosshair-based double-pop crystal placer.
 *
 * Root cause of previous "can't place" bug:
 *   CrystalUtils.canPlaceCrystalServer() has a hardcoded logic bug —
 *   it uses && between obsidian and bedrock checks (impossible to satisfy),
 *   so it ALWAYS returns false. This recode uses canPlaceCrystalClient()
 *   which is correct, plus an inline block-type guard, matching AutoCrystal.
 *
 * doPlace() mirrors AutoCrystal.doPlace() exactly:
 *   MouseSimulation.mouseClick (if sim enabled) + interactBlock + swingHand
 *
 * doBreak() mirrors AutoCrystal.doBreak() exactly:
 *   MouseSimulation.mouseClick (if sim enabled) + attackEntity + swingHand
 *   with a lastBrokenCrystal guard to prevent double-breaking.
 */
public final class AutoDtap extends Module implements PlayerTickListener, ItemUseListener, AttackListener {

    // ── Settings ───────────────────────────────────────────────────────────

    private final NumberSetting placeDelay = new NumberSetting(
            EncryptedString.of("Place Delay"), 0, 20, 0, 1)
            .setDescription(EncryptedString.of("Ticks between crystal placements (0 = every tick)"));

    private final NumberSetting breakDelay = new NumberSetting(
            EncryptedString.of("Break Delay"), 0, 20, 0, 1)
            .setDescription(EncryptedString.of("Ticks between crystal breaks (0 = every tick)"));

    private final NumberSetting maxCrystals = new NumberSetting(
            EncryptedString.of("Max Crystals"), 1, 6, 2, 1)
            .setDescription(EncryptedString.of("Max placed crystals before waiting for a break"));

    private final BooleanSetting requireObsidian = new BooleanSetting(
            EncryptedString.of("Require Obsidian"), true)
            .setDescription(EncryptedString.of("Only place on obsidian or bedrock"));

    private final BooleanSetting simulateClick = new BooleanSetting(
            EncryptedString.of("Simulate Click"), true)
            .setDescription(EncryptedString.of("Sends a real mouse event alongside interaction"));

    private final BooleanSetting noCountGlitch = new BooleanSetting(
            EncryptedString.of("No Count Glitch"), true)
            .setDescription(EncryptedString.of("Cancels vanilla right-click when not pressing RMB (prevents count desync)"));

    private final BooleanSetting noBounce = new BooleanSetting(
            EncryptedString.of("No Bounce"), true)
            .setDescription(EncryptedString.of("Cancels vanilla left-click attack when not pressing LMB"));

    private final BooleanSetting activateOnRightClick = new BooleanSetting(
            EncryptedString.of("Activate On RightClick"), false)
            .setDescription(EncryptedString.of("Only runs while holding right click"));

    private final BooleanSetting stopOnKill = new BooleanSetting(
            EncryptedString.of("Stop On Kill"), false)
            .setDescription(EncryptedString.of("Stops when a nearby player dies"));

    // ── State ──────────────────────────────────────────────────────────────

    private int placeClock = 0;
    private int breakClock = 0;
    private int placedCount = 0;
    private EndCrystalEntity lastBrokenCrystal = null;

    // ── Constructor ────────────────────────────────────────────────────────

    public AutoDtap() {
        super(EncryptedString.of("Auto Dtap"),
                EncryptedString.of("Crosshair-based double-pop crystal placer"),
                -1,
                Category.COMBAT);
        addSettings(placeDelay, breakDelay, maxCrystals,
                requireObsidian, simulateClick, noCountGlitch, noBounce,
                activateOnRightClick, stopOnKill);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        eventManager.add(PlayerTickListener.class, this);
        eventManager.add(ItemUseListener.class, this);
        eventManager.add(AttackListener.class, this);
        reset();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(PlayerTickListener.class, this);
        eventManager.remove(ItemUseListener.class, this);
        eventManager.remove(AttackListener.class, this);
        reset();
        super.onDisable();
    }

    private void reset() {
        placeClock        = 0;
        breakClock        = 0;
        placedCount       = 0;
        lastBrokenCrystal = null;
    }

    // ── Place / Break ──────────────────────────────────────────────────────

    /**
     * Mirrors AutoCrystal.doPlace() exactly.
     * Uses MouseSimulation + interactBlock together.
     */
    private void doPlace(BlockHitResult hit) {
        if (simulateClick.getValue())
            MouseSimulation.mouseClick(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        if (result.isAccepted() && result.shouldSwingHand())
            mc.player.swingHand(Hand.MAIN_HAND);
        placedCount++;
        placeClock = 0;
    }

    /**
     * Mirrors AutoCrystal.doBreak() exactly.
     * Uses MouseSimulation + attackEntity + lastBrokenCrystal guard.
     */
    private void doBreak(EndCrystalEntity crystal) {
        if (simulateClick.getValue())
            MouseSimulation.mouseClick(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        mc.interactionManager.attackEntity(mc.player, crystal);
        mc.player.swingHand(Hand.MAIN_HAND);
        if (placedCount > 0) placedCount--;
        lastBrokenCrystal = crystal;
        breakClock = 0;
    }

    /**
     * Placement validation using canPlaceCrystalClient — NOT canPlaceCrystalServer.
     *
     * canPlaceCrystalServer in CrystalUtils has a bug:
     *   if (!blockState.isOf(Blocks.OBSIDIAN) || !blockState.isOf(Blocks.BEDROCK))
     * This should be && not ||, so it always fails for both obsidian and bedrock.
     *
     * canPlaceCrystalClient is correct and checks:
     *   - block above is air
     *   - no entities in 1x2 box above
     *
     * We add the obsidian/bedrock guard separately.
     */
    private boolean canPlace(BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = mc.world.getBlockState(pos);

        // Block type check (bypass broken canPlaceCrystalServer)
        boolean validBase = !requireObsidian.getValue()
                || state.isOf(Blocks.OBSIDIAN)
                || state.isOf(Blocks.BEDROCK);
        if (!validBase) return false;

        // Use the working client-side check (checks air above + entity collision)
        return CrystalUtils.canPlaceCrystalClient(pos);
    }

    // ── Tick ───────────────────────────────────────────────────────────────

    @Override
    public void onPlayerTick() {
        if (mc.player == null || mc.world == null) return;
        if (mc.getNetworkHandler() == null) return;
        if (mc.currentScreen != null) return;

        // Right-click gate
        if (activateOnRightClick.getValue()
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS)
            return;

        // Must hold end crystal
        if (!mc.player.isHolding(Items.END_CRYSTAL)) return;

        // Stop on nearby death
        if (stopOnKill.getValue() && WorldUtils.isDeadBodyNearby()) return;

        placeClock++;
        breakClock++;

        // ── Break ──────────────────────────────────────────────────────────
        if (breakClock >= breakDelay.getValueInt()
                && mc.crosshairTarget instanceof EntityHitResult hit
                && hit.getEntity() instanceof EndCrystalEntity crystal
                && crystal != lastBrokenCrystal) {
            doBreak(crystal);
        }

        // ── Place ──────────────────────────────────────────────────────────
        if (placeClock >= placeDelay.getValueInt()
                && placedCount < maxCrystals.getValueInt()
                && mc.crosshairTarget instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK
                && canPlace(blockHit)) {
            doPlace(blockHit);
        }
    }

    // ── Item use / attack cancel ───────────────────────────────────────────

    /** Prevent vanilla right-click crystal-use desync when not pressing RMB. */
    @Override
    public void onItemUse(ItemUseEvent event) {
        if (!mc.player.isHolding(Items.END_CRYSTAL)) return;
        if (noCountGlitch.getValue()
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS) {
            event.cancel();
        }
    }

    /** Prevent vanilla left-click attack bounce when not pressing LMB. */
    @Override
    public void onAttack(AttackEvent event) {
        if (!mc.player.isHolding(Items.END_CRYSTAL)) return;
        if (noBounce.getValue()
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            event.cancel();
        }
    }
}
