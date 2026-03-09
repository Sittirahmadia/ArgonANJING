package dev.lvstrng.argon.module.modules.combat;

import dev.lvstrng.argon.event.events.AttackListener;
import dev.lvstrng.argon.event.events.ItemUseListener;
import dev.lvstrng.argon.event.events.TickListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.utils.*;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

public final class AutoCrystal extends Module implements TickListener, ItemUseListener, AttackListener {

    // Place settings
    private final NumberSetting placeDelay = new NumberSetting(
            EncryptedString.of("Place Delay"), 0, 20, 0, 1)
            .setDescription(EncryptedString.of("Ticks to wait before placing"));

    private final NumberSetting placeChance = new NumberSetting(
            EncryptedString.of("Place Chance"), 0, 100, 100, 1)
            .setDescription(EncryptedString.of("Randomization chance to place (%)"));

    // Break settings
    private final NumberSetting breakDelay = new NumberSetting(
            EncryptedString.of("Break Delay"), 0, 20, 0, 1)
            .setDescription(EncryptedString.of("Ticks to wait before breaking"));

    private final NumberSetting breakChance = new NumberSetting(
            EncryptedString.of("Break Chance"), 0, 100, 100, 1)
            .setDescription(EncryptedString.of("Randomization chance to break (%)"));

    // Behaviour settings
    private final BooleanSetting onRmb = new BooleanSetting(
            EncryptedString.of("On RMB"), true)
            .setDescription(EncryptedString.of("Only activates when holding right mouse button"));

    private final BooleanSetting fastMode = new BooleanSetting(
            EncryptedString.of("Fast Mode"), true)
            .setDescription(EncryptedString.of("Places immediately on a broken crystal's block"));

    private final BooleanSetting clickSimulation = new BooleanSetting(
            EncryptedString.of("Click Simulation"), false)
            .setDescription(EncryptedString.of("Simulates a real mouse click for CPS counters"));

    private final BooleanSetting noCountGlitch = new BooleanSetting(
            EncryptedString.of("No Count Glitch"), true)
            .setDescription(EncryptedString.of("Cancels right-click packet to prevent count glitch"));

    private final BooleanSetting noBounce = new BooleanSetting(
            EncryptedString.of("No Bounce"), true)
            .setDescription(EncryptedString.of("Prevents crystal bounce animation interfering with placement"));

    private final BooleanSetting autoSwitch = new BooleanSetting(
            EncryptedString.of("Auto Switch"), false)
            .setDescription(EncryptedString.of("Automatically switches to end crystal in hotbar"));

    private final BooleanSetting requireObsidian = new BooleanSetting(
            EncryptedString.of("Require Obsidian"), true)
            .setDescription(EncryptedString.of("Only places on obsidian or bedrock"));

    private int placeClock = 0;
    private int breakClock = 0;
    // tracks crystals that have been placed this tick to avoid double-breaking
    private EndCrystalEntity lastBrokenCrystal = null;

    public AutoCrystal() {
        super(EncryptedString.of("Auto Crystal"),
                EncryptedString.of("Automatically places and breaks end crystals"),
                -1,
                Category.COMBAT);
        addSettings(placeDelay, placeChance, breakDelay, breakChance,
                onRmb, fastMode, clickSimulation, noCountGlitch, noBounce,
                autoSwitch, requireObsidian);
    }

    @Override
    public void onEnable() {
        eventManager.add(TickListener.class, this);
        eventManager.add(ItemUseListener.class, this);
        eventManager.add(AttackListener.class, this);
        reset();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(TickListener.class, this);
        eventManager.remove(ItemUseListener.class, this);
        eventManager.remove(AttackListener.class, this);
        super.onDisable();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;

        // RMB gate
        if (onRmb.getValue() &&
                GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS)
            return;

        // Auto-switch to crystal if enabled
        if (autoSwitch.getValue() && !mc.player.isHolding(Items.END_CRYSTAL)) {
            InventoryUtils.selectItemFromHotbar(Items.END_CRYSTAL);
        }

        if (mc.player.isHolding(Items.END_CRYSTAL)) {
            placeCrystal();
            breakCrystal();
        }

        placeClock++;
        breakClock++;
    }

    public void placeCrystal() {
        if (placeClock < placeDelay.getValueInt()) return;
        if (MathUtils.randomInt(1, 100) > placeChance.getValueInt()) return;

        if (mc.crosshairTarget instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK) {

            BlockPos pos = blockHit.getBlockPos();
            BlockState state = mc.world.getBlockState(pos);

            boolean validBlock = !requireObsidian.getValue()
                    || state.isOf(Blocks.OBSIDIAN)
                    || state.isOf(Blocks.BEDROCK);

            if (validBlock && CrystalUtils.canPlaceCrystalClient(pos)) {
                doPlace(blockHit);
                placeClock = 0;
            }

        } else if (fastMode.getValue() && mc.crosshairTarget instanceof EntityHitResult entityHit) {
            // Fast mode: place on the block below a just-broken crystal
            if (entityHit.getEntity() instanceof EndCrystalEntity crystal) {
                tryFastPlace(crystal);
            } else if (entityHit.getEntity() instanceof SlimeEntity) {
                // Slime can hide a crystal entity — raytrace ignoring invisibles
                HitResult real = WorldUtils.getHitResult(4.5);
                if (real instanceof EntityHitResult er && er.getEntity() instanceof EndCrystalEntity crystal) {
                    tryFastPlace(crystal);
                }
            }
        }
    }

    private void tryFastPlace(EndCrystalEntity crystal) {
        // Only fast-place on a crystal that we just broke (EndCrystalEntity has no hurtTime field)
        if (crystal != lastBrokenCrystal) return;

        double reach = 4.5;
        Vec3d cam = mc.player.getCameraPosVec(net.minecraft.client.render.RenderTickCounter.ONE.getTickDelta(false));
        Vec3d look = WorldUtils.getPlayerLookVec(mc.player.getYaw(), mc.player.getPitch());
        Vec3d end = cam.add(look.multiply(reach));

        BlockHitResult blockHit = mc.world.raycast(new RaycastContext(
                cam, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));

        if (blockHit == null) return;

        BlockState state = mc.world.getBlockState(blockHit.getBlockPos());
        if (!state.isOf(Blocks.OBSIDIAN) && !state.isOf(Blocks.BEDROCK)) return;

        doPlace(blockHit);
        placeClock = 0;
    }

    private void doPlace(BlockHitResult blockHit) {
        if (clickSimulation.getValue())
            MouseSimulation.mouseClick(GLFW.GLFW_MOUSE_BUTTON_RIGHT);

        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, blockHit);
        if (result.isAccepted() && result.shouldSwingHand())
            mc.player.swingHand(Hand.MAIN_HAND);
    }

    public void breakCrystal() {
        if (breakClock < breakDelay.getValueInt()) return;
        if (MathUtils.randomInt(1, 100) > breakChance.getValueInt()) return;

        if (!(mc.crosshairTarget instanceof EntityHitResult hit)) return;

        if (hit.getEntity() instanceof EndCrystalEntity crystal) {
            if (crystal == lastBrokenCrystal) return; // don't double-break
            doBreak(crystal);
        } else if (hit.getEntity() instanceof SlimeEntity) {
            // Slime hiding crystal
            HitResult real = WorldUtils.getHitResult(4.5);
            if (real instanceof EntityHitResult er && er.getEntity() instanceof EndCrystalEntity crystal) {
                if (crystal == lastBrokenCrystal) return;
                doBreak(crystal);
            }
        }
    }

    private void doBreak(EndCrystalEntity crystal) {
        if (clickSimulation.getValue())
            MouseSimulation.mouseClick(GLFW.GLFW_MOUSE_BUTTON_LEFT);

        mc.interactionManager.attackEntity(mc.player, crystal);
        mc.player.swingHand(Hand.MAIN_HAND);

        lastBrokenCrystal = crystal;
        breakClock = 0;
    }

    private void reset() {
        placeClock = placeDelay.getValueInt();
        breakClock = breakDelay.getValueInt();
        lastBrokenCrystal = null;
    }

    @Override
    public void onItemUse(ItemUseEvent event) {
        if (!mc.player.isHolding(Items.END_CRYSTAL)) return;
        if (noCountGlitch.getValue()
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS) {
            event.cancel();
        }
    }

    @Override
    public void onAttack(AttackListener.AttackEvent event) {
        if (!mc.player.isHolding(Items.END_CRYSTAL)) return;
        if (noBounce.getValue()
                && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            event.cancel();
        }
    }
}
