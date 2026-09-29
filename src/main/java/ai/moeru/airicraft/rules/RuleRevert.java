package ai.moeru.airicraft.rules;

/** Where a failing override goes: the policies ask, so the planner's own edits can revert to a previous version. */
@FunctionalInterface
public interface RuleRevert {
	/**
	 * @param failed the module that failed
	 * @param tick the tick of the failing step
	 */
	Result revert(RuleModule failed, long tick);

	/** {@code fromVersion} and {@code toVersion} are planner version numbers; 0 is the base module. */
	record Result(RuleModule to, int fromVersion, int toVersion) {}

	/** The behaviour before planner authorship: any failing override reverts to the bundled module. */
	static RuleRevert toBundled(RuleModule.Hook hook) {
		return (failed, tick) -> new Result(RuleModule.bundled(hook), 0, 0);
	}
}
