package keystrokesmod.module.impl.world;

import keystrokesmod.module.Module;
import keystrokesmod.module.impl.other.SlotHandler;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import org.lwjgl.input.Mouse;

public class AutoTool extends Module {
    private final SliderSetting hoverDelay;
    private final ButtonSetting rightDisable;
    private final ButtonSetting requireMouse;
    private final ButtonSetting swap;
    private final ButtonSetting sneakRequire;
    public int previousSlot = -1;
    public int toolSlot = -1;
    private int ticksHovered;
    private BlockPos currentBlock;
    public AutoTool() {
        super("AutoTool", category.world);
        this.registerSetting(hoverDelay = new SliderSetting("Hover delay", 0.0, 0.0, 20.0, 1.0));
        this.registerSetting(rightDisable = new ButtonSetting("Disable while right click", true));
        this.registerSetting(sneakRequire = new ButtonSetting("Only while crouching", false));
        this.registerSetting(requireMouse = new ButtonSetting("Require mouse down", true));
        this.registerSetting(swap = new ButtonSetting("Swap to previous slot", true));
    }

    public void onDisable() {
        resetVariables();
    }

    public void setSlot(final int currentItem) {
        if (currentItem == -1) {
            return;
        }
        SlotHandler.setCurrentSlot(currentItem);
    }

    public void onUpdate() {
        toolSlot = -1;
        if (!mc.inGameHasFocus || mc.currentScreen != null || (rightDisable.isToggled() && Mouse.isButtonDown(1)) || !mc.thePlayer.capabilities.allowEdit || (sneakRequire.isToggled() && !mc.thePlayer.isSneaking())) {
            resetVariables();
            return;
        }
        if (!Mouse.isButtonDown(0) && requireMouse.isToggled()) {
            resetSlot();
            return;
        }
        MovingObjectPosition over = mc.objectMouseOver;
        if (over == null || over.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || (sneakRequire.isToggled() && !mc.thePlayer.isSneaking())) {
            resetSlot();
            resetVariables();
            return;
        }
        if (over.getBlockPos().equals(currentBlock)) {
            ticksHovered++;
        }
        else {
            ticksHovered = 0;
        }
        currentBlock = over.getBlockPos();
        if (hoverDelay.getInput() == 0 || ticksHovered > hoverDelay.getInput()) {
            int slot = Utils.getTool(BlockUtils.getBlock(currentBlock));
            if (slot == -1) {
                return;
            }
            if (previousSlot == -1) {
                previousSlot = SlotHandler.getCurrentSlot();
            }
            setSlot(slot);
            toolSlot = slot;
        }
    }

    private void resetVariables() {
        ticksHovered = 0;
        resetSlot();
        previousSlot = -1;
        toolSlot = -1;
    }

    private void resetSlot() {
        if (previousSlot == -1 || !swap.isToggled()) {
            return;
        }
        if (mc.thePlayer.inventory.currentItem != toolSlot && toolSlot != -1) {
            previousSlot = -1;
            return;
        }
        setSlot(previousSlot);
        previousSlot = -1;
    }
}
