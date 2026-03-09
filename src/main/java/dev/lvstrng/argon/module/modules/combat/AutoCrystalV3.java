package dev.lvstrng.argon.module.modules.combat;

import dev.lvstrng.argon.event.events.AttackListener;
import dev.lvstrng.argon.event.events.ItemUseListener;
import dev.lvstrng.argon.event.events.TickListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.MinMaxSetting;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.utils.*;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
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

import java.util.ArrayList;
import java.util.List;

/**
 * AutoCrystalV3 — Enhanced crosshair-based auto crystal.
 *
 * New features over V1:
 *  - Millisecond-precision place/break delay via MinMaxSetting (random range)
 *  - Configurable place & break range
 *  - Min-damage threshold: skips placements/breaks that deal too little damage
 *  - Anti-Suicide: never act if explosion would kill you
 *  - Movement prediction: offset target hitbox by velocity before damage calc
 *  - Smart Place: scan best position around nearest target when crosshair has no valid block
 *  - Lethal Break: instantly break a crystal that would kill the target
 *  - Swap-back: return to previous hotbar slot after placing
 *  - Misclick: randomly skip windows to appear more human
 */
public final class AutoCrystalV3 extends Module implements TickListener, ItemUseListener, AttackListener {

    // ── Target ────────────────────────────────────────────────────────────────

    private final NumberSetting targetRange = new NumberSetting(
            EncryptedString.of("Target Range"), 2, 10, 6, 0.5)
            .setDescription(EncryptedString.of("Max distance to scan for enemy players"));

    private final BooleanSetting visibleOnly = new BooleanSetting(
            EncryptedString.of("Visible Only"), false)
            .setDescription(EncryptedString.of("Only target players in direct line of sight"));

    private final BooleanSetting predictMovement = new BooleanSetting(
            EncryptedString.of("Predict Movement"), true)
            .setDescription(EncryptedString.of("Offset target position by velocity for damage calc"));

    // ── Place ─────────────────────────────────────────────────────────────────

    private final BooleanSetting placeEnabled = new BooleanSetting(
            EncryptedString.of("Place"), true)
            .setDescription(EncryptedString.of("Enable crystal placement"));

    private final NumberSetting placeRange = new NumberSetting(
            EncryptedString.of("Place Range"), 1, 6, 4.5, 0.5)
            .setDescription(EncryptedString.of("Max block distance to place crystals"));

    private final MinMaxSetting placeDelayMs = new MinMaxSetting(
            EncryptedString.of("Place Delay ms"), 0, 500, 1, 0, 80)
            .setDescription(EncryptedString.of("Random ms between placements"));

    private final NumberSetting placeChance = new NumberSetting(
            EncryptedString.of("Place Chance %"), 1, 100, 100, 1)
            .setDescription(EncryptedString.of("Chance to place each window"));

    private final NumberSetting minPlaceDamage = new NumberSetting(
            EncryptedString.of("Min Place Dmg"), 0, 20, 2, 0.5)
            .setDescription(EncryptedString.of("Skip placement if target damage is below this"));

    private final BooleanSetting smartPlace = new BooleanSetting(
            EncryptedString.of("Smart Place"), true)
            .setDescription(EncryptedString.of("Scan best position near target when crosshair has no valid block"));

    // ── Break ─────────────────────────────────────────────────────────────────

    private final BooleanSetting breakEnabled = new BooleanSetting(
            EncryptedString.of("Break"), true)
            .setDescription(EncryptedString.of("Enable crystal breaking"));

    private final NumberSetting breakRange = new NumberSetting(
            EncryptedString.of("Break Range"), 1, 6, 4.5, 0.5)
            .setDescription(EncryptedString.of("Max distance to attack crystals"));

    private final MinMaxSetting breakDelayMs = new MinMaxSetting(
            EncryptedString.of("Break Delay ms"), 0, 500, 1, 0, 60)
            .setDescription(EncryptedString.of("Random ms between breaks"));

    private final NumberSetting breakChance = new NumberSetting(
            EncryptedString.of("Break Chance %"), 1, 100, 100, 1)
            .setDescription(EncryptedString.of("Chance to break each window"));

    private final NumberSetting minBreakDamage = new NumberSetting(
            EncryptedString.of("Min Break Dmg"), 0, 20, 1, 0.5)
            .setDescription(EncryptedString.of("Skip breaking if target damage is below this"));

    private final BooleanSetting lethalBreak = new BooleanSetting(
            EncryptedString.of("Lethal Break"), true)
            .setDescription(EncryptedString.of("Break lethal crystals instantly, ignoring break delay"));

    // ── Safety ────────────────────────────────────────────────────────────────

    private final BooleanSetting antiSuicide = new BooleanSetting(
            EncryptedString.of("Anti Suicide"), true)
            .setDescription(EncryptedString.of("Never act if the explosion would kill you"));

    // ── Behaviour ─────────────────────────────────────────────────────────────

    private final BooleanSetting onRmb = new BooleanSetting(
            EncryptedString.of("On RMB"), false)
            .setDescription(EncryptedString.of("Only activates while holding right mouse button"));

    private final BooleanSetting fastMode = new BooleanSetting(
            EncryptedString.of("Fast Mode"), true)
            .setDescription(EncryptedString.of("Place immediately on a just-broken crystal position"));

    private final BooleanSetting autoSwitch = new BooleanSetting(
            EncryptedString.of("Auto Switch"), true)
            .setDescription(EncryptedString.of("Auto-switch to end crystal in hotbar"));

    private final BooleanSetting swapBack = new BooleanSetting(
            EncryptedString.of("Swap Back"), true)
            .setDescription(EncryptedString.of("Return to previous slot after placing"));

    private final BooleanSetting requireObsidian = new BooleanSetting(
            EncryptedString.of("Require Obsidian"), true)
            .setDescription(EncryptedString.of("Only place on obsidian or bedrock"));

    private final BooleanSetting noCountGlitch = new BooleanSetting(
            EncryptedString.of("No Count Glitch"), true)
            .setDescription(EncryptedString.of("Cancel right-click packet to prevent count glitch"));

    private final BooleanSetting noBounce = new BooleanSetting(
            EncryptedString.of("No Bounce"), true)
            .setDescription(EncryptedString.of("Cancel vanilla LMB when not pressing mouse"));

    private final BooleanSetting misclick = new BooleanSetting(
            EncryptedString.of("Misclick"), true)
            .setDescription(EncryptedString.of("Randomly skip a window to appear more human"));

    private final NumberSetting misclickChance = new NumberSetting(
            EncryptedString.of("Misclick %"), 0, 25, 5, 1)
            .setDescription(EncryptedString.of("Chance per window to skip action"));

    // ── State ─────────────────────────────────────────────────────────────────

    private final TimerUtils placeTimer = new TimerUtils();
    private final TimerUtils breakTimer = new TimerUtils();

    private int  prevSlot   = -1;
    private EndCrystalEntity lastBroken = null;

    // ── Constructor ───────────────────────────────────────────────────────────

    public AutoCrystalV3() {
        super(EncryptedString.of("Auto Crystal V3"),
                EncryptedString.of("Enhanced crosshair-based auto crystal with smart features"),
                -1,
                Category.COMBAT);

        addSettings(
                targetRange, visibleOnly, predictMovement,
                placeEnabled, placeRange, placeDelayMs, placeChance, minPlaceDamage, smartPlace,
                breakEnabled, breakRange, breakDelayMs, breakChance, minBreakDamage, lethalBreak,
                antiSuicide,
                onRmb, fastMode, autoSwitch, swapBack, requireObsidian,
                noCountGlitch, noBounce, misclick, misclickChance
        );
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        eventManager.add(TickListener.class, this);
        eventManager.add(ItemUseListener.class, this);
        eventManager.add(AttackListener.class, this);
        placeTimer.reset();
        breakTimer.reset();
        lastBroken = null;
        prevSlot   = -1;
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(TickListener.class, this);
        eventManager.remove(ItemUseListener.class, this);
        eventManager.remove(AttackListener.class, this);
        super.onDisable();
    }

    // ── Main tick ─────────────────────────────────────────────────────────────

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;

        if (onRmb.getValue() &&
                GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS)
            return;

        // Auto-switch to crystal
        boolean didSwitch = false;
        if (autoSwitch.getValue() && !mc.player.isHolding(Items.END_CRYSTAL)) {
            int prev = mc.player.getInventory().selectedSlot;
            if (InventoryUtils.selectItemFromHotbar(Items.END_CRYSTAL)) {
                prevSlot   = prev;
                didSwitch  = true;
            }
        }

        if (!mc.player.isHolding(Items.END_CRYSTAL)) return;

        // Find nearest target
        PlayerEntity target = findTarget();

        if (placeEnabled.getValue()) tryPlace(target);
        if (breakEnabled.getValue()) tryBreak(target);

        // Swap back if we only switched for this tick and didn't place
        if (didSwitch && swapBack.getValue() && prevSlot != -1) {
            InventoryUtils.setInvSlot(prevSlot);
            prevSlot = -1;
        }
    }

    // ── Target finding ────────────────────────────────────────────────────────

    private PlayerEntity findTarget() {
        PlayerEntity best     = null;
        float        bestDist = Float.MAX_VALUE;

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            if (p.isDead() || p.getHealth() <= 0) continue;
            if (p.isSpectator()) continue;

            float dist = (float) mc.player.distanceTo(p);
            if (dist > targetRange.getValue()) continue;

            if (visibleOnly.getValue() && !hasLineOfSight(p)) continue;

            if (dist < bestDist) {
                bestDist = dist;
                best     = p;
            }
        }
        return best;
    }

    private boolean hasLineOfSight(PlayerEntity p) {
        Vec3d eyes       = mc.player.getEyePos();
        Vec3d targetEyes = p.getEyePos();
        return mc.world.raycast(new RaycastContext(
                eyes, targetEyes,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                mc.player)).getType() == HitResult.Type.MISS;
    }

    // ── Placement ─────────────────────────────────────────────────────────────

    private void tryPlace(PlayerEntity target) {
        if (!placeTimer.hasReached(placeDelayMs.getRandomValueLong())) return;
        if (MathUtils.randomInt(1, 100) > placeChance.getValueInt()) return;
        if (misclick.getValue() && MathUtils.randomInt(1, 100) <= misclickChance.getValueInt()) return;

        // 1. Crosshair block
        if (mc.crosshairTarget instanceof BlockHitResult blockHit
                && blockHit.getType() == HitResult.Type.BLOCK) {
            if (attemptPlace(blockHit, target)) return;
        }

        // 2. Fast mode: place on last broken crystal's block
        if (fastMode.getValue() && lastBroken != null && !lastBroken.isRemoved()) {
            BlockPos below = lastBroken.getBlockPos().down();
            if (isValidBase(below) && CrystalUtils.canPlaceCrystalClientAssumeObsidian(below)) {
                BlockHitResult fakeHit = BlockHitResult.createMissed(
                        Vec3d.ofCenter(below).add(0, 0.5, 0),
                        net.minecraft.util.math.Direction.UP, below);
                if (attemptPlace(fakeHit, target)) return;
            }
        }

        // 3. Smart scan: find best position around target
        if (smartPlace.getValue() && target != null) {
            BlockPos best = findBestPlacement(target);
            if (best != null) {
                BlockHitResult scanHit = BlockHitResult.createMissed(
                        Vec3d.ofCenter(best).add(0, 0.5, 0),
                        net.minecraft.util.math.Direction.UP, best);
                attemptPlace(scanHit, target);
            }
        }
    }

    private boolean attemptPlace(BlockHitResult hit, PlayerEntity target) {
        BlockPos pos = hit.getBlockPos();

        if (!isWithinRange(Vec3d.ofCenter(pos), placeRange.getValue())) return false;
        if (!isValidBase(pos)) return false;
        if (!CrystalUtils.canPlaceCrystalClient(pos)) return false;

        Vec3d  explode  = Vec3d.ofCenter(pos).add(0, 1, 0);
        float  selfDmg  = calcDamage(mc.player, explode);
        float  tgtDmg   = target != null ? calcDamage(target, explode) : 0f;

        if (antiSuicide.getValue() && selfDmg >= mc.player.getHealth()) return false;
        if (target != null && tgtDmg < minPlaceDamage.getValue()) return false;

        doPlace(hit);
        placeTimer.reset();

        // Swap back after place
        if (swapBack.getValue() && prevSlot != -1) {
            InventoryUtils.setInvSlot(prevSlot);
            prevSlot = -1;
        }
        return true;
    }

    private void doPlace(BlockHitResult hit) {
        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        if (result.isAccepted() && result.shouldSwingHand())
            mc.player.swingHand(Hand.MAIN_HAND);
    }

    // ── Breaking ──────────────────────────────────────────────────────────────

    private void tryBreak(PlayerEntity target) {
        List<EndCrystalEntity> crystals = new ArrayList<>();

        for (EndCrystalEntity c : mc.world.getEntitiesByClass(
                EndCrystalEntity.class,
                mc.player.getBoundingBox().expand(breakRange.getValue() + 1),
                e -> true)) {
            if (isWithinRange(c.getPos(), breakRange.getValue()))
                crystals.add(c);
        }

        if (crystals.isEmpty()) return;

        // Score each crystal
        EndCrystalEntity best      = null;
        float            bestScore = -Float.MAX_VALUE;

        for (EndCrystalEntity c : crystals) {
            float tDmg = target != null ? calcDamage(target, c.getPos()) : 0f;
            float sDmg = calcDamage(mc.player, c.getPos());

            if (antiSuicide.getValue() && sDmg >= mc.player.getHealth()) continue;
            if (target != null && tDmg < minBreakDamage.getValue()) continue;

            float score = tDmg - sDmg * 0.5f;
            if (score > bestScore) {
                bestScore = score;
                best      = c;
            }
        }

        if (best == null) return;

        // Lethal break ignores delay
        float   tDmg   = target != null ? calcDamage(target, best.getPos()) : 0f;
        boolean lethal = lethalBreak.getValue() && target != null && tDmg >= target.getHealth();

        if (!lethal && !breakTimer.hasReached(breakDelayMs.getRandomValueLong())) return;
        if (MathUtils.randomInt(1, 100) > breakChance.getValueInt()) return;
        if (misclick.getValue() && MathUtils.randomInt(1, 100) <= misclickChance.getValueInt()) return;

        doBreak(best);
    }

    private void doBreak(EndCrystalEntity crystal) {
        mc.interactionManager.attackEntity(mc.player, crystal);
        mc.player.swingHand(Hand.MAIN_HAND);
        lastBroken = crystal;
        breakTimer.reset();
    }

    // ── Smart placement scan ──────────────────────────────────────────────────

    private BlockPos findBestPlacement(PlayerEntity target) {
        Vec3d targetPos = predictMovement.getValue()
                ? target.getPos().add(target.getVelocity())
                : target.getPos();

        BlockPos center   = BlockPos.ofFloored(targetPos);
        int      radius   = 3;
        BlockPos bestPos  = null;
        float    bestScore = -Float.MAX_VALUE;

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int y = -1; y <= 1; y++) {
                    BlockPos pos = center.add(x, y, z);
                    if (!isWithinRange(Vec3d.ofCenter(pos), placeRange.getValue())) continue;
                    if (!isValidBase(pos)) continue;
                    if (!CrystalUtils.canPlaceCrystalClient(pos)) continue;

                    Vec3d explode = Vec3d.ofCenter(pos).add(0, 1, 0);
                    float tDmg   = calcDamage(target, explode);
                    float sDmg   = calcDamage(mc.player, explode);

                    if (antiSuicide.getValue() && sDmg >= mc.player.getHealth()) continue;
                    if (tDmg < minPlaceDamage.getValue()) continue;

                    float score = tDmg - sDmg * 0.5f;
                    if (score > bestScore) {
                        bestScore = score;
                        bestPos   = pos;
                    }
                }
            }
        }
        return bestPos;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isValidBase(BlockPos pos) {
        BlockState s = mc.world.getBlockState(pos);
        boolean isObsidian = s.isOf(Blocks.OBSIDIAN) || s.isOf(Blocks.BEDROCK);
        return !requireObsidian.getValue() || isObsidian;
    }

    private boolean isWithinRange(Vec3d pos, double range) {
        return mc.player.getEyePos().distanceTo(pos) <= range;
    }

    /**
     * Lightweight damage estimate using inverse-square falloff with
     * explosion power 6 (end crystal), with rough armour reduction.
     */
    private float calcDamage(net.minecraft.entity.Entity entity, Vec3d explosionPos) {
        double dist = entity.getPos().add(0, entity.getHeight() / 2.0, 0).distanceTo(explosionPos);
        if (dist > 12) return 0f;
        double impact   = (1.0 - dist / 12.0);
        impact          = impact * impact;
        float rawDamage = (float) (impact * 7.0 * 12.0 + 1);
        return rawDamage * 0.5f; // rough armour reduction
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @Override
    public void onItemUse(ItemUseListener.ItemUseEvent event) {
        if (!mc.player.isHolding(Items.END_CRYSTAL)) return;
        if (noCountGlitch.getValue() &&
                GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS) {
            event.cancel();
        }
    }

    @Override
    public void onAttack(AttackListener.AttackEvent event) {
        if (!mc.player.isHolding(Items.END_CRYSTAL)) return;
        if (noBounce.getValue() &&
                GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            event.cancel();
        }
    }
}
