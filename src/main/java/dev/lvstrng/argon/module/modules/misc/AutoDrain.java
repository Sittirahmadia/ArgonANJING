package dev.lvstrng.argon.module.modules.misc;

import dev.lvstrng.argon.event.events.TickListener;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.BooleanSetting;
import dev.lvstrng.argon.module.setting.ModeSetting;
import dev.lvstrng.argon.module.setting.NumberSetting;
import dev.lvstrng.argon.utils.EncryptedString;
import dev.lvstrng.argon.utils.InventoryUtils;
import dev.lvstrng.argon.utils.TimerUtils;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class AutoDrain extends Module implements TickListener {

    public enum LiquidMode { Water, Lava, Both }

    private final ModeSetting<LiquidMode> liquidMode = new ModeSetting<>(
            EncryptedString.of("Liquid"), LiquidMode.Both, LiquidMode.class)
            .setDescription(EncryptedString.of("Which liquid type to drain"));

    private final NumberSetting range = new NumberSetting(
            EncryptedString.of("Range"), 1.0, 8.0, 4.0, 0.5)
            .setDescription(EncryptedString.of("Scan radius around player"));

    private final NumberSetting cooldown = new NumberSetting(
            EncryptedString.of("Cooldown"), 0.0, 5000.0, 500.0, 100.0)
            .setDescription(EncryptedString.of("Milliseconds between drain actions"));

    private final BooleanSetting drainAll = new BooleanSetting(
            EncryptedString.of("Drain All"), false)
            .setDescription(EncryptedString.of("Drain all blocks in range per tick, not just the closest"));

    private final TimerUtils timer = new TimerUtils();

    public AutoDrain() {
        super(EncryptedString.of("Auto Drain"),
                EncryptedString.of("Passively drains nearby liquids using a bucket"),
                -1, Category.MISC);
        addSettings(liquidMode, range, cooldown, drainAll);
    }

    @Override public void onEnable()  { eventManager.add(TickListener.class, this); super.onEnable(); }
    @Override public void onDisable() { eventManager.remove(TickListener.class, this); super.onDisable(); }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;
        if (!timer.hasReached(cooldown.getValue())) return;
        if (!InventoryUtils.hasItemInHotbar(i -> i == Items.BUCKET)) return;

        BlockPos playerPos = mc.player.getBlockPos();
        double r = range.getValue();
        Box box = new Box(playerPos).expand(r);

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        boolean found = false;

        for (BlockPos pos : BlockPos.iterate(
                BlockPos.ofFloored(box.minX, box.minY, box.minZ),
                BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ))) {

            if (!shouldDrain(pos)) continue;

            if (drainAll.getValue()) {
                interactAt(pos.toImmutable());
                found = true;
            } else {
                double dist = pos.getSquaredDistanceFromCenter(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                if (dist < bestDist) { bestDist = dist; best = pos.toImmutable(); }
            }
        }

        if (!drainAll.getValue() && best != null) { interactAt(best); found = true; }
        if (found) timer.reset();
    }

    private void interactAt(BlockPos pos) {
        InventoryUtils.selectItemFromHotbar(Items.BUCKET);
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        if (result.isAccepted() && result.shouldSwingHand()) mc.player.swingHand(Hand.MAIN_HAND);
    }

    private boolean shouldDrain(BlockPos pos) {
        var state = mc.world.getBlockState(pos);
        return switch (liquidMode.getMode()) {
            case Water -> state.isOf(Blocks.WATER);
            case Lava  -> state.isOf(Blocks.LAVA);
            case Both  -> state.isOf(Blocks.WATER) || state.isOf(Blocks.LAVA);
        };
    }
}
