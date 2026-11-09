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
    // Effective values = override, else inherited from the checklist definition.
    private java.math.BigDecimal basePrice;
    private Integer includedImageCount;
    private Integer includedVideoCount;
    private String pricingProfile;
    private Integer standardVideoDurationSeconds;
    private java.math.BigDecimal basePriceOverride;
    private Integer includedImageCountOverride;
    private Integer includedVideoCountOverride;
    private String pricingProfileOverride;
    private java.math.BigDecimal checklistBasePrice;
    private int checklistIncludedImageCount;
    private int checklistIncludedVideoCount;
    private String checklistPricingProfile;
}
