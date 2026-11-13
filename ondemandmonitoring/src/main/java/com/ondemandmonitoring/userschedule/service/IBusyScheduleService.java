package com.ondemandmonitoring.userschedule.service;

import com.ondemandmonitoring.userschedule.dto.request.BusyScheduleRequest;
import com.ondemandmonitoring.userschedule.dto.response.BusyScheduleResponse;
import com.ondemandmonitoring.userschedule.dto.response.WeeklyBusyScheduleResponse;
import java.time.LocalDate;

public interface IBusyScheduleService {
    WeeklyBusyScheduleResponse getMyWeek(LocalDate anyDayInWeek);

    WeeklyBusyScheduleResponse getStaffWeek(String staffId, LocalDate anyDayInWeek);

    BusyScheduleResponse get(String id);

    BusyScheduleResponse create(BusyScheduleRequest request);

    BusyScheduleResponse update(String id, BusyScheduleRequest request);

    void delete(String id);
}
