package dev.lvstrng.argon.module.modules.combat;

import dev.lvstrng.argon.Argon;
import dev.lvstrng.argon.event.events.*;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.modules.client.Friends;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.MinMaxSetting;
import dev.lvstrng.argon.module.setting.ModeSetting;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.utils.*;
import dev.lvstrng.argon.utils.rotation.Rotation;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.GameMode;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public final class AutoCrystalV2 extends Module
        implements TickListener, MovementPacketListener, ItemUseListener, AttackListener {

    public enum TargetPriority { Nearest, LowestHP, HighestDamage }
    public enum RotateMode    { PacketSilent, TickSilent, Normal, None }

    // Target
    private final NumberSetting targetRange = new NumberSetting(EncryptedString.of("Target Range"), 2, 8, 6, 0.5).setDescription(EncryptedString.of("Max distance to scan for enemy players"));
    private final ModeSetting<TargetPriority> targetPriority = new ModeSetting<>(EncryptedString.of("Target Priority"), TargetPriority.LowestHP, TargetPriority.class).setDescription(EncryptedString.of("How to choose the primary target"));
    private final NumberSetting targetLockTicks = new NumberSetting(EncryptedString.of("Target Lock Ticks"), 0, 20, 6, 1).setDescription(EncryptedString.of("Hold a target for N ticks before switching (0=no lock)"));
    private final BooleanSetting visibleOnly = new BooleanSetting(EncryptedString.of("Visible Only"), false).setDescription(EncryptedString.of("Only target players in line of sight"));
    private final BooleanSetting excludeFriends = new BooleanSetting(EncryptedString.of("Exclude Friends"), true).setDescription(EncryptedString.of("Skip players on your friends list"));
    private final BooleanSetting predictMovement = new BooleanSetting(EncryptedString.of("Predict Movement"), true).setDescription(EncryptedString.of("Offset target position by velocity for damage calc"));

    // Place
    private final BooleanSetting placeEnabled = new BooleanSetting(EncryptedString.of("Place"), true).setDescription(EncryptedString.of("Place end crystals"));
    private final NumberSetting placeRange = new NumberSetting(EncryptedString.of("Place Range"), 1, 6, 4.5, 0.5).setDescription(EncryptedString.of("Max block distance to place crystals"));
    private final NumberSetting minPlaceDamage = new NumberSetting(EncryptedString.of("Min Place Dmg"), 0, 20, 2, 0.5).setDescription(EncryptedString.of("Skip placement if damage to target is below this"));
    private final MinMaxSetting placeDelayMs = new MinMaxSetting(EncryptedString.of("Place Delay ms"), 0, 500, 1, 0, 100).setDescription(EncryptedString.of("Random ms between placement attempts"));
    private final NumberSetting placeChance = new NumberSetting(EncryptedString.of("Place Chance %"), 1, 100, 100, 1).setDescription(EncryptedString.of("Chance to place each window"));

    // Break
    private final BooleanSetting breakEnabled = new BooleanSetting(EncryptedString.of("Break"), true).setDescription(EncryptedString.of("Break end crystals"));
    private final NumberSetting breakRange = new NumberSetting(EncryptedString.of("Break Range"), 1, 6, 4.5, 0.5).setDescription(EncryptedString.of("Max distance to attack crystals"));
    private final NumberSetting minBreakDamage = new NumberSetting(EncryptedString.of("Min Break Dmg"), 0, 20, 1, 0.5).setDescription(EncryptedString.of("Skip breaking if damage to target is below this"));
    private final MinMaxSetting breakDelayMs = new MinMaxSetting(EncryptedString.of("Break Delay ms"), 0, 500, 1, 0, 80).setDescription(EncryptedString.of("Random ms between break attempts"));
    private final NumberSetting breakChance = new NumberSetting(EncryptedString.of("Break Chance %"), 1, 100, 100, 1).setDescription(EncryptedString.of("Chance to break each window"));
    private final BooleanSetting ownCrystalFastBreak = new BooleanSetting(EncryptedString.of("Fast Break Own"), true).setDescription(EncryptedString.of("Break crystals we placed instantly, bypassing break delay"));
    private final BooleanSetting lethalBreak = new BooleanSetting(EncryptedString.of("Lethal Break"), true).setDescription(EncryptedString.of("Break lethal crystals immediately regardless of delay"));
    private final BooleanSetting raytraceBreak = new BooleanSetting(EncryptedString.of("Raytrace Break"), true).setDescription(EncryptedString.of("Only break crystals with clear line of sight (prevents Grim flags)"));
    private final BooleanSetting antiSurround = new BooleanSetting(EncryptedString.of("Anti Surround"), true).setDescription(EncryptedString.of("Break enemy crystals placed near you before they explode"));
    private final NumberSetting antiSurroundRadius = new NumberSetting(EncryptedString.of("Surround Radius"), 1, 4, 2.5, 0.5).setDescription(EncryptedString.of("Radius around self to check for enemy crystals"));

    // Safety
    private final NumberSetting maxSelfDamage = new NumberSetting(EncryptedString.of("Max Self Dmg"), 0, 20, 8, 0.5).setDescription(EncryptedString.of("Never place/break if self-damage exceeds this (0=no limit)"));
    private final NumberSetting selfDamageWeight = new NumberSetting(EncryptedString.of("Self Dmg Weight"), 0, 3, 1.5, 0.1).setDescription(EncryptedString.of("Penalise self-damage in scoring"));
    private final BooleanSetting antiSuicide = new BooleanSetting(EncryptedString.of("Anti Suicide"), true).setDescription(EncryptedString.of("Never place/break if the explosion would kill you"));

    // Rotation
    private final ModeSetting<RotateMode> rotateMode = new ModeSetting<>(EncryptedString.of("Rotate Mode"), RotateMode.PacketSilent, RotateMode.class).setDescription(EncryptedString.of("PacketSilent=inject into movement packet | TickSilent | Normal | None"));
    private final BooleanSetting gcdCorrect = new BooleanSetting(EncryptedString.of("GCD Correct"), true).setDescription(EncryptedString.of("Align rotation delta to mouse GCD — looks like real mouse input"));

    // Rate limiter
    private final NumberSetting maxActionsPerSec = new NumberSetting(EncryptedString.of("Max Actions/s"), 1, 30, 20, 1).setDescription(EncryptedString.of("Hard cap on interactions per second (prevents packet spam flags)"));

    // Misc
    private final BooleanSetting onRmb = new BooleanSetting(EncryptedString.of("On RMB"), false).setDescription(EncryptedString.of("Only activates while holding right mouse button"));
    private final BooleanSetting autoSwitch = new BooleanSetting(EncryptedString.of("Auto Switch"), true).setDescription(EncryptedString.of("Switch to end crystal in hotbar automatically"));
    private final BooleanSetting swapBack = new BooleanSetting(EncryptedString.of("Swap Back"), true).setDescription(EncryptedString.of("Restore hotbar slot after each action"));
    private final BooleanSetting requireObsidian = new BooleanSetting(EncryptedString.of("Require Obsidian"), true).setDescription(EncryptedString.of("Only place on obsidian or bedrock"));
    private final BooleanSetting noCountGlitch = new BooleanSetting(EncryptedString.of("No Count Glitch"), true).setDescription(EncryptedString.of("Cancels right-click to prevent count glitch"));
    private final BooleanSetting noBounce = new BooleanSetting(EncryptedString.of("No Bounce"), true).setDescription(EncryptedString.of("Cancels vanilla LMB when not pressing mouse"));
    private final BooleanSetting misclick = new BooleanSetting(EncryptedString.of("Misclick"), true).setDescription(EncryptedString.of("Randomly skips a window to appear human"));
    private final NumberSetting misclickChance = new NumberSetting(EncryptedString.of("Misclick %"), 0, 25, 5, 1).setDescription(EncryptedString.of("Chance per window to skip"));
    private final MinMaxSetting clickHoldMs = new MinMaxSetting(EncryptedString.of("Click Hold ms"), 10, 120, 1, 25, 60).setDescription(EncryptedString.of("Simulated click hold duration"));

    // ── State ──────────────────────────────────────────────────────────────────
    private final TimerUtils placeTimer = new TimerUtils();
    private final TimerUtils breakTimer = new TimerUtils();
    private final TimerUtils rateTimer  = new TimerUtils();
    private final Random     rng        = new Random();

    // Packet rotation
    private float queuedYaw   = Float.NaN;
    private float queuedPitch = Float.NaN;
    private float savedYaw    = Float.NaN;
    private float savedPitch  = Float.NaN;

    // Target lock
    private PlayerEntity lockedTarget    = null;
    private int          lockTickCounter = 0;

    // Own-crystal tracking
    private final Set<BlockPos> ownPlacedPositions = new LinkedHashSet<>();

    // Swap-back
    private int prevSlot = -1;

    // Placement scan cache
    private BlockPos     cachedPlacePos = null;
    private PlayerEntity cachedForTarget = null;
    private long         cacheTimestamp  = 0L;
    private static final long CACHE_MS   = 50L;

    // Rate limiter
    private int actionsThisSec = 0;

    public AutoCrystalV2() {
        super(EncryptedString.of("Auto Crystal V2"),
                EncryptedString.of("Crystal PvP — packet rotation, fast-break, lethal check, raytrace, AC bypass"),
                -1, Category.COMBAT);
        addSettings(
                targetRange, targetPriority, targetLockTicks, visibleOnly, excludeFriends, predictMovement,
                placeEnabled, placeRange, minPlaceDamage, placeDelayMs, placeChance,
                breakEnabled, breakRange, minBreakDamage, breakDelayMs, breakChance,
                ownCrystalFastBreak, lethalBreak, raytraceBreak,
                antiSurround, antiSurroundRadius,
                maxSelfDamage, selfDamageWeight, antiSuicide,
                rotateMode, gcdCorrect,
                maxActionsPerSec,
                onRmb, autoSwitch, swapBack, requireObsidian,
                noCountGlitch, noBounce, misclick, misclickChance, clickHoldMs
        );
    }

    @Override public void onEnable() {
        eventManager.add(TickListener.class, this);
        eventManager.add(MovementPacketListener.class, this);
        eventManager.add(ItemUseListener.class, this);
        eventManager.add(AttackListener.class, this);
        resetState(); super.onEnable();
    }

    @Override public void onDisable() {
        eventManager.remove(TickListener.class, this);
        eventManager.remove(MovementPacketListener.class, this);
        eventManager.remove(ItemUseListener.class, this);
        eventManager.remove(AttackListener.class, this);
        resetState(); super.onDisable();
    }

    private void resetState() {
        queuedYaw = savedYaw = queuedPitch = savedPitch = Float.NaN;
        lockedTarget = null; lockTickCounter = 0;
        ownPlacedPositions.clear();
        cachedPlacePos = null; cachedForTarget = null; cacheTimestamp = 0L;
        prevSlot = -1; actionsThisSec = 0;
    }

    // ── Packet-level rotation injection ───────────────────────────────────────
    @Override
    public void onSendMovementPackets() {
        if (mc.player == null) return;
        if (rotateMode.isMode(RotateMode.PacketSilent) && !Float.isNaN(queuedYaw)) {
            mc.player.setYaw(queuedYaw);
            mc.player.setPitch(queuedPitch);
        }
    }

    // ── Tick ──────────────────────────────────────────────────────────────────
    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;

        // Restore silent rotation from last tick
        if (!Float.isNaN(savedYaw)) {
            mc.player.setYaw(savedYaw);
            mc.player.setPitch(savedPitch);
            savedYaw = savedPitch = queuedYaw = queuedPitch = Float.NaN;
        }

        if (onRmb.getValue() &&
                GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS)
            return;

        // Rate limiter reset
        if (rateTimer.hasReached(1000L)) { actionsThisSec = 0; rateTimer.reset(); }

        // Auto-switch
        boolean didSwitch = false;
        if (autoSwitch.getValue() && !mc.player.isHolding(Items.END_CRYSTAL)) {
            int prev = mc.player.getInventory().selectedSlot;
            if (InventoryUtils.selectItemFromHotbar(Items.END_CRYSTAL)) { prevSlot = prev; didSwitch = true; }
        }
        if (!mc.player.isHolding(Items.END_CRYSTAL)) return;

        PlayerEntity target = resolveTarget();
        if (target == null) { doSwapBack(didSwitch); return; }

        boolean acted = false;

        // Anti-surround
        if (antiSurround.getValue() && canAct()) {
            EndCrystalEntity sc = findNearbySurroundCrystal();
            if (sc != null) { doBreak(sc); acted = true; }
        }

        // Own-crystal fast-break
        if (!acted && ownCrystalFastBreak.getValue() && canAct()) {
            EndCrystalEntity oc = findOwnCrystalToBreak(target);
            if (oc != null) { doBreak(oc); breakTimer.reset(); acted = true; }
        }

        // Lethal break
        if (!acted && lethalBreak.getValue() && canAct()) {
            EndCrystalEntity lc = findLethalCrystal(target);
            if (lc != null) { doBreak(lc); breakTimer.reset(); acted = true; }
        }

        // Normal break
        if (!acted && breakEnabled.getValue() && canAct()
                && breakTimer.hasReached(randomMs(breakDelayMs))
                && rng.nextInt(100) < breakChance.getValueInt()
                && !misrollCheck()) {
            EndCrystalEntity bc = findBestCrystalToBreak(target);
            if (bc != null) { doBreak(bc); breakTimer.reset(); }
        }

        // Place
        if (placeEnabled.getValue() && canAct()
                && placeTimer.hasReached(randomMs(placeDelayMs))
                && rng.nextInt(100) < placeChance.getValueInt()
                && !misrollCheck()) {
            BlockPos bp = findBestPlacementPos(target);
            if (bp != null) { doPlace(bp); placeTimer.reset(); }
        }

        doSwapBack(didSwitch);
    }

    // ── Target resolution ─────────────────────────────────────────────────────
    private PlayerEntity resolveTarget() {
        if (mc.world == null) return null;
        if (lockedTarget != null) {
            lockTickCounter++;
            boolean expired = targetLockTicks.getValueInt() > 0 && lockTickCounter >= targetLockTicks.getValueInt();
            boolean invalid = lockedTarget.isDead() || lockedTarget.isRemoved()
                    || lockedTarget.distanceTo(mc.player) > targetRange.getValue();
            if (!expired && !invalid) return lockedTarget;
            lockedTarget = null; lockTickCounter = 0;
        }
        List<PlayerEntity> cands = new ArrayList<>();
        float range = (float) targetRange.getValue();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isDead() || p.isRemoved()) continue;
            if (p.distanceTo(mc.player) > range) continue;
            if (visibleOnly.getValue() && !mc.player.canSee(p)) continue;
            if (excludeFriends.getValue()) {
                Friends fm = Argon.INSTANCE.getModuleManager().getModule(Friends.class);
                if (fm != null && Argon.INSTANCE.getFriendManager().isFriend(p)) continue;
            }
            if (DamageUtils.getGameMode(p) == GameMode.CREATIVE) continue;
            cands.add(p);
        }
        if (cands.isEmpty()) return null;
        PlayerEntity found = switch (targetPriority.getMode()) {
            case Nearest -> cands.stream().min(Comparator.comparingDouble(p -> p.distanceTo(mc.player))).orElse(null);
            case LowestHP -> cands.stream().min(Comparator.comparingDouble(p -> p.getHealth() + p.getAbsorptionAmount())).orElse(null);
            case HighestDamage -> {
                PlayerEntity best = null; float bd = -1;
                for (PlayerEntity p : cands) { float d = bestPossibleDamage(p); if (d > bd) { bd = d; best = p; } }
                yield best;
            }
        };
        if (found != null && targetLockTicks.getValueInt() > 0) { lockedTarget = found; lockTickCounter = 0; }
        return found;
    }

    private float bestPossibleDamage(PlayerEntity t) {
        float best = 0; int r = (int) Math.ceil(placeRange.getValue());
        BlockPos o = t.getBlockPos();
        for (int dx=-r;dx<=r;dx++) for (int dy=-2;dy<=1;dy++) for (int dz=-r;dz<=r;dz++) {
            BlockPos p = o.add(dx,dy,dz);
            if (!isValidPlacement(p)) continue;
            float d = DamageUtils.crystalDamage(t, Vec3d.ofCenter(p.up()));
            if (d > best) best = d;
        }
        return best;
    }

    // ── Anti-surround ─────────────────────────────────────────────────────────
    private EndCrystalEntity findNearbySurroundCrystal() {
        if (mc.world == null) return null;
        double r = antiSurroundRadius.getValue();
        EndCrystalEntity best = null; double bd = Double.MAX_VALUE;
        for (var e : mc.world.getEntities()) {
            if (!(e instanceof EndCrystalEntity c) || c.isRemoved()) continue;
            if (ownPlacedPositions.contains(crystalBase(c))) continue;
            double d = DamageUtils.distanceTo(c);
            if (d > r || d >= bd) continue;
            if (raytraceBreak.getValue() && !hasLOS(c.getPos())) continue;
            float sd = DamageUtils.crystalDamage(mc.player, c.getPos());
            if (antiSuicide.getValue() && sd >= mc.player.getHealth()) continue;
            bd = d; best = c;
        }
        return best;
    }

    // ── Own-crystal fast-break ────────────────────────────────────────────────
    private EndCrystalEntity findOwnCrystalToBreak(PlayerEntity target) {
        if (mc.world == null || ownPlacedPositions.isEmpty()) return null;
        EndCrystalEntity best = null; float bs = Float.NEGATIVE_INFINITY;
        for (var e : mc.world.getEntities()) {
            if (!(e instanceof EndCrystalEntity c) || c.isRemoved()) continue;
            if (!ownPlacedPositions.contains(crystalBase(c))) continue;
            if (DamageUtils.distanceTo(c) > breakRange.getValue()) continue;
            if (raytraceBreak.getValue() && !hasLOS(c.getPos())) continue;
            float td = DamageUtils.crystalDamage(target, c.getPos());
            float sd = DamageUtils.crystalDamage(mc.player, c.getPos());
            if (td < minBreakDamage.getValue()) continue;
            if (maxSelfDamage.getValue() > 0 && sd > maxSelfDamage.getValue()) continue;
            if (antiSuicide.getValue() && sd >= mc.player.getHealth()) continue;
            float sc = td - (float)(selfDamageWeight.getValue() * sd);
            if (sc > bs) { bs = sc; best = c; }
        }
        return best;
    }

    // ── Lethal break ──────────────────────────────────────────────────────────
    private EndCrystalEntity findLethalCrystal(PlayerEntity target) {
        if (mc.world == null) return null;
        float hp = target.getHealth() + target.getAbsorptionAmount();
        for (var e : mc.world.getEntities()) {
            if (!(e instanceof EndCrystalEntity c) || c.isRemoved()) continue;
            if (DamageUtils.distanceTo(c) > breakRange.getValue()) continue;
            if (raytraceBreak.getValue() && !hasLOS(c.getPos())) continue;
            float td = DamageUtils.crystalDamage(target, c.getPos());
            float sd = DamageUtils.crystalDamage(mc.player, c.getPos());
            if (td < hp) continue;
            if (antiSuicide.getValue() && sd >= mc.player.getHealth()) continue;
            if (maxSelfDamage.getValue() > 0 && sd > maxSelfDamage.getValue()) continue;
            return c;
        }
        return null;
    }

    // ── Best break ────────────────────────────────────────────────────────────
    private EndCrystalEntity findBestCrystalToBreak(PlayerEntity target) {
        if (mc.world == null) return null;
        EndCrystalEntity best = null; float bs = Float.NEGATIVE_INFINITY;
        for (var e : mc.world.getEntities()) {
            if (!(e instanceof EndCrystalEntity c) || c.isRemoved()) continue;
            if (DamageUtils.distanceTo(c) > breakRange.getValue()) continue;
            if (raytraceBreak.getValue() && !hasLOS(c.getPos())) continue;
            float td = DamageUtils.crystalDamage(target, c.getPos());
            float sd = DamageUtils.crystalDamage(mc.player, c.getPos());
            if (td < minBreakDamage.getValue()) continue;
            if (maxSelfDamage.getValue() > 0 && sd > maxSelfDamage.getValue()) continue;
            if (antiSuicide.getValue() && sd >= mc.player.getHealth()) continue;
            float sc = td - (float)(selfDamageWeight.getValue() * sd);
            if (sc > bs) { bs = sc; best = c; }
        }
        return best;
    }

    // ── Best placement ────────────────────────────────────────────────────────
    private BlockPos findBestPlacementPos(PlayerEntity target) {
        if (mc.world == null) return null;
        long now = System.currentTimeMillis();
        if (cachedPlacePos != null && cachedForTarget == target && now - cacheTimestamp < CACHE_MS && isValidPlacement(cachedPlacePos))
            return cachedPlacePos;

        Vec3d tPos = predictMovement.getValue() ? target.getPos().add(target.getVelocity()) : target.getPos();
        BlockPos origin = BlockPos.ofFloored(tPos);
        int r = (int) Math.ceil(placeRange.getValue());
        BlockPos best = null; float bs = Float.NEGATIVE_INFINITY;

        for (int dx=-r;dx<=r;dx++) for (int dy=-2;dy<=1;dy++) for (int dz=-r;dz<=r;dz++) {
            BlockPos pos = origin.add(dx,dy,dz);
            if (DamageUtils.distanceTo(pos) > placeRange.getValue() + 0.5) continue;
            if (!isValidPlacement(pos)) continue;
            Vec3d ep = Vec3d.ofCenter(pos.up());
            float td = DamageUtils.crystalDamage(target, ep, predictMovement.getValue(), pos);
            float sd = DamageUtils.crystalDamage(mc.player, ep, false, pos);
            if (td < minPlaceDamage.getValue()) continue;
            if (maxSelfDamage.getValue() > 0 && sd > maxSelfDamage.getValue()) continue;
            if (antiSuicide.getValue() && sd >= mc.player.getHealth()) continue;
            float sc = td - (float)(selfDamageWeight.getValue() * sd);
            if (sc > bs) { bs = sc; best = pos; }
        }
        cachedPlacePos = best; cachedForTarget = target; cacheTimestamp = now;
        return best;
    }

    private boolean isValidPlacement(BlockPos pos) {
        if (mc.world == null) return false;
        BlockState s = mc.world.getBlockState(pos);
        if (requireObsidian.getValue()) { if (!s.isOf(Blocks.OBSIDIAN) && !s.isOf(Blocks.BEDROCK)) return false; }
        else { if (!s.isSolidBlock(mc.world, pos)) return false; }
        return CrystalUtils.canPlaceCrystalClientAssumeObsidian(pos);
    }

    // ── Interaction ───────────────────────────────────────────────────────────
    private void doPlace(BlockPos pos) {
        applyRotation(Vec3d.ofCenter(pos));
        Vec3d hit = Vec3d.ofCenter(pos).add(0, 0.5, 0);
        BlockHitResult bhr = new BlockHitResult(hit, Direction.UP, pos, false);
        MouseSimulation.mouseClick(GLFW.GLFW_MOUSE_BUTTON_RIGHT, rangeInt(clickHoldMs.getMinInt(), clickHoldMs.getMaxInt()));
        ActionResult r = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
        if (r.isAccepted() && r.shouldSwingHand()) mc.player.swingHand(Hand.MAIN_HAND);
        ownPlacedPositions.add(pos);
        if (ownPlacedPositions.size() > 32) { var it = ownPlacedPositions.iterator(); it.next(); it.remove(); }
        cachedPlacePos = null; actionsThisSec++;
    }

    private void doBreak(EndCrystalEntity crystal) {
        applyRotation(crystal.getPos());
        MouseSimulation.mouseClick(GLFW.GLFW_MOUSE_BUTTON_LEFT, rangeInt(clickHoldMs.getMinInt(), clickHoldMs.getMaxInt()));
        WorldUtils.hitEntity(crystal, true);
        ownPlacedPositions.remove(crystalBase(crystal));
        actionsThisSec++;
    }

    // ── Rotation ──────────────────────────────────────────────────────────────
    private void applyRotation(Vec3d target) {
        if (rotateMode.isMode(RotateMode.None)) return;
        Rotation rot = RotationUtils.getDirection(mc.player, target);
        float yaw = (float) rot.yaw(), pitch = (float) rot.pitch();
        if (gcdCorrect.getValue()) {
            float gcd = computeGcd(), cy = mc.player.getYaw(), cp = mc.player.getPitch();
            yaw   = cy + Math.round((yaw   - cy) / gcd) * gcd;
            pitch = cp + Math.round((pitch - cp) / gcd) * gcd;
        }
        pitch = MathHelper.clamp(pitch, -90f, 90f);
        if (rotateMode.isMode(RotateMode.PacketSilent)) {
            queuedYaw = yaw; queuedPitch = pitch;
            savedYaw  = mc.player.getYaw(); savedPitch = mc.player.getPitch();
        } else if (rotateMode.isMode(RotateMode.TickSilent)) {
            savedYaw = mc.player.getYaw(); savedPitch = mc.player.getPitch();
            mc.player.setYaw(yaw); mc.player.setPitch(pitch);
        } else {
            mc.player.setYaw(yaw); mc.player.setPitch(pitch);
        }
    }

    private float computeGcd() {
        double s = mc.options.getMouseSensitivity().getValue(), f = s * 0.6 + 0.2;
        return (float)(f * f * f * 8.0 * 0.15);
    }

    // ── Raytrace ──────────────────────────────────────────────────────────────
    private boolean hasLOS(Vec3d target) {
        if (mc.world == null) return false;
        Vec3d eyes = mc.player.getCameraPosVec(net.minecraft.client.render.RenderTickCounter.ONE.getTickDelta(false));
        var res = mc.world.raycast(new RaycastContext(eyes, target, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
        return res.getType() == HitResult.Type.MISS;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private boolean canAct() { return actionsThisSec < maxActionsPerSec.getValueInt(); }
    private boolean misrollCheck() { return misclick.getValue() && rng.nextInt(100) < misclickChance.getValueInt(); }
    private long randomMs(MinMaxSetting s) { long mn=(long)s.getMinValue(), mx=(long)s.getMaxValue(); return mn>=mx?mn:mn+(long)(rng.nextDouble()*(mx-mn)); }
    private int rangeInt(int mn, int mx) { return mn>=mx?mn:mn+rng.nextInt(mx-mn+1); }
    private BlockPos crystalBase(EndCrystalEntity c) { return BlockPos.ofFloored(c.getX(), c.getY()-1, c.getZ()); }
    private void doSwapBack(boolean did) { if (did && swapBack.getValue() && prevSlot != -1) { InventoryUtils.setInvSlot(prevSlot); prevSlot = -1; } }

    // ── Events ────────────────────────────────────────────────────────────────
    @Override public void onItemUse(ItemUseEvent event) {
        if (mc.player == null || !mc.player.isHolding(Items.END_CRYSTAL)) return;
        if (noCountGlitch.getValue() && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS) event.cancel();
    }

    @Override public void onAttack(AttackListener.AttackEvent event) {
        if (mc.player == null) return;
        if (noBounce.getValue() && GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) event.cancel();
    }
}
