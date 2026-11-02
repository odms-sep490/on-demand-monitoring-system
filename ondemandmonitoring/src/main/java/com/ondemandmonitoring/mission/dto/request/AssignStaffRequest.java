package com.ondemandmonitoring.mission.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignStaffRequest {
    @NotBlank(message = "staffId is required")
    private String staffId;

    @NotEmpty(message = "assignedRoles must contain at least one role")
    @Size(max = 4, message = "assignedRoles must contain at most 4 roles")
    private List<@NotBlank(message = "assignedRoles must not contain blank values") String> assignedRoles;

    /**
     * Kept only so older direct Java callers can be migrated without breaking
     * compilation. The HTTP contract is {@code assignedRoles: string[]}.
     */
    @Deprecated
    @JsonIgnore
    public void setAssignedRole(String assignedRole) {
        this.assignedRoles = assignedRole == null ? null : List.of(assignedRole);
    }

    @Deprecated
    @JsonIgnore
    public String getAssignedRole() {
        return assignedRoles == null || assignedRoles.isEmpty() ? null : assignedRoles.getFirst();
    }
}
