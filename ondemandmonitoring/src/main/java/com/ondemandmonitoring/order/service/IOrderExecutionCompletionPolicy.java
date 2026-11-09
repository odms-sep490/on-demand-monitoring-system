package com.ondemandmonitoring.order.service;

import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.order.domain.Order;
import java.util.List;

/** Command-only aggregate boundary. Never invoke from Mission runtime or query APIs. */
public interface IOrderExecutionCompletionPolicy {
    LockedExecution lockAggregate(String orderId);
    boolean completeIfReady(LockedExecution execution);

    record LockedExecution(Order order, List<Mission> missions) {
        public LockedExecution { missions = List.copyOf(missions); }
    }
}
