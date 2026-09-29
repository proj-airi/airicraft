// Bundled salience rules. Java sensors hand over honest candidates: things the player could actually perceive,
// each once, as plain records (no live world access, so no X-ray). This module decides which of them matter now
// and turns those into percepts; every other candidate is dropped with a reason.
//
// input:  {tick, seed, context: {objective, constraints, wanted: [itemIds], inventory: {itemId: count},
//          activeJobType, activeJobTargets: [ids], idle}, candidates: [{id, kind, ...evidence}]}
// output: {percepts: [{type, payload, candidateIds}], drops: [{candidateId, reason}], state}
//
// Candidate kinds: block {blockId, x, y, z, distance, direction, exposedFaces, dimension}; item {itemId, count,
// x, y, z, distance, age, attribution, offered, itemEntityUuid}; entity {entityType, uuid, name, named, tamed, baby,
// hostile, reflexTracked, distance, direction, x, y, z, equipment}; entity_lost {entityType, uuid, name};
// environment {change, ...}.
(lib => {
  // Blocks the host scans for. Keep this short: every entry costs raycasts.
  const NOTABLE_BLOCKS = [
    'minecraft:diamond_ore', 'minecraft:deepslate_diamond_ore', 'minecraft:emerald_ore', 'minecraft:deepslate_emerald_ore',
    'minecraft:ancient_debris', 'minecraft:spawner', 'minecraft:trial_spawner', 'minecraft:chest', 'minecraft:trapped_chest',
    'minecraft:nether_portal', 'minecraft:end_portal_frame'
  ];
  // Common garbage stops being garbage when the goal, a job or an inventory shortage wants it (context.wanted).
  const GARBAGE = new Set([
    'minecraft:dirt', 'minecraft:coarse_dirt', 'minecraft:cobblestone', 'minecraft:cobbled_deepslate', 'minecraft:netherrack',
    'minecraft:gravel', 'minecraft:sand', 'minecraft:red_sand', 'minecraft:stone', 'minecraft:andesite', 'minecraft:diorite',
    'minecraft:granite', 'minecraft:tuff', 'minecraft:deepslate', 'minecraft:calcite', 'minecraft:rotten_flesh',
    'minecraft:wheat_seeds', 'minecraft:beetroot_seeds', 'minecraft:pumpkin_seeds', 'minecraft:melon_seeds',
    'minecraft:poisonous_potato', 'minecraft:spider_eye'
  ]);
  const VILLAGERS = new Set(['minecraft:villager', 'minecraft:wandering_trader']);
  // Food and breeding animals, noticed only when their products are wanted or the goal names them.
  const FOOD_SOURCES = {
    'minecraft:cow': ['minecraft:beef', 'minecraft:leather', 'minecraft:milk_bucket'],
    'minecraft:pig': ['minecraft:porkchop'],
    'minecraft:sheep': ['minecraft:mutton', 'minecraft:white_wool'],
    'minecraft:chicken': ['minecraft:chicken', 'minecraft:egg', 'minecraft:feather'],
    'minecraft:rabbit': ['minecraft:rabbit', 'minecraft:rabbit_hide']
  };
  const VEIN_TICKS = 1200;           // a same-type block this close to a recent percept is the same vein
  const VEIN_RADIUS = 2.5;
  const MAX_VEINS = 24;
  // Notice budgets: a per-key cooldown and an hourly cap per category (Cortico-style).
  const BUDGET = {
    block: {cooldown: 0, hourly: 30},
    item: {cooldown: 200, hourly: 40},
    entity: {cooldown: 600, hourly: 40},
    player: {cooldown: 1200, hourly: 40},
    environment: {cooldown: 1200, hourly: 20}
  };

  const path = id => String(id || '').replace(/^minecraft:/, '').replace(/_/g, ' ');
  const mentioned = (context, id) => {
    const text = `${context.objective || ''} ${context.constraints || ''}`.toLowerCase();
    return text.length > 1 && path(id).length > 0 && text.includes(path(id));
  };
  const wanted = (context, id) => (context.wanted || []).includes(id) || mentioned(context, id);
  const owns = (context, id) => context.activeJobType != null && (context.activeJobTargets || []).includes(id);

  function budgeted(state, category, key, tick) {
    const budget = BUDGET[category];
    state.cooldowns = state.cooldowns || {};
    state.caps = state.caps || {};
    const cooldownKey = `${category}:${key}`;
    if (budget.cooldown > 0 && tick - (state.cooldowns[cooldownKey] ?? -Infinity) < budget.cooldown) return 'cooldown';
    if (!lib.hourlyCap(state.caps, category, tick, budget.hourly)) return 'hourly_cap';
    if (budget.cooldown > 0) state.cooldowns[cooldownKey] = tick;
    return null;
  }

  // Keep the state small: forget cooldowns older than the longest one.
  function prune(state, tick) {
    for (const [key, at] of Object.entries(state.cooldowns || {})) if (tick - at >= 1200) delete state.cooldowns[key];
    state.veins = (state.veins || []).filter(vein => tick - vein.tick < VEIN_TICKS).slice(-MAX_VEINS);
  }

  function sameVein(state, block, tick) {
    return (state.veins || []).some(vein => vein.blockId === block.blockId && tick - vein.tick < VEIN_TICKS
      && Math.hypot(vein.x - block.x, vein.y - block.y, vein.z - block.z) <= VEIN_RADIUS);
  }

  // Adjacent same-type blocks in this step form one vein (connected within one block, diagonals included).
  function veins(blocks) {
    const groups = [];
    const seen = new Set();
    for (let start = 0; start < blocks.length; start++) {
      if (seen.has(start)) continue;
      const group = [];
      const queue = [start];
      seen.add(start);
      while (queue.length) {
        const index = queue.shift();
        group.push(blocks[index]);
        for (let other = 0; other < blocks.length; other++) {
          if (seen.has(other) || blocks[other].blockId !== blocks[index].blockId) continue;
          const a = blocks[index], b = blocks[other];
          if (Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y), Math.abs(a.z - b.z)) <= 1) {
            seen.add(other);
            queue.push(other);
          }
        }
      }
      groups.push(group);
    }
    return groups;
  }

  function blockPercepts(blocks, input, state, drops, percepts) {
    const open = [];
    for (const block of blocks) {
      if (owns(input.context, block.blockId)) drops.push({candidateId: block.id, reason: 'owned_by_active_job'});
      else if (sameVein(state, block, input.tick)) drops.push({candidateId: block.id, reason: 'same_vein'});
      else open.push(block);
    }
    for (const group of veins(open)) {
      const nearest = group.reduce((best, block) => block.distance < best.distance ? block : best, group[0]);
      const over = budgeted(state, 'block', nearest.blockId, input.tick);
      if (over) {
        for (const block of group) drops.push({candidateId: block.id, reason: over});
        continue;
      }
      for (const block of group) state.veins.push({blockId: block.blockId, x: block.x, y: block.y, z: block.z, tick: input.tick});
      percepts.push({
        type: 'perception.block_noticed',
        candidateIds: group.map(block => block.id),
        payload: {
          blockId: nearest.blockId, count: group.length, dimension: nearest.dimension,
          nearest: {x: nearest.x, y: nearest.y, z: nearest.z}, distance: nearest.distance, direction: nearest.direction,
          exposedFaces: nearest.exposedFaces, positions: group.slice(0, 8).map(block => ({x: block.x, y: block.y, z: block.z})),
          evidence: 'line_of_sight_to_exposed_face'
        }
      });
    }
  }

  function itemPercept(item, input, state) {
    const context = input.context;
    if (item.offered) return {drop: 'offer_percept'};
    if (item.attribution === 'own_mining_drop' || owns(context, item.itemId)) return {drop: 'owned_by_active_job'};
    if (GARBAGE.has(item.itemId) && !wanted(context, item.itemId)) return {drop: 'garbage'};
    const over = budgeted(state, 'item', item.itemId, input.tick);
    if (over) return {drop: over};
    return {percept: {type: 'perception.item_noticed', candidateIds: [item.id], payload: {
      itemId: item.itemId, count: item.count, position: {x: item.x, y: item.y, z: item.z}, distance: item.distance,
      direction: item.direction, attribution: item.attribution, itemEntityUuid: item.itemEntityUuid,
      wanted: wanted(context, item.itemId)
    }}};
  }

  function entityCategory(entity, context) {
    if (entity.hostile) return null;
    if (entity.entityType === 'minecraft:player') return 'player';
    if (VILLAGERS.has(entity.entityType)) return 'villager';
    if (entity.named || entity.tamed) return 'named_or_tamed';
    const products = FOOD_SOURCES[entity.entityType];
    if (products && (products.some(item => wanted(context, item)) || mentioned(context, entity.entityType)
      || /\b(breed|food|hunt|farm)\b/.test(`${context.objective || ''}`.toLowerCase()))) return 'food_source';
    return null;
  }

  function entityPercept(entity, input, state) {
    if (entity.hostile) return {drop: entity.reflexTracked ? 'reflex_owns_threat' : 'hostile_left_to_reflex'};
    const category = entityCategory(entity, input.context);
    if (category == null) return {drop: 'common_entity'};
    const over = budgeted(state, category === 'player' ? 'player' : 'entity',
      category === 'player' ? entity.uuid : entity.entityType, input.tick);
    if (over) return {drop: over};
    state.tracked = (state.tracked || []).filter(uuid => uuid !== entity.uuid).concat([entity.uuid]).slice(-32);
    return {percept: {type: 'perception.entity_noticed', candidateIds: [entity.id], payload: {
      entityType: entity.entityType, category, uuid: entity.uuid, name: entity.name, named: entity.named,
      tamed: entity.tamed, baby: entity.baby, distance: entity.distance, direction: entity.direction,
      position: {x: entity.x, y: entity.y, z: entity.z}, equipment: entity.equipment
    }}};
  }

  function lostPercept(lost, state) {
    if (!(state.tracked || []).includes(lost.uuid)) return {drop: 'not_tracked'};
    state.tracked = state.tracked.filter(uuid => uuid !== lost.uuid);
    return {percept: {type: 'perception.entity_lost', candidateIds: [lost.id], payload: {
      entityType: lost.entityType, uuid: lost.uuid, name: lost.name
    }}};
  }

  function environmentPercept(change, input, state) {
    const over = budgeted(state, 'environment', change.change, input.tick);
    if (over) return {drop: over};
    const payload = Object.assign({}, change);
    delete payload.id;
    delete payload.kind;
    return {percept: {type: 'perception.environment_changed', candidateIds: [change.id], payload}};
  }

  return {
    interests: {blocks: NOTABLE_BLOCKS},
    step(input, state) {
      state = state || {};
      state.veins = state.veins || [];
      prune(state, input.tick);
      const percepts = [];
      const drops = [];
      const blocks = [];
      for (const candidate of input.candidates) {
        let result = null;
        switch (candidate.kind) {
          case 'block': blocks.push(candidate); continue;
          case 'item': result = itemPercept(candidate, input, state); break;
          case 'entity': result = entityPercept(candidate, input, state); break;
          case 'entity_lost': result = lostPercept(candidate, state); break;
          case 'environment': result = environmentPercept(candidate, input, state); break;
          default: result = {drop: 'unknown_kind'};
        }
        if (result.percept) percepts.push(result.percept);
        else drops.push({candidateId: candidate.id, reason: result.drop});
      }
      blockPercepts(blocks, input, state, drops, percepts);
      return {percepts, drops, state};
    }
  };
})
