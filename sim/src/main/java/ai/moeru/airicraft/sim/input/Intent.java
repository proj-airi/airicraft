package ai.moeru.airicraft.sim.input;

/**
 * One tick of policy intent. Semantic-level only — the executor turns it into
 * legal vanilla inputs. Policies never touch entities directly.
 *
 * @param lookEntityId network id of the entity to face (mutually exclusive with lookPos)
 * @param lookPos      world position [x,y,z] to face
 * @param moveDir      world-space xz direction [x,z], need not be normalized
 * @param attack       press attack this tick (rate-limited + legality-checked)
 * @param useHand      "main" | "off" | null : start using the item in that hand
 * @param stopUsing    stop using the current item
 */
public record Intent(
		Integer lookEntityId,
		double[] lookPos,
		double[] moveDir,
		boolean jump,
		boolean sprint,
		boolean sneak,
		boolean attack,
		String useHand,
		boolean stopUsing) {

	public static final Intent IDLE = builder().build();

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {
		private Integer lookEntityId;
		private double[] lookPos;
		private double[] moveDir;
		private boolean jump;
		private boolean sprint;
		private boolean sneak;
		private boolean attack;
		private String useHand;
		private boolean stopUsing;

		public Builder lookEntity(Integer id) {
			this.lookEntityId = id;
			return this;
		}

		public Builder lookPos(double x, double y, double z) {
			this.lookPos = new double[] {x, y, z};
			return this;
		}

		public Builder moveDir(double x, double z) {
			this.moveDir = new double[] {x, z};
			return this;
		}

		public Builder jump(boolean v) {
			this.jump = v;
			return this;
		}

		public Builder sprint(boolean v) {
			this.sprint = v;
			return this;
		}

		public Builder sneak(boolean v) {
			this.sneak = v;
			return this;
		}

		public Builder attack(boolean v) {
			this.attack = v;
			return this;
		}

		public Builder useHand(String hand) {
			this.useHand = hand;
			return this;
		}

		public Builder stopUsing(boolean v) {
			this.stopUsing = v;
			return this;
		}

		public Intent build() {
			return new Intent(lookEntityId, lookPos, moveDir, jump, sprint, sneak, attack, useHand, stopUsing);
		}
	}
}
