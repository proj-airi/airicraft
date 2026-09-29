import type { WorldDefinition } from 'cortico/world.ts';
import { AIRICRAFT_DEFAULTS, type AiricraftConfigSection } from './config.ts';
import { AiricraftWorld } from './world.ts';

export const AIRICRAFT: WorldDefinition<AiricraftConfigSection> = {
  id: 'airicraft',
  label: 'Minecraft (airicraft)',
  defaults: () => ({ ...AIRICRAFT_DEFAULTS, excludeTools: [...AIRICRAFT_DEFAULTS.excludeTools] }),
  // `ctx.cfg` is a live reference to `worlds.airicraft`.
  create: (ctx) => new AiricraftWorld({ cfg: ctx.cfg, timezone: ctx.timezone }),
};
