@echo off
setlocal
title Huong dan chay Drone Stack

echo ============================================================
echo  HUONG DAN CHAY DRONE STACK TU A TOI Z
echo ============================================================
echo.
echo 1. Giai nen file zip vao mot thu muc bat ky.
echo    Vi du:
echo    D:\DronePackage
echo.
echo 2. Mo thu muc scripts ben trong:
echo    on-demand-monitoring-system\scripts
echo.
echo 3. Bam dup vao file:
echo    RUN_DRONE_STACK.cmd
echo.
echo 4. Lan dau chay co the mat rat lau vi may se tu kiem tra va tai:
echo    - Java 21 neu chua co
echo    - Node.js LTS neu chua co
echo    - Ubuntu-24.04 trong WSL neu chua co
echo    - PX4, Gazebo va cac goi Python can thiet
echo    - .env mau va khoa telemetry rieng tren may nay
echo    - Docker Desktop va Cognito dev pool neu chay ca UI/backend
echo.
echo 5. Neu Windows hoi quyen Admin, hay bam Yes.
echo.
echo 6. Neu WSL/Ubuntu yeu cau restart may hoac mo lai terminal:
echo    - Restart may neu duoc yeu cau
echo    - Sau do bam lai RUN_DRONE_STACK.cmd
echo.
echo 7. Khi chay thanh cong, he thong se mo:
echo    - Backend API: http://localhost:8080
echo    - Frontend UI: http://localhost:5173/#portal/drone-operator
echo    - Flight Control stream: http://localhost:8090/stream.mjpg
echo    - Cua so dieu khien weather
echo.
echo 8. Neu chi muon mo PX4/Gazebo va bo dieu khien, chay:
echo    RUN_DRONE_SIM_ONLY.cmd
echo.
echo 9. Neu chi muon build/kiem tra ma chua mo drone:
echo    RUN_DRONE_STACK.cmd -SkipDrone
echo.
echo 10. Neu da setup xong va muon chay nhanh hon lan sau:
echo    RUN_DRONE_STACK.cmd -SkipBootstrap
echo.
echo 11. Neu muon dong goi lai thanh file zip moi de gui nguoi khac:
echo     PACKAGE_DRONE_STACK.cmd
echo.
echo ============================================================
echo  TROUBLESHOOTING
echo ============================================================
echo.
echo - Loi thieu winget:
echo   Hay cai Java 21 va Node.js LTS thu cong, roi chay lai RUN_DRONE_STACK.cmd.
echo.
echo - Loi WSL:
echo   Mo PowerShell bang Run as Administrator va chay:
echo   wsl --install -d Ubuntu-24.04
echo   Sau do restart may neu Windows yeu cau.
echo.
echo - Loi port da duoc su dung:
echo   Tat cac cua so backend/frontend/drone cu, roi chay lai RUN_DRONE_STACK.cmd.
echo.
echo - Loi do internet:
echo   Kiem tra mang roi chay lai. Lan dau can internet de tai dependency.
echo.
echo ============================================================
echo  BAM PHIM BAT KY DE CHAY DRONE STACK NGAY
echo  Hoac dong cua so nay neu chi muon doc huong dan.
echo ============================================================
pause

call "%~dp0RUN_DRONE_STACK.cmd"
endlocal
