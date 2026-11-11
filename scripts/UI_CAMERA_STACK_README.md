# OMSS Flight Demo Stack

Run this package to open the OMSS operator UI, backend API, PX4, and Gazebo in the fast demo profile.

## Start

From `on-demand-monitoring-system`:

```cmd
scripts\RUN_DRONE_STACK.cmd
```

`scripts\start-ui-camera-stack.cmd` runs the same full demo directly.

The default launcher opens:

- `BE - OMSS API`
- `FE - OMSS UI`
- the operator UI in your browser

The frontend connects to the local flight-control service at `http://localhost:8090` for telemetry, camera video, and flight commands.

To open only the API and UI without PX4/Gazebo, pass `-SkipDrone`:

```cmd
scripts\RUN_DRONE_STACK.cmd -SkipDrone
```

Use `-RealFlight` to run with the complete compact scene and realistic flight speeds.

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

The default demo uses the compact fast-demo scene. Its Gazebo world keeps the
same compact world name and coordinates as the full scene:

```text
SIM_WORLD=compact
FOREST3D_WEB_ONLY=1
world=forest_monitoring_compact
camera=downward compact camera topic
```

The default fast-demo world replaces only the heavy terrain collision with a
flat surface and reuses the tracked compact terrain mesh for visuals. `-RealFlight`
uses the full compact terrain collision.

Do not use `-SimWorld legacy` for this demo.

## Faster Restart

After dependencies are installed once, the full demo can skip its bootstrap:

```cmd
scripts\RUN_DRONE_STACK.cmd -SkipBootstrap
```

If Backend or Frontend is already running:

```cmd
scripts\start-ui-camera-stack.cmd -SkipBackend
scripts\start-ui-camera-stack.cmd -SkipFrontend
scripts\start-ui-camera-stack.cmd -SkipBackend -SkipFrontend
```
