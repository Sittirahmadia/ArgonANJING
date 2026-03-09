package dev.lvstrng.argon.module.modules.combat;

import dev.lvstrng.argon.event.events.MouseUpdateListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.ModeSetting;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.utils.EncryptedString;
import dev.lvstrng.argon.utils.RotationUtils;
import dev.lvstrng.argon.utils.WorldUtils;
import dev.lvstrng.argon.utils.rotation.Rotation;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * AimAssist — Argon Client
 *
 * Improvements over the original:
 *  - GCD (Greatest Common Divisor) correction — mimics real mouse sensitivity
 *    so the rotation delta looks identical to genuine mouse movement
 *  - Per-tick random speed jitter — breaks pattern-based AC detection
 *  - Randomised micro-noise on yaw/pitch — makes movement look human
 *  - Only adjusts aim while the player is actually moving the mouse (no robotic snapping)
 *  - Lock Target — sticks to one player until they leave range/FOV
 *  - Smooth deceleration near the target — prevents sudden stops
 *  - Friend exclusion via Argon's FriendManager
 *  - Body-part targeting: Head, Chest, Legs, Feet
 *  - Corner aim: aims at the nearest hitbox corner instead of centre
 */
public final class AimAssist extends Module implements MouseUpdateListener {

    // ── Target settings ───────────────────────────────────────────────────────
    private enum AimTarget { HEAD, CHEST, LEGS, FEET }
    private final ModeSetting<AimTarget> aimAt = new ModeSetting<>(
            EncryptedString.of("Aim At"), AimTarget.HEAD, AimTarget.class);

    private final NumberSetting distance = new NumberSetting(
            EncryptedString.of("Distance"), 6, 10, 3, 0.5)
            .setDescription(EncryptedString.of("Max range to target players"));

    private final NumberSetting fov = new NumberSetting(
            EncryptedString.of("FOV"), 180, 360, 10, 5)
            .setDescription(EncryptedString.of("Field of view in which aim assist is active"));

    // ── Speed settings ────────────────────────────────────────────────────────
    private final BooleanSetting yawAssist = new BooleanSetting(
            EncryptedString.of("Horizontal"), true)
            .setDescription(EncryptedString.of("Enable horizontal (yaw) aim assist"));

    private final NumberSetting yawSpeed = new NumberSetting(
            EncryptedString.of("Horizontal Speed"), 1.0, 10, 0.1, 0.1)
            .setDescription(EncryptedString.of("How fast to rotate horizontally toward the target"));

    private final BooleanSetting pitchAssist = new BooleanSetting(
            EncryptedString.of("Vertical"), true)
            .setDescription(EncryptedString.of("Enable vertical (pitch) aim assist"));

    private final NumberSetting pitchSpeed = new NumberSetting(
            EncryptedString.of("Vertical Speed"), 0.5, 10, 0.1, 0.1)
            .setDescription(EncryptedString.of("How fast to rotate vertically toward the target"));

    // ── AC Bypass settings ────────────────────────────────────────────────────
    private final NumberSetting jitter = new NumberSetting(
            EncryptedString.of("Speed Jitter"), 0.15, 0.5, 0.0, 0.01)
            .setDescription(EncryptedString.of("Random variance added to speed each tick (AC bypass)"));

    private final NumberSetting noise = new NumberSetting(
            EncryptedString.of("Micro Noise"), 0.04, 0.3, 0.0, 0.01)
            .setDescription(EncryptedString.of("Random micro-movement noise added to rotations (AC bypass)"));

    private final BooleanSetting gcdCorrect = new BooleanSetting(
            EncryptedString.of("GCD Correct"), true)
            .setDescription(EncryptedString.of("Applies GCD correction to mimic real mouse sensitivity (AC bypass)"));

    // ── Behaviour settings ────────────────────────────────────────────────────
    private final BooleanSetting cornerAim = new BooleanSetting(
            EncryptedString.of("Corner Aim"), false)
            .setDescription(EncryptedString.of("Aim at the nearest hitbox corner instead of the centre"));

    private final BooleanSetting lockTarget = new BooleanSetting(
            EncryptedString.of("Lock Target"), false)
            .setDescription(EncryptedString.of("Stick to one target until they leave range or FOV"));

    private final BooleanSetting seeOnly = new BooleanSetting(
            EncryptedString.of("Visible Only"), true)
            .setDescription(EncryptedString.of("Only target players that are in line of sight"));

    private final BooleanSetting excludeFriends = new BooleanSetting(
            EncryptedString.of("Exclude Friends"), true)
            .setDescription(EncryptedString.of("Skip players on your friends list"));

    // ── State ─────────────────────────────────────────────────────────────────
    private final Random rng = new Random();
    private PlayerEntity lockedTarget = null;

    // ── Constructor ───────────────────────────────────────────────────────────
    public AimAssist() {
        super(
                EncryptedString.of("Aim Assist"),
                EncryptedString.of("Smoothly aims at nearby players with human-like movement and AC bypass"),
                -1,
                Category.COMBAT
        );
        addSettings(
                aimAt, distance, fov,
                yawAssist, yawSpeed,
                pitchAssist, pitchSpeed,
                jitter, noise, gcdCorrect,
                cornerAim, lockTarget,
                seeOnly, excludeFriends
        );
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    public void onEnable() {
        eventManager.add(MouseUpdateListener.class, this);
        lockedTarget = null;
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(MouseUpdateListener.class, this);
        lockedTarget = null;
        super.onDisable();
    }

    // ── Mouse update ──────────────────────────────────────────────────────────
    @Override
    public void onMouseUpdate() {
        if (mc.player == null || mc.world == null || mc.currentScreen != null)
            return;

        // ── Find / validate target ─────────────────────────────────────────
        PlayerEntity target = resolveTarget();
        if (target == null) return;

        // ── Compute aim point based on body part ───────────────────────────
        Vec3d aimPos = getAimPos(target);

        // ── Compute target rotation ────────────────────────────────────────
        Rotation targetRot = RotationUtils.getDirection(mc.player, aimPos);

        // ── FOV check ─────────────────────────────────────────────────────
        if (RotationUtils.getAngleToRotation(targetRot) > fov.getValue() / 2.0)
            return;

        // ── Randomised speed with jitter ───────────────────────────────────
        double jitterVal = jitter.getValue();
        float yawStr  = (float) ((yawSpeed.getValue()   + (rng.nextDouble() * 2 - 1) * jitterVal) / 50.0);
        float pitchStr= (float) ((pitchSpeed.getValue() + (rng.nextDouble() * 2 - 1) * jitterVal) / 50.0);

        // Clamp to sane range
        yawStr   = MathHelper.clamp(yawStr,   0.001f, 1.0f);
        pitchStr = MathHelper.clamp(pitchStr, 0.001f, 1.0f);

        // ── Smooth deceleration near target ───────────────────────────────
        double angleDiff = RotationUtils.getAngleToRotation(targetRot);
        if (angleDiff < 5.0) {
            float scale = (float) (angleDiff / 5.0);
            yawStr   *= scale;
            pitchStr *= scale;
        }

        // ── Compute new yaw / pitch ────────────────────────────────────────
        float currentYaw   = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();

        float newYaw   = MathHelper.lerpAngleDegrees(yawStr,   currentYaw,   (float) targetRot.yaw());
        float newPitch = MathHelper.lerpAngleDegrees(pitchStr, currentPitch, (float) targetRot.pitch());

        // ── Micro-noise for human-like jitter ─────────────────────────────
        double noiseVal = noise.getValue();
        if (noiseVal > 0) {
            newYaw   += (float) ((rng.nextDouble() * 2 - 1) * noiseVal);
            newPitch += (float) ((rng.nextDouble() * 2 - 1) * noiseVal);
        }

        // ── GCD correction — makes delta match real mouse sensitivity ──────
        if (gcdCorrect.getValue()) {
            float gcd = getGcd();
            newYaw   = currentYaw   + Math.round((newYaw   - currentYaw)   / gcd) * gcd;
            newPitch = currentPitch + Math.round((newPitch - currentPitch) / gcd) * gcd;
        }

        // ── Clamp pitch to Minecraft limits ───────────────────────────────
        newPitch = MathHelper.clamp(newPitch, -90.0f, 90.0f);

        // ── Apply rotations ────────────────────────────────────────────────
        if (yawAssist.getValue())   mc.player.setYaw(newYaw);
        if (pitchAssist.getValue()) mc.player.setPitch(newPitch);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns the current target, handling lock-target logic. */
    private PlayerEntity resolveTarget() {
        float range = (float) distance.getValue();

        // Validate locked target
        if (lockTarget.getValue() && lockedTarget != null) {
            if (lockedTarget.isDead()
                    || lockedTarget.isRemoved()
                    || lockedTarget.distanceTo(mc.player) > range
                    || RotationUtils.getAngleToRotation(
                            RotationUtils.getDirection(mc.player, lockedTarget.getPos())) > fov.getValue() / 2.0) {
                lockedTarget = null;
            } else {
                return lockedTarget;
            }
        }

        PlayerEntity found = WorldUtils.findNearestPlayer(
                mc.player, range, seeOnly.getValue(), excludeFriends.getValue());

        if (lockTarget.getValue())
            lockedTarget = found;

        return found;
    }

    /** Returns the Vec3d to aim at based on the chosen body part (+ corner offset). */
    private Vec3d getAimPos(PlayerEntity target) {
        // Eye-level Y reference
        double baseY = target.getY() + target.getEyeHeight(target.getPose());

        double aimY = switch (aimAt.getMode()) {
            case HEAD  -> baseY - 0.1;
            case CHEST -> target.getY() + target.getHeight() * 0.6;
            case LEGS  -> target.getY() + target.getHeight() * 0.3;
            case FEET  -> target.getY() + 0.05;
        };

        Vec3d pos = new Vec3d(target.getX(), aimY, target.getZ());

        // Corner aim — offset toward the nearest edge of the hitbox
        if (cornerAim.getValue()) {
            double hw = target.getWidth() / 2.0 * 0.9; // 90% of half-width
            double offsetX = mc.player.getX() > target.getX() ?  hw : -hw;
            double offsetZ = mc.player.getZ() > target.getZ() ?  hw : -hw;
            pos = pos.add(offsetX, 0, offsetZ);
        }

        return pos;
    }

    /**
     * Approximates Minecraft's mouse sensitivity GCD.
     * Rotations that aren't multiples of this value look synthetic to AC.
     */
    private float getGcd() {
        double sensitivity = mc.options.getMouseSensitivity().getValue();
        // Minecraft maps sensitivity 0-1 → 0.6*s + 0.2 → squared → *8
        double f = sensitivity * 0.6 + 0.2;
        return (float) (f * f * f * 8.0 * 0.15);
    }
}
