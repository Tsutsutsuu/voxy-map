# GPU Cube Spike (standalone test mod)

This is a separate Fabric 26.2 test mod, built from the voxy-map Stonecutter project but packaged as its own jar. It does not depend on voxy-map classes.

## Build

```powershell
cd stonecutter
.\gradlew.bat :26.2-fabric:gpuSpikeJar --no-daemon
```

Output:

```text
stonecutter/versions/26.2-fabric/build/libs/gpu-spike.jar
```

## Install

Copy `gpu-spike.jar` into the 26.2 Fabric profile `mods/` folder and restart the game.

## Use

- Press `G` in game to open the fullscreen test UI.
- Drag with left mouse to rotate the cube.
- Scroll to change distance.
- `W/A/S/D` move the cube on X/Z, `Q/E` move on Y.
- `G` or `Esc` closes.

The cube is rendered in the world render pass with:

```text
RenderPipeline DEBUG_FILLED_BOX
StagedVertexBuffer (append -> upload -> execute -> endFrame, every frame)
one RenderPass per frame to the main render target
```

The screen itself is transparent; the cube is rendered by `LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN` while the screen is open. This isolates the GPU path from voxy-map's tile store, CPU rasteriser, meshes and async frame upload.

## What a pass means

If the cube renders continuously while rotating/moving for at least 10 seconds, the 26.2 GPU pipeline and per-frame buffer lifecycle are valid. If it becomes colour blocks after the first frame, the failure is in the buffer lifecycle, not in voxy-map's tile/mesh code.
