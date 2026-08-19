# BallPlatformer

A polished Android 2D physics platformer inspired by the feel of classic rolling-ball platform games, built with Java and Android Canvas.

## Current release direction

- 32 main levels with deterministic layouts and increasing difficulty.
- Boss encounters on every eighth level.
- Checkpoints, spikes, springs, moving platforms, enemies and collectible gems.
- Smooth roll/jump physics with coyote time and jump buffering.
- Three persistent ball skins.
- Level unlock progression, lifetime gems and best-score persistence.
- Dedicated Endless Mode that procedurally streams new level segments while the player moves forward.
- Endless score and personal best.
- Particle effects, parallax scenery, gradient lighting and touch-first controls.
- Pause, restart, level select, settings and win flows.
- GitHub Actions debug APK build.

## Android build

The project targets API 36 and uses Android Gradle Plugin 9.3.1 with Gradle 9.5 and Java 17.

CI builds the debug APK on pushes to `main`/agent branches and pull requests.

## Controls

- Left button: roll left
- Right button: roll right
- Up button: jump
- Pause button: pause/restart/select level

## Endless Mode

Endless mode does not preload one giant map. It generates new platform segments as the player advances and removes distant objects, keeping the active world bounded while the run continues.
