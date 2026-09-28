// Host kernel for attention and salience rule modules. It is not a rule: it loads the bundled library and one
// module, and makes each step deterministic. Time comes only from input.tick and randomness from input.seed.
(() => {
  let rule, lib;
  const NativeDate = Date;
  return {
    load(libFactory, ruleFactory) {
      lib = libFactory();
      rule = ruleFactory(lib);
      if (!rule || typeof rule.step !== 'function') throw Error('A rule module must return {step(input, state, lib)}');
    },
    run(inputJson, stateJson) {
      const input = JSON.parse(inputJson);
      const fixedNow = input.tick * 50;
      Date = class extends NativeDate {
        constructor(...args) { super(...(args.length ? args : [fixedNow])); }
        static now() { return fixedNow; }
      };
      Math.random = lib.seededRandom(input.seed);
      const out = rule.step(input, stateJson ? JSON.parse(stateJson) : {}, lib);
      if (!out || typeof out !== 'object') throw Error('step must return {decisions, state}');
      return JSON.stringify({decisions: out.decisions || [], state: out.state === undefined ? {} : out.state});
    }
  };
})()
