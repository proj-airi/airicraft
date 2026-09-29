/** `worlds.airi` section of the deployment config. Disabled by default. */
export interface AiriStageConfigSection {
  enabled: boolean;
  /** Address the stage connects to. Loopback by default: the socket has no authentication. */
  host: string;
  port: number;
}

export const AIRI_STAGE_DEFAULTS: AiriStageConfigSection = {
  enabled: false,
  host: '127.0.0.1',
  port: 6122,
};
