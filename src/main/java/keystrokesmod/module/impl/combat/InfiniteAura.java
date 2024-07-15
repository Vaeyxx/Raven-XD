package keystrokesmod.module.impl.combat;

import akka.japi.Pair;
import keystrokesmod.event.ReceivePacketEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.impl.other.anticheats.utils.phys.Vec2;
import keystrokesmod.module.impl.other.anticheats.utils.world.PlayerRotation;
import keystrokesmod.module.impl.player.Blink;
import keystrokesmod.module.impl.world.AntiBot;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.ModeSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.script.classes.Vec3;
import keystrokesmod.utility.MoveUtil;
import keystrokesmod.utility.PacketUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class InfiniteAura extends Module {
    private static final String[] MODES = new String[]{"Silent"};
    private final ModeSetting mode = new ModeSetting("Mode", MODES, 0);
    private final SliderSetting range = new SliderSetting("Range", 10, 6, 30, 1);
    private final SliderSetting attackRange = new SliderSetting("Attack range", 6, 3, 6, 1);
    private final SliderSetting moveSpeedMultiplier = new SliderSetting("Move speed multiplier", 1, 1, 5, 0.1);
    private final ButtonSetting drawRealPosition = new ButtonSetting("Draw real position", true);
    private final ButtonSetting ignoreTeammates = new ButtonSetting("Ignore teammates", false);
    private final ButtonSetting blink = new ButtonSetting("Blink", false);
    private final SliderSetting grimACBadPacketsE = new SliderSetting("GrimAC BadPacketsE", 20, 1, 20, 1, "ticks");

    @Nullable
    private EntityPlayer target = null;
    @Nullable
    private Vec3 pos = null;
    @Nullable
    private Vec3 lastPos = null;
    @Nullable
    private Vec3 motion = null;
    @Nullable
    private Vec2 rot = null;
    @Nullable
    private Vec2 lastRot = null;

    private int positionUpdateTicks = Integer.MAX_VALUE;

    private final Queue<Packet<?>> packetQueue = new ConcurrentLinkedQueue<>();

    public InfiniteAura() {
        super("InfiniteAura", category.combat);
        this.registerSetting(new DescriptionSetting("Hit target from over 6 blocks. (test)"));
        this.registerSetting(mode);
        this.registerSetting(range);
        this.registerSetting(attackRange);
        this.registerSetting(moveSpeedMultiplier);
        this.registerSetting(drawRealPosition);
        this.registerSetting(ignoreTeammates);
        this.registerSetting(blink);
        this.registerSetting(grimACBadPacketsE);
    }

    @Override
    public void onDisable() {
        if (pos != null) {
            mc.thePlayer.setPosition(pos.x, pos.y, pos.z);
        }
        if (motion != null) {
            mc.thePlayer.motionX = motion.x;
            mc.thePlayer.motionY = motion.y;
            mc.thePlayer.motionZ = motion.z;
        }

        target = null;
        pos = null;
        lastPos = null;
        motion = null;
        rot = null;
        lastRot = null;
        positionUpdateTicks = Integer.MAX_VALUE;
        stopBlink();
    }

    @SubscribeEvent
    public void onRender(RenderWorldLastEvent event) {
        if (drawRealPosition.isToggled() && pos != null) {
            Blink.drawBox(pos.toVec3());
        }
    }

    @Override
    public void onUpdate() {
        if (target == null || !mc.theWorld.playerEntities.contains(target)) {
            target = getTarget();
            if (target != null)
                Utils.sendMessage("found target: " + target.getName());
            return;
        }

        if (pos == null || motion == null || rot == null) {
            pos = lastPos = new Vec3(mc.thePlayer);
            motion = new Vec3(mc.thePlayer.motionX, mc.thePlayer.motionY, mc.thePlayer.motionZ);
            rot = lastRot = new Vec2(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
        }

        final Vec3 targetPos = new Vec3(target);
        final double distance = pos.distanceTo(targetPos);

        // 判定是否到达目标
        double maxTickMotion = MoveUtil.getAllowedHorizontalDistance();
        if (moveSpeedMultiplier.getInput() != 1) {
            maxTickMotion *= moveSpeedMultiplier.getInput();
        }
        if (distance < (target == mc.thePlayer ? Math.sqrt(maxTickMotion * maxTickMotion) : attackRange.getInput())) {
            if (target == mc.thePlayer) {
                // 完成一个攻击循环
                target = null;
                pos = null;
                motion = null;
                rot = null;
                stopBlink();
            } else {
                rot = new Vec2(PlayerRotation.getYaw(targetPos), PlayerRotation.getPitch(targetPos));
                Utils.attackEntity(target, true);
                Utils.sendMessage("attack");
                target = mc.thePlayer;
            }
            return;
        }

        final double deltaX = targetPos.x - pos.x;
        final double deltaZ = targetPos.z - pos.z;
        // 向目标移动
        if (deltaX > maxTickMotion) {
            rot.x = -90;
            motion.x += maxTickMotion;
        } else if (deltaX < -maxTickMotion) {
            rot.x = 90;
            motion.x -= maxTickMotion;
        } else if (deltaZ > maxTickMotion) {
            rot.x = 0;
            motion.z += maxTickMotion;
        } else if (deltaZ < -maxTickMotion) {
            rot.x = 180;
            motion.z -= maxTickMotion;
        }

        // 从运动转换坐标
        pos.x += motion.x;
        pos.z += motion.z;
        motion.x *= 0.5;
        motion.z *= 0.5;

        if (motion.x < 0.005) motion.x = 0;
        if (motion.z < 0.005) motion.z = 0;

        // 发包
        updatePosition();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onSendPacket(@NotNull SendPacketEvent event) {
        if (target != null && event.getPacket() instanceof C03PacketPlayer) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onReceivePacket(ReceivePacketEvent event) {
        if (pos != null && lastPos != null && motion != null && rot != null && lastRot != null)
            if (event.getPacket() instanceof S12PacketEntityVelocity) {
                event.setCanceled(true);

                motion.x += ((S12PacketEntityVelocity) event.getPacket()).getMotionX() / 8000.0;
                motion.y += ((S12PacketEntityVelocity) event.getPacket()).getMotionY() / 8000.0;
                motion.z += ((S12PacketEntityVelocity) event.getPacket()).getMotionZ() / 8000.0;
                event.setCanceled(true);
            } else if (event.getPacket() instanceof S08PacketPlayerPosLook) {
                event.setCanceled(true);

                pos.x = ((S08PacketPlayerPosLook) event.getPacket()).getX();
                pos.y = ((S08PacketPlayerPosLook) event.getPacket()).getY();
                pos.z = ((S08PacketPlayerPosLook) event.getPacket()).getZ();
                rot.x = ((S08PacketPlayerPosLook) event.getPacket()).getYaw();
                rot.y = ((S08PacketPlayerPosLook) event.getPacket()).getPitch();
                motion.x = motion.y = motion.z = 0;
                event.setCanceled(true);
            }
    }

    /**
     * @see EntityPlayerSP#onUpdateWalkingPlayer()
     */
    public void updatePosition() {
        assert pos != null;
        assert lastPos != null;
        assert rot != null;
        assert lastRot != null;

        double d0 = this.pos.x - this.lastPos.x;
        double d1 = this.pos.y - this.lastPos.y;
        double d2 = this.pos.z - this.lastPos.z;
        double d3 = this.rot.x - this.lastRot.x;
        double d4 = this.rot.y - this.lastRot.y;
        boolean flag2 = d0 * d0 + d1 * d1 + d2 * d2 > 9.0E-4 || this.positionUpdateTicks >= grimACBadPacketsE.getInput();
        boolean flag3 = d3 != 0.0 || d4 != 0.0;
        if (flag2 && flag3) {
            sendPacket(new C03PacketPlayer.C06PacketPlayerPosLook(pos.x, pos.y, pos.z, rot.x, rot.y, true));
        } else if (flag2) {
            sendPacket(new C03PacketPlayer.C04PacketPlayerPosition(pos.x, pos.y, pos.z, true));
        } else if (flag3) {
            sendPacket(new C03PacketPlayer.C05PacketPlayerLook(rot.x, rot.y, true));
        } else {
            sendPacket(new C03PacketPlayer(true));
        }

        ++this.positionUpdateTicks;
        if (flag2) {
            this.lastPos.x = this.pos.x;
            this.lastPos.y = this.pos.y;
            this.lastPos.z = this.pos.z;
            this.positionUpdateTicks = 0;
        }

        if (flag3) {
            this.lastRot.x = this.rot.x;
            this.lastRot.y = this.rot.y;
        }
    }

    private void sendPacket(Packet<?> packet) {
        if (blink.isToggled()) {
            packetQueue.add(packet);
        } else {
            stopBlink();
            PacketUtils.sendPacketNoEvent(packet);
        }
    }

    private void stopBlink() {
        try {
            while (!packetQueue.isEmpty()) {
                Packet<?> packet = packetQueue.remove();
                if (packet == null) continue;

                PacketUtils.sendPacketNoEvent(packet);
            }
        } catch (Exception ignored) {
        }
        packetQueue.clear();
    }


    private EntityPlayer getTarget() {
        return mc.theWorld.playerEntities.stream()
                .filter(p -> !p.equals(mc.thePlayer))
                .filter(p -> !ignoreTeammates.isToggled() || !Utils.isTeamMate(p))
                .filter(p -> !Utils.isFriended(p))
                .filter(p -> !AntiBot.isBot(p))
                .filter(p -> p.posY == mc.thePlayer.posY)  // 模拟移动太难了
                .map(p -> new Pair<>(p, new Vec3(p).distanceTo(mc.thePlayer)))
                .filter(pair -> pair.second() <= range.getInput())
                .min(Comparator.comparing(Pair::second))
                .map(Pair::first)
                .orElse(null);
    }

    @Override
    public String getInfo() {
        return MODES[(int) mode.getInput()];
    }
}
