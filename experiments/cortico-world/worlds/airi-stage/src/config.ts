/** `worlds.airi` section of the deployment config. Disabled by default. */
export interface AiriStageConfigSection {
  enabled: boolean;
  /** Address the stage connects to. Loopback by default: the socket has no authentication. */
  host: string;
  port: number;
  /**
   * How the "finished speaking" report reaches the persona. `piggyback` (default) only rides along with the next
   * wake; `debounce` batches it into a wake; `flush` wakes the persona immediately. Waking on every playback end can
   * make a chatty persona answer its own speech, so it is opt-in.
   */
  speechEndTrigger: 'piggyback' | 'debounce' | 'flush';
}

export const AIRI_STAGE_DEFAULTS: AiriStageConfigSection = {
  enabled: false,
  host: '127.0.0.1',
  port: 6122,
  speechEndTrigger: 'piggyback',
};
