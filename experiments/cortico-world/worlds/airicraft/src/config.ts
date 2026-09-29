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
   * airicraft tools that are not exposed. The persona speaks through its own tools, so the embedded
   * planner's `say` and `report_to_me` are hidden by default.
   */
  excludeTools: string[];
}

export const AIRICRAFT_DEFAULTS: AiricraftConfigSection = {
  enabled: false,
  bridgeStateFile: '',
  pollIntervalMs: 300,
  toolTimeoutMs: 120_000,
  toolPrefix: 'ac_',
  excludeTools: ['say', 'report_to_me'],
};
