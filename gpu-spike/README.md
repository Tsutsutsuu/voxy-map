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

The cube is drawn by a real Blaze3D GPU pass:

```text
RenderPipeline DEBUG_FILLED_SNIPPET (no depth, no cull)
StagedVertexBuffer (append -> upload -> execute -> endFrame, every frame)
one RenderPass per frame to the main render target
```

It runs after the complete GUI pass via a tiny `Minecraft#renderFrame` mixin, so it is in front of the fullscreen screen instead of behind the world/UI. The vertex projection is done in screen space on the CPU because this spike is testing GPU buffer/render-pass lifetime, not a full 3D camera pipeline.


## What a pass means

If the cube renders continuously while rotating/moving for at least 10 seconds, the 26.2 GPU pipeline and per-frame buffer lifecycle are valid. If it becomes colour blocks after the first frame, the failure is in the buffer lifecycle, not in voxy-map's tile/mesh code.
