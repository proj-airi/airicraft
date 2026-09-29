import type { WorldDefinition } from 'cortico/world.ts';
import { AIRI_STAGE_DEFAULTS, type AiriStageConfigSection } from './config.ts';
import { AiriStageWorld } from './world.ts';

export const AIRI_STAGE: WorldDefinition<AiriStageConfigSection> = {
  id: 'airi',
  label: 'AIRI stage',
  defaults: () => ({ ...AIRI_STAGE_DEFAULTS }),
  create: (ctx) => new AiriStageWorld({ cfg: ctx.cfg, timezone: ctx.timezone, botName: ctx.botName }),
};
