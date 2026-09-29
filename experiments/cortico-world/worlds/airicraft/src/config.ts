/** `worlds.airicraft` section of the deployment config. Disabled by default. */
export interface AiricraftConfigSection {
  enabled: boolean;
  /**
   * Bridge discovery file written by the mod. Empty means `AIRICRAFT_BRIDGE_STATE_FILE`, then
   * `~/.airicraft/bridge-state.json`.
   */
  bridgeStateFile: string;
  /** How often the event feed is polled. */
  pollIntervalMs: number;
  /** Upper bound for one tool call through the bridge. */
  toolTimeoutMs: number;
  /** Prefix for tool names; Cortico requires names to be unique across Worlds. */
  toolPrefix: string;
  /**
   * airicraft tools that are not exposed. The persona speaks, holds objectives and decides when it is woken
   * through Cortico, so the embedded planner's own tools for those are hidden by default.
   */
  excludeTools: string[];
}

export const AIRICRAFT_DEFAULTS: AiricraftConfigSection = {
  enabled: false,
  bridgeStateFile: '',
  pollIntervalMs: 300,
  toolTimeoutMs: 120_000,
  toolPrefix: 'ac_',
  excludeTools: [
    'say',
    'report_to_me',
    'update_event_policy',
    'record_decision',
    'set_planner_goal',
    'change_planner_goal',
    'finish_planner_goal',
    'block_planner_goal',
    'resume_planner_goal',
    'inspect_planner_goal',
  ],
};
