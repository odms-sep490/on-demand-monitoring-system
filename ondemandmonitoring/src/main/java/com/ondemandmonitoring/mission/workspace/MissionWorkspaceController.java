package com.ondemandmonitoring.mission.workspace;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/missions/{missionId}/workspace-version")
public class MissionWorkspaceController {
    private final MissionWorkspaceVersionService versions;

    @GetMapping
    public ResponseEntity<WorkspaceVersion> get(@PathVariable String missionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(versions.get(missionId));
    }
}
