#!/usr/bin/env python3
"""One-off scratch world seeds. Requires nbtlib; never modifies template or existing worlds."""
from pathlib import Path
import copy, time, nbtlib
from nbtlib import Compound, String, Int, Byte, Long, Float, List
root=Path(__file__).resolve().parents[2]
base=nbtlib.load(root/'run/saves/a/level.dat')
for name,flat in [('Blueprint-Superflat',True),('Blueprint-Terrain',False)]:
    target=root/'run/saves'/name
    if target.exists():
        print('Keeping existing',target);continue
    data=copy.deepcopy(base);n=data['Data'];n.pop('Player',None)
    n['LevelName']=String(name);n['GameType']=Int(1);n['allowCommands']=Byte(1);n['Difficulty']=Byte(0)
    n['SpawnX']=Int(0);n['SpawnY']=Int(-60 if flat else 90);n['SpawnZ']=Int(0);n['SpawnAngle']=Float(0)
    n['Time']=Long(0);n['DayTime']=Long(6000);n['LastPlayed']=Long(int(time.time()*1000));n['raining']=Byte(0);n['thundering']=Byte(0)
    n['GameRules']['doDaylightCycle']=String('false');n['GameRules']['doMobSpawning']=String('false');n['GameRules']['doWeatherCycle']=String('false')
    n['WorldGenSettings']['seed']=Long(42);n['WorldGenSettings']['generate_features']=Byte(0 if flat else 1)
    if flat:
        n['WorldGenSettings']['dimensions']['minecraft:overworld']['generator']=Compound({
            'type':String('minecraft:flat'),'settings':Compound({
                'biome':String('minecraft:plains'),'features':Byte(0),'lakes':Byte(0),
                'structure_overrides':List[String]([]),
                'layers':List[Compound]([Compound({'block':String(block),'height':Int(height)}) for block,height in [('minecraft:bedrock',1),('minecraft:dirt',2),('minecraft:grass_block',1)]])})})
    target.mkdir(parents=True);data.save(target/'level.dat');print('Created',target)
