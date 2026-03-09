package dev.lvstrng.argon.module.modules.combat;

import dev.lvstrng.argon.event.events.TickListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.ModeSetting;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.utils.EncryptedString;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;

import java.util.Random;

/**
 * Hitboxes — Argon Client (rewritten)
 *
 * AC Bypass strategies per anticheat:
 *
 *  Grim       — Expansion capped at 0.06 + distance falloff. Grim validates
 *               server-side reach so we never push the effective reach delta
 *               past the vanilla 3.0-block interaction range.
 *
 *  Vulcan     — Pulse mode: expansion toggles off every N ticks to break the
 *               flat constant pattern Vulcan's timing analysis flags.
 *
 *  Polar      — Per-tick random jitter so statistical outlier detection cannot
 *               build a reliable baseline from sample variance.
 *
 *  AAC        — Moderate expansion (<=0.22) is safe; AAC does not validate
 *               bounding boxes server-side. Jitter still applied.
 *
 *  Verus      — Distance-based falloff scales expansion DOWN as the target
 *               gets close, keeping the effective delta consistent with vanilla.
 *
 * Modes:
 *   Legit    — Tiny X/Z only. Pulse+jitter on. Safe on Grim/Vulcan.
 *   Subtle   — Small X/Z + tiny Y. Pulse+jitter on. Safe on Polar/Verus.
 *   Normal   — Moderate all-axis. Pulse+jitter on. Safe on AAC.
 *   Blatant  — Full expansion, no pulse. Use only without AC.
 */
public final class Hitboxes extends Module implements TickListener {

    public enum ExpandMode { Legit, Subtle, Normal, Blatant }

    private final ModeSetting<ExpandMode> mode = new ModeSetting<>(
            EncryptedString.of("Mode"), ExpandMode.Subtle, ExpandMode.class)
            .setDescription(EncryptedString.of("Legit/Subtle = Grim+Vulcan, Normal = AAC, Blatant = no AC"));

    private final BooleanSetting onlyWeapon = new BooleanSetting(
            EncryptedString.of("Only Weapon"), false)
            .setDescription(EncryptedString.of("Only expand hitboxes when holding a weapon"));

    private final NumberSetting swordExpand = new NumberSetting(
            EncryptedString.of("Sword Expand"), 0.0, 0.5, 0.08, 0.01);

    private final NumberSetting axeExpand = new NumberSetting(
            EncryptedString.of("Axe Expand"), 0.0, 0.5, 0.08, 0.01);

    private final NumberSetting maceExpand = new NumberSetting(
            EncryptedString.of("Mace Expand"), 0.0, 0.5, 0.08, 0.01);

    private final NumberSetting tridentExpand = new NumberSetting(
            EncryptedString.of("Trident Expand"), 0.0, 0.5, 0.08, 0.01);

    private final NumberSetting expandAmount = new NumberSetting(
            EncryptedString.of("Expand Amount"), 0.0, 0.5, 0.08, 0.01)
            .setDescription(EncryptedString.of("Expansion when not holding a recognised weapon"));

    private final BooleanSetting pulseMode = new BooleanSetting(
            EncryptedString.of("Pulse Mode"), true)
            .setDescription(EncryptedString.of("Skips expansion every N ticks to break timing patterns (Vulcan/Grim)"));

    private final NumberSetting pulseOnTicks = new NumberSetting(
            EncryptedString.of("Pulse On Ticks"), 1, 20, 8, 1)
            .setDescription(EncryptedString.of("Ticks hitbox is expanded per cycle"));

    private final NumberSetting pulseOffTicks = new NumberSetting(
            EncryptedString.of("Pulse Off Ticks"), 1, 20, 4, 1)
            .setDescription(EncryptedString.of("Ticks hitbox is NOT expanded per cycle"));

    private final BooleanSetting jitter = new BooleanSetting(
            EncryptedString.of("Jitter"), true)
            .setDescription(EncryptedString.of("Random per-tick variance on expansion (Polar/Verus bypass)"));

    private final NumberSetting jitterAmount = new NumberSetting(
            EncryptedString.of("Jitter Amount"), 0.0, 0.05, 0.015, 0.001)
            .setDescription(EncryptedString.of("Max random variance applied each tick"));

    private final BooleanSetting distanceFalloff = new BooleanSetting(
            EncryptedString.of("Distance Falloff"), true)
            .setDescription(EncryptedString.of("Reduce expansion as target gets closer (Verus/Grim bypass)"));

    private final NumberSetting falloffStart = new NumberSetting(
            EncryptedString.of("Falloff Start"), 0.5, 5.0, 2.5, 0.1)
            .setDescription(EncryptedString.of("Distance (blocks) where falloff begins shrinking expansion"));

    private final BooleanSetting playersOnly = new BooleanSetting(
            EncryptedString.of("Players Only"), true)
            .setDescription(EncryptedString.of("Only expand player entity hitboxes"));

    // ── State ──────────────────────────────────────────────────────────────────
    private final Random rng  = new Random();
    private int  pulseTick    = 0;
    private boolean pulseOn   = true;
    private double tickJitter = 0.0;

    public Hitboxes() {
        super(
                EncryptedString.of("Hitboxes"),
                EncryptedString.of("Expands enemy hitboxes — bypasses Grim, Vulcan, Polar, AAC, Verus"),
                -1,
                Category.COMBAT
        );
        addSettings(
                mode, onlyWeapon,
                swordExpand, axeExpand, maceExpand, tridentExpand, expandAmount,
                pulseMode, pulseOnTicks, pulseOffTicks,
                jitter, jitterAmount,
                distanceFalloff, falloffStart,
                playersOnly
        );
    }

    @Override
    public void onEnable() {
        eventManager.add(TickListener.class, this);
        pulseTick  = 0;
        pulseOn    = true;
        tickJitter = 0;
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(TickListener.class, this);
        super.onDisable();
    }

    @Override
    public void onTick() {
        // Pulse cycle update
        if (pulseMode.getValue()) {
            pulseTick++;
            int limit = pulseOn ? pulseOnTicks.getValueInt() : pulseOffTicks.getValueInt();
            if (pulseTick >= limit) {
                pulseOn   = !pulseOn;
                pulseTick = 0;
            }
        } else {
            pulseOn = true;
        }

        // Jitter update
        tickJitter = jitter.getValue()
                ? (rng.nextDouble() * 2 - 1) * jitterAmount.getValue()
                : 0.0;
    }

    // ── Public API for MixinEntity ─────────────────────────────────────────────

    /** X/Z expansion to apply this tick for the given entity. Returns 0 = skip. */
    public double getExpansionFor(Entity entity) {
        if (mc.player == null) return 0;
        if (entity == mc.player) return 0;
        if (playersOnly.getValue() && !(entity instanceof PlayerEntity)) return 0;
        if (!pulseOn) return 0;

        double base = resolveBaseExpand();
        if (base <= 0) return 0;

        // Mode safety cap
        base = Math.min(base, modeCap());
        // Jitter
        base = Math.max(0, base + tickJitter);

        // Distance falloff — keeps effective reach delta within vanilla range (Verus/Grim)
        if (distanceFalloff.getValue()) {
            double dist  = entity.distanceTo(mc.player);
            double start = falloffStart.getValue();
            if (dist < start) {
                double scale = 0.30 + 0.70 * (dist / start);
                base *= scale;
            }
        }

        return base;
    }

    /** Y expansion to apply this tick for the given entity. */
    public double getYExpansionFor(Entity entity) {
        double xz = getExpansionFor(entity);
        if (xz <= 0) return 0;
        return switch (mode.getMode()) {
            case Legit   -> 0;
            case Subtle  -> xz * 0.30;
            case Normal  -> xz * 0.60;
            case Blatant -> xz;
        };
    }

    private double resolveBaseExpand() {
        Item held = mc.player.getMainHandStack().getItem();
        if (held instanceof SwordItem)   return swordExpand.getValue();
        if (held instanceof AxeItem)     return axeExpand.getValue();
        if (held instanceof MaceItem)    return maceExpand.getValue();
        if (held instanceof TridentItem) return tridentExpand.getValue();
        if (onlyWeapon.getValue())       return 0;
        return expandAmount.getValue();
    }

    private double modeCap() {
        return switch (mode.getMode()) {
            case Legit   -> 0.06;
            case Subtle  -> 0.12;
            case Normal  -> 0.22;
            case Blatant -> 0.50;
        };
    }

    public boolean isBlatant() {
        return mode.isMode(ExpandMode.Blatant);
    }
}
