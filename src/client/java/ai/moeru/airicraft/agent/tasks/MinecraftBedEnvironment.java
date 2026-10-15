package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.social.GameMessageLog;
import ai.moeru.airicraft.control.Priority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Finds, walks to and uses a bed through the foreground leases; reads the server's answers from {@link GameMessageLog}. */
final class MinecraftBedEnvironment implements SleepInBedTaskExecutor.Environment {
	private static final String OWNER = "sleep_in_bed";
	private static final float AIM_DEGREES = 10.0F;
	/** Just under the top of a bed (9/16 of a block high), so a ray or a click lands on the bed itself. */
	private static final double BED_AIM_HEIGHT = 0.45D;
	private final Actuator actuator = new Actuator(OWNER, Priority.FOREGROUND);
	private final MovementController movement = new MovementController(OWNER, Priority.FOREGROUND);
	private final CameraController camera;

	MinecraftBedEnvironment(CameraController camera) {
		this.camera = Objects.requireNonNull(camera, "camera");
	}

	private static Minecraft client() { return Minecraft.getInstance(); }

	@Override
	public SleepInBedTaskExecutor.Body observe() {
		LocalPlayer player = client().player;
		ClientLevel level = client().level;
		if (player == null || level == null) return null;
		return new SleepInBedTaskExecutor.Body(player.position(), player.blockPosition(), player.onGround(), player.getHealth(),
			player.getFoodData().getFoodLevel(), level.getDayTime(), level.isRaining(), level.isThundering(),
			Level.OVERWORLD.equals(level.dimension()), level.dimension().location().toString(), player.isSleeping());
	}

	@Override
	public SleepInBedTaskExecutor.Bed bedAt(BlockPos pos) {
		ClientLevel level = client().level;
		if (level == null || !level.hasChunkAt(pos)) return null;
		return bed(level.getBlockState(pos), pos);
	}

	private static SleepInBedTaskExecutor.Bed bed(BlockState state, BlockPos pos) {
		if (!(state.getBlock() instanceof BedBlock)) return null;
		Direction facing = state.getValue(BedBlock.FACING);
		BlockPos head = state.getValue(BedBlock.PART) == BedPart.HEAD ? pos : pos.relative(facing);
		return new SleepInBedTaskExecutor.Bed(head.immutable(), head.relative(facing.getOpposite()).immutable());
	}

	@Override
	public List<SleepInBedTaskExecutor.Bed> visibleBeds(int radius) {
		LocalPlayer player = client().player;
		ClientLevel level = client().level;
		if (player == null || level == null) return List.of();
		BlockPos origin = player.blockPosition();
		Vec3 eye = player.getEyePosition();
		Set<BlockPos> seenHeads = new HashSet<>();
		List<SleepInBedTaskExecutor.Bed> beds = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					if (!level.hasChunkAt(cursor)) continue;
					BlockState state = level.getBlockState(cursor);
					if (!(state.getBlock() instanceof BedBlock)) continue;
					SleepInBedTaskExecutor.Bed bed = bed(state, cursor.immutable());
					if (bed == null || !seenHeads.add(bed.head())) continue;
					if (visible(level, player, eye, bed)) beds.add(bed);
				}
			}
		}
		return beds;
	}

	/** Whether a ray from the eyes reaches either half of the bed: what the player could see, no X-ray. */
	private static boolean visible(ClientLevel level, LocalPlayer player, Vec3 eye, SleepInBedTaskExecutor.Bed bed) {
		for (BlockPos part : bed.parts()) {
			Vec3 aim = new Vec3(part.getX() + 0.5D, part.getY() + BED_AIM_HEIGHT, part.getZ() + 0.5D);
			BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
			if (hit.getType() == HitResult.Type.BLOCK && bed.parts().contains(hit.getBlockPos())) return true;
		}
		return false;
	}

	@Override
	public boolean standable(BlockPos cell) {
		ClientLevel level = client().level;
		if (level == null || !level.hasChunkAt(cell)) return false;
		BlockPos below = cell.below();
		return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP) && level.getFluidState(below).isEmpty()
			&& open(level, cell) && open(level, cell.above());
	}

	private static boolean open(ClientLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getFluidState(pos).isEmpty();
	}

	@Override
	public List<SleepInBedTaskExecutor.Monster> monstersNear(SleepInBedTaskExecutor.Bed bed) {
		ClientLevel level = client().level;
		if (level == null) return List.of();
		Vec3 head = Vec3.atBottomCenterOf(bed.head());
		// Vanilla counts every Monster; a zombified piglin only when angry at the player, which the client cannot see.
		return level.getEntitiesOfClass(Monster.class, SleepInBedTaskExecutor.restBox(bed.head()),
				monster -> monster.isAlive() && !(monster instanceof ZombifiedPiglin)).stream()
			.map(monster -> new SleepInBedTaskExecutor.Monster(BuiltInRegistries.ENTITY_TYPE.getKey(monster.getType()).getPath(),
				monster.position().distanceTo(head)))
			.toList();
	}

	@Override
	public boolean useBed(SleepInBedTaskExecutor.Bed bed, boolean force) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		if (player == null) return false;
		movement.stop(minecraft);
		Vec3 eye = player.getEyePosition();
		BlockPos target = bed.parts().stream()
			.min(java.util.Comparator.comparingDouble(part -> Vec3.atCenterOf(part).distanceToSqr(eye))).orElse(bed.head());
		Vec3 hit = new Vec3(target.getX() + 0.5D, target.getY() + BED_AIM_HEIGHT, target.getZ() + 0.5D);
		camera.lookAt(minecraft, hit);
		if (!force && !camera.isLookingAt(minecraft, hit, AIM_DEGREES)) return false;
		InteractionResult result = actuator.useItemOn(minecraft, player, InteractionHand.MAIN_HAND,
			new BlockHitResult(hit, Direction.UP, target, false));
		if (result == InteractionResult.FAIL) return false;
		if (result.consumesAction()) player.swing(InteractionHand.MAIN_HAND);
		return true;
	}

	@Override
	public long messageMark() {
		return GameMessageLog.mark();
	}

	@Override
	public List<SleepInBedTaskExecutor.Message> messagesSince(long mark) {
		return GameMessageLog.since(mark).stream()
			.map(entry -> new SleepInBedTaskExecutor.Message(entry.key(), entry.text(), entry.overlay()))
			.toList();
	}

	@Override
	public void leaveBed() {
		actuator.leaveBed(client(), client().player);
	}

	@Override
	public void stop() {
		movement.stop(client());
	}

	@Override
	public void release() {
		stop();
	}
}
