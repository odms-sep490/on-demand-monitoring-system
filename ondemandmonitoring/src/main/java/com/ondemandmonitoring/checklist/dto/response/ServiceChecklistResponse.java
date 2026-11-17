package com.ondemandmonitoring.checklist.dto.response;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
public class ServiceChecklistResponse {
    private String id;
    private String serviceId;
    private String serviceName;
    private Boolean serviceActive;
    private String checklistId;
    private String content;
    private String description;
    private java.util.List<String> executionSteps;
    private java.util.List<String> inputRequirements;
    private java.util.List<String> expectedOutputs;
    private Boolean checklistActive;
    private int displayOrder;
    private Long version;
    private Long checklistVersion;
    private boolean required;
    /** How many services include this checklist; more than one means editing its content changes all of them. */
    private long usedByServiceCount;
    private Integer includedImageCount;
    private Integer includedVideoCount;
    private Integer standardVideoDurationSeconds;
    private Integer includedImageCountOverride;
    private Integer includedVideoCountOverride;
    private int checklistIncludedImageCount;
    private int checklistIncludedVideoCount;
    /** Device types associated with the underlying checklist definition. */
    private java.util.List<com.ondemandmonitoring.device.dto.response.DeviceTypeResponse> deviceTypes = java.util.List.of();
}
