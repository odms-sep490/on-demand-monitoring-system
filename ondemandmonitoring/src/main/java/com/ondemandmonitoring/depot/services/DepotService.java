
package com.ondemandmonitoring.depot.services;

import com.ondemandmonitoring.depot.dto.request.DepotCreateRequest;
import com.ondemandmonitoring.depot.dto.request.DepotUpdateRequest;
import com.ondemandmonitoring.depot.dto.response.DepotResponse;

import java.util.List;

public interface DepotService {

    // Create
    DepotResponse createDepot(DepotCreateRequest request);

    // Read by ID
    DepotResponse getDepotById(String id);

    // Read all
    List<DepotResponse> getAllDepots();

    // Update
    DepotResponse updateDepot(String id, DepotUpdateRequest request);

    // Delete
    void deleteDepot(String id);

    // Find nearest active depot based on Order location
    DepotResponse findNearestDepot(String orderId);
}
