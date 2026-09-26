(() => {
  let rule, lib;
  const NativeDate = Date;
  return {
    load(libFactory, ruleFactory) {
      lib = libFactory();
      rule = ruleFactory(lib);
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
      return JSON.stringify(out);
    }
  };
})()
