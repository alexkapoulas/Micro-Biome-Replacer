# Micro Biome Replacer

A NeoForge 1.21.1 mod that removes isolated "micro biomes" during world generation, creating cleaner and more visually coherent landscapes.

## What It Does

Micro biomes are small, isolated biome patches that appear as visual noise in the world. This mod detects biome regions smaller than a configurable threshold and seamlessly replaces them with the dominant neighboring biome during world generation.

Because replacements happen during the BIOMES generation stage (before terrain shaping), the terrain, surface blocks, and features all correctly reflect the replacement biome.

## Features

- **Configurable threshold**: Set the minimum biome size in blocks (default: 256 blocks)
- **Biome blacklists**: Protect specific biomes from being replaced (e.g., Mushroom Fields, Ice Spikes)
- **Replacement blacklists**: Prevent certain biomes from expanding (e.g., Oceans won't consume small islands)
- **Deterministic**: Same seed always produces the same world
- **C2ME compatible**: Fully supports parallel chunk generation
- **Minimal overhead**: Less than 1% impact on world generation time

## Configuration

Configuration options (in `microbiome.toml`):

| Option | Default | Description |
|--------|---------|-------------|
| `minimumSizeBlocks` | 256 | Biome regions smaller than this are replaced |
| `neverReplace` | Mushroom Fields, Ice Spikes, Deep Dark | Biomes that are never replaced, even if small |
| `neverUseAsReplacement` | All ocean variants | Biomes that won't expand to replace micro biomes |

## Installation

1. Install [NeoForge](https://neoforged.net/) for Minecraft 1.21.1
2. Download the mod JAR from releases
3. Place the JAR in your `mods` folder

## Building from Source

```bash
./gradlew build
```

If you encounter issues, try:
```bash
./gradlew --refresh-dependencies
./gradlew clean build
```

## Testing

The project includes an automated test harness that validates micro biome replacement by running a headless Minecraft server:

```bash
./scripts/test.sh accuracy      # Test that micro biomes are replaced
./scripts/test.sh determinism   # Test that results are reproducible
./scripts/test.sh all           # Run all tests
```

### Requirements

- Python 3.8+
- `mcrcon` binary (included in `scripts/`)
- NeoForge server set up in `test_server/`

### Test Output

Results are written to `test_results/`:
- `report.json` - Structured test results
- `harness.log` - Detailed execution log
- `server_stdout.log` - Minecraft server output

## Additional Resources

- Community Documentation: https://docs.neoforged.net/
- NeoForged Discord: https://discord.neoforged.net/
