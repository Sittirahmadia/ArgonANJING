package dev.lvstrng.argon.module.modules.misc;

import dev.lvstrng.argon.event.events.AttackListener;
import dev.lvstrng.argon.event.events.TickListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.ModeSetting;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.utils.EncryptedString;
import dev.lvstrng.argon.utils.InventoryUtils;
import dev.lvstrng.argon.utils.TimerUtils;
import dev.lvstrng.argon.utils.WorldUtils;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class WebBurn extends Module implements TickListener, AttackListener {

    public enum ActivationMode { Auto, OnHit }

    private final ModeSetting<ActivationMode> activation = new ModeSetting<>(
            EncryptedString.of("Activation"), ActivationMode.OnHit, ActivationMode.class);

    private final NumberSetting range = new NumberSetting(
            EncryptedString.of("Range"), 1.0, 8.0, 4.0, 0.5);

    private final NumberSetting webOffsetY = new NumberSetting(
            EncryptedString.of("Web Offset Y"), 0.0, 3.0, 0.0, 1.0)
            .setDescription(EncryptedString.of("Y offset above target feet to place cobweb"));

    private final NumberSetting lavaOffsetY = new NumberSetting(
            EncryptedString.of("Lava Offset Y"), 1.0, 4.0, 1.0, 1.0)
            .setDescription(EncryptedString.of("Additional Y blocks above cobweb to place lava"));

    private final NumberSetting lavaDelay = new NumberSetting(
            EncryptedString.of("Lava Delay"), 0.0, 1000.0, 50.0, 25.0)
            .setDescription(EncryptedString.of("Milliseconds after web placement before placing lava"));

    private final BooleanSetting autoRemove = new BooleanSetting(
            EncryptedString.of("Auto Remove"), true)
            .setDescription(EncryptedString.of("Automatically collect the lava after remove delay"));

    private final NumberSetting removeDelay = new NumberSetting(
            EncryptedString.of("Remove Delay"), 0.0, 3000.0, 600.0, 50.0)
            .setDescription(EncryptedString.of("Milliseconds before collecting the lava"));

    private final BooleanSetting checkAir = new BooleanSetting(
            EncryptedString.of("Check Air"), true)
            .setDescription(EncryptedString.of("Only place if target positions are air blocks"));

    private final NumberSetting cooldown = new NumberSetting(
            EncryptedString.of("Cooldown"), 0.0, 5000.0, 1000.0, 100.0);

    private final TimerUtils cooldownTimer = new TimerUtils();
    private final TimerUtils lavaTimer     = new TimerUtils();
    private final TimerUtils removeTimer   = new TimerUtils();

    private BlockPos pendingLava   = null;
    private BlockPos pendingRemove = null;

    public WebBurn() {
        super(EncryptedString.of("Web Burn"),
                EncryptedString.of("Places a cobweb on the enemy then lava above to burn them"),
                -1, Category.MISC);
        addSettings(activation, range, webOffsetY, lavaOffsetY,
                lavaDelay, autoRemove, removeDelay, checkAir, cooldown);
    }

    @Override
    public void onEnable() {
        eventManager.add(TickListener.class, this);
        eventManager.add(AttackListener.class, this);
        reset();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        eventManager.remove(TickListener.class, this);
        eventManager.remove(AttackListener.class, this);
        reset();
        super.onDisable();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;

        // Step 2: place lava after delay
        if (pendingLava != null && lavaTimer.hasReached(lavaDelay.getValue())) {
            placeLava(pendingLava);
            if (autoRemove.getValue()) {
                pendingRemove = pendingLava;
                removeTimer.reset();
            }
            pendingLava = null;
        }

        // Step 3: collect lava after remove delay
        if (pendingRemove != null && removeTimer.hasReached(removeDelay.getValue())) {
            if (mc.world.getBlockState(pendingRemove).isOf(Blocks.LAVA)) {
                InventoryUtils.selectItemFromHotbar(Items.BUCKET);
                BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pendingRemove), Direction.UP, pendingRemove, false);
                ActionResult r = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                if (r.isAccepted() && r.shouldSwingHand()) mc.player.swingHand(Hand.MAIN_HAND);
            }
            pendingRemove = null;
        }

        if (activation.isMode(ActivationMode.Auto)) execute();
    }

    @Override
    public void onAttack(AttackListener.AttackEvent event) {
        if (activation.isMode(ActivationMode.OnHit)) execute();
    }

    private void execute() {
        if (!cooldownTimer.hasReached(cooldown.getValue())) return;
        if (pendingLava != null || pendingRemove != null) return; // sequence in progress

        if (!InventoryUtils.hasItemInHotbar(i -> i == Items.COBWEB)) return;
        if (!InventoryUtils.hasItemInHotbar(i -> i == Items.LAVA_BUCKET)) return;

        PlayerEntity target = WorldUtils.findNearestPlayer(mc.player, range.getValueFloat(), true, true);
        if (target == null) return;

        BlockPos webPos  = target.getBlockPos().up((int) webOffsetY.getValue());
        BlockPos lavaPos = webPos.up((int) lavaOffsetY.getValue());

        if (checkAir.getValue()) {
            if (!mc.world.getBlockState(webPos).isAir()) return;
            if (!mc.world.getBlockState(lavaPos).isAir()) return;
        }

        // Step 1: place cobweb
        InventoryUtils.selectItemFromHotbar(Items.COBWEB);
        BlockHitResult webHit = new BlockHitResult(Vec3d.ofCenter(webPos), Direction.UP, webPos, false);
        ActionResult webResult = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, webHit);
        if (webResult.isAccepted() && webResult.shouldSwingHand()) mc.player.swingHand(Hand.MAIN_HAND);

        // Schedule lava placement
        pendingLava = lavaPos;
        lavaTimer.reset();
        cooldownTimer.reset();
    }

    private void placeLava(BlockPos pos) {
        if (!InventoryUtils.selectItemFromHotbar(Items.LAVA_BUCKET)) return;
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        if (result.isAccepted() && result.shouldSwingHand()) mc.player.swingHand(Hand.MAIN_HAND);
    }

    private void reset() {
        pendingLava   = null;
        pendingRemove = null;
    }
}
