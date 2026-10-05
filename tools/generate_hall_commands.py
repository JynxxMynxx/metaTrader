"""Validate/export the authoritative user-edited command document; never overwrite it."""
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
text = (root / 'pirateBayConfig' / 'piratebayTradingHallConfigCommandList.md').read_text(encoding='utf-8-sig')
commands = [s.strip() for s in text.splitlines() if s.strip().startswith('.')]
issues = []
trades = set()
coordinate_commands = {'inputItem1Chest', 'inputItem2Chest', 'outputItemChest', 'outputChest'}
meta_positions = {'hallReturn', 'raidReturn', 'ominousBottleChest'}
meta_regions = {'hallBounds', 'nonStackableDumpChestBounds', 'unstackableFinalStorageArea'}
meta_lists = {'stackableShulkerOutputChest', 'shulkerIngredientsChest', 'leftoverEmptyShulkerChest', 'raidFarmTotemOutputChest', 'grindstone', 'bookshelf', 'unstackableShulkerLocation', 'rocketChest'}
destinations = {}
for number, command in enumerate(commands, 1):
    if '<' in command:
        issues.append(f'Command {number}: placeholder remains')
    parts = command.split()
    if parts[:2] == ['.metaTrader', 'set'] and parts[2] in {'hallHeliPad', 'hallFireWorkChest', 'raidFireWorkChest', 'raidHeliPad', 'raidFarmBounds'}:
        issues.append(f'Removed travel command: {command}')
    if parts[0] == '.trader' and parts[1] == 'add':
        if parts[3] in coordinate_commands:
            if parts[2] not in trades or len(parts) != 7:
                issues.append(f'Invalid chest addition: {command}')
        else:
            if len(parts) not in (6, 12, 16):
                issues.append(f'Invalid coordinate-only trade creation: {command}')
            trades.add(parts[2])
    if parts[0] == '.trader' and parts[1] == 'set' and parts[2] not in trades:
        issues.append(f'Setting refers to missing trade: {command}')
    if parts[0] == '.metaTrader' and parts[1] == 'set':
        if parts[2] in meta_positions and len(parts) != 6:
            issues.append(f'Invalid position command: {command}')
        elif parts[2] in meta_regions and len(parts) != 9:
            issues.append(f'Invalid region command: {command}')
        elif 'Container' in parts[2]:
            issues.append(f'Removed container-type setting: {command}')
    if parts[0] == '.metaTrader' and parts[1] == 'add':
        if parts[2] in meta_lists and len(parts) != 6:
            issues.append(f'Invalid coordinate addition: {command}')
        elif parts[2] in {'stackableShulkingInputChest', 'outputShulkerLocation'} and len(parts) != 7:
            issues.append(f'Invalid item/coordinate addition: {command}')
    if 'outputShulkerLocationEnchantedBook' in parts:
        pos = tuple(parts[-3:])
        if pos in destinations and destinations[pos] != parts[3:6]:
            issues.append(f'Conflicting enchanted-book destination {pos}')
        destinations[pos] = parts[3:6]
    if re.search(r'\.metaTrader (?:set|add) (?:finalSortingBounds|shulkersToCraftForNonStackableSort|stackableShulkingStation|stackableSortingBounds|nonStackableStoreChestBounds)', command):
        issues.append(f'Removed old sorting command: {command}')
    if 'outputShulkerLocation' in command and int(parts[-1]) > 0:
        issues.append(f'Positive-Z destination needs confirmation: {command}')
if not any('add stackableShulkerOutputChest ' in c for c in commands):
    issues.append('Missing stackable filled-box collection chest configuration')
book_destinations = {(c.split()[4].removeprefix('minecraft:'), c.split()[5]) for c in commands if c.startswith('.metaTrader add outputShulkerLocationEnchantedBook ')}
for c in commands:
    p = c.split()
    if c.startswith('.trader set ') and p[3:5] == ['outputEnchants', 'add']:
        if (p[5].removeprefix('minecraft:'), p[6]) not in book_destinations:
            issues.append(f'Missing sorting destination for {p[2]}: {p[5]} {p[6]}')
(root / 'pirateBayConfig' / 'piratebayTradingHallConfigCommandList.txt').write_text('\n'.join(commands)+'\n',encoding='utf-8')
print(f'Exported {len(commands)} commands without changing the Markdown document.')
for issue in issues:
    print('CHECK: ' + issue)
if issues:
    raise SystemExit(1)
