package com.ondemandmonitoring.mission.service;

import com.ondemandmonitoring.mission.dto.response.CustomerMissionReport;
import com.ondemandmonitoring.mission.dto.response.CustomerMissionSummary;
import java.util.List;

public interface ICustomerMissionReportQueryService {
    List<CustomerMissionSummary> list(String orderId);
    CustomerMissionReport report(String orderId, String missionId);
}
