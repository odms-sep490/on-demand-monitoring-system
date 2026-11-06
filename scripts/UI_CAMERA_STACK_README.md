# OMSS UI Camera Stack

Run this package to open the OMSS operator UI and backend API. PX4/Gazebo is not started by default.

## Start

From `on-demand-monitoring-system`:

```cmd
scripts\RUN_DRONE_STACK.cmd
```

`scripts\start-ui-camera-stack.cmd` runs the same UI-only default directly.

The default launcher opens:

- `BE - OMSS API`
- `FE - OMSS UI`
- the operator UI in your browser

Connect a live flight-control service at `http://localhost:8090` for telemetry, camera video, and flight commands.
This UI-only mode does not start or arm a drone.

To start the PX4/Gazebo simulation too, pass `-WithSimulation`:

```cmd
scripts\start-ui-camera-stack.cmd -WithSimulation
```

Open the UI:

```text
http://localhost:5173/#portal/drone-operator
```

The script normally opens it automatically.

## Weather Controls (simulation mode)

Use these weather keys in the `CTRL - Flight Control Stream` terminal, or in
the separate `WEATHER - Controls` terminal. Press one key once; no Enter needed:

```text
u = Clear Day
y = Sunset
i = Night
g = Cloudy
j = Foggy
m = Windy
b = Light Rain
z = Heavy Rain
```

Only safe single-letter weather keys are enabled here. The launcher avoids
Flight Control keys like `w/a/s/d/f/q/e/t/c/r/l/x/1/2/3/4/5`.

This changes Gazebo runtime lighting and wind without restarting the drone stack.

## Correct Packaged Map

The package is forced to the compact full map:

```text
SIM_WORLD=compact
FOREST3D_WEB_ONLY=1
world=forest_monitoring_compact
camera=downward compact camera topic
```

Do not use `-SimWorld legacy` for this demo.

## Faster Restart

After dependencies are installed once, simulation mode can skip its bootstrap:

```cmd
scripts\start-ui-camera-stack.cmd -WithSimulation -SkipBootstrap
```

If Backend or Frontend is already running:

```cmd
scripts\start-ui-camera-stack.cmd -SkipBackend
scripts\start-ui-camera-stack.cmd -SkipFrontend
scripts\start-ui-camera-stack.cmd -SkipBackend -SkipFrontend
```
