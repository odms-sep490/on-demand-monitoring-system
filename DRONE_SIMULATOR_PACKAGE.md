# Drone Simulator Package

This folder is portable. Do not edit scripts to point at a personal path like
`C:\Users\...`; the launcher resolves the current repo location automatically.

## One-click Run

From the `on-demand-monitoring-system` repository folder, run:

- Full UI + backend + drone simulator: `scripts\RUN_DRONE_STACK.cmd`
- PX4/Gazebo simulator and flight controller only: `scripts\RUN_DRONE_SIM_ONLY.cmd`
- Create a clean zip package: `scripts\PACKAGE_DRONE_STACK.cmd`
- Vietnamese A-to-Z guide: `scripts\HUONG_DAN_CHAY_DRONE_STACK.cmd`

Both launchers use the folder they are stored in as `PROJECT_PATH`, convert it to
WSL format, then start the scripts from that location.

`scripts\RUN_DRONE_STACK.cmd` checks the Windows-side tools too. If Java 21 or
Node.js LTS are missing, it tries to install them with `winget`; if Windows needs
a restart or a new terminal session afterward, run the same file again. It then
builds the Spring Boot backend and React frontend before starting the stack.

The launcher creates a private `.env` from `.env.example` on first run and
generates a local telemetry key. Simulator-only mode skips backend telemetry and
does not need a database or AWS credentials. The full authenticated web stack needs your development Cognito
pool values, AWS credentials with Cognito permissions (or a configured AWS
profile), and a local PostgreSQL server. For Google sign-in, also set the Cognito domain. With the example defaults,
the launcher starts the included PostgreSQL Compose service on port 5433; install
and start Docker Desktop first. Real S3 uploads need your own AWS credentials and
bucket configuration.

## Packaged Assets

The compact simulator package includes:

- `Forest3D/worlds/forest_monitoring_compact.sdf`
- `Forest3D/worlds/forest_monitoring_compact_fast_demo.sdf`
- `Forest3D/models/x500_mono_cam_down`
- `Forest3D/models/compact_terrain`
- `Forest3D/models/compact_terrain_fast_demo`
- `Forest3D/models/compact_water`
- `Forest3D/models/compact_roads`
- `Forest3D/models/compact_bridges`
- `Forest3D/models/compact_home`
- `Forest3D/models/compact_highrise`
- `Forest3D/models/compact_zones`
- `Forest3D/models/compact_forest`
- `Forest3D/models/compact_thermal_sources`
- `Forest3D/models/compact_environment_props`
- `Forest3D/models/compact_mountains`
- `Forest3D/models/compact_airport`
- `ondemandmonitoring/src/main/resources/static/simulation-viewer`

The launch scripts validate these files before starting, so missing map or model
files fail early with a clear message instead of Gazebo `model://...` errors.

## Requirements

- Windows with WSL available. If Ubuntu is missing, the launcher asks WSL to
  install `Ubuntu-24.04`.
- Ubuntu distro name defaults to `Ubuntu-24.04`.
- Internet access on first bootstrap so PX4 and Python dependencies can install.
- PX4 bootstrap pins new installations to `cbc6a6f7abbd06fbb2ff0aed3e57e3e95f88232e`.
- Docker Desktop or a PostgreSQL server when starting the backend UI stack.
- A development Cognito pool when using authenticated backend/UI features.
- Frontend folder `ondemand-monitoring-web` should sit next to this folder when
  using `RUN_DRONE_STACK.cmd`.

## Useful Options

```powershell
.\scripts\RUN_DRONE_STACK.cmd -SkipBootstrap
.\scripts\RUN_DRONE_STACK.cmd -SkipBuild
.\scripts\RUN_DRONE_STACK.cmd -SkipDrone
.\scripts\RUN_DRONE_STACK.cmd -NoBrowser
.\scripts\RUN_DRONE_SIM_ONLY.cmd -WithWeather
.\scripts\PACKAGE_DRONE_STACK.cmd
.\scripts\HUONG_DAN_CHAY_DRONE_STACK.cmd
```
