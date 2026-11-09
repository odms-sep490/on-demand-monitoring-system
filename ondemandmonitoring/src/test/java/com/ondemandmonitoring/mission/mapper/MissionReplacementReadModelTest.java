package com.ondemandmonitoring.mission.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.mission.domain.Mission;
import com.ondemandmonitoring.mission.dto.response.MissionResponse;
import com.ondemandmonitoring.mission.enums.MissionStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MissionReplacementReadModelTest {
    @Test void generatedSpringMapperAndJsonPreserveReplacementChainWithoutSiblingLookup() throws Exception {
        var mapper=Mappers.getMapper(MissionMapper.class);
        var helper=mock(MissionMapperHelper.class);
        ReflectionTestUtils.setField(mapper,"missionMapperHelper",helper);
        Mission first=mission("m1",null), second=mission("m2","m1"), third=mission("m3","m2");
        var json=new ObjectMapper();
        var detail=json.readTree(json.writeValueAsString(mapper.toResponse(third)));
        assertEquals("m2",detail.get("replacesMissionId").asText());
        var collection=List.of(first,second,third).stream().map(mapper::toResponse).toList();
        assertEquals(java.util.Arrays.asList(null,"m1","m2"),collection.stream().map(MissionResponse::getReplacesMissionId).toList());
        var list=json.readTree(json.writeValueAsString(collection));
        assertTrue(list.get(0).get("replacesMissionId").isNull());
        assertEquals("m1",list.get(1).get("replacesMissionId").asText());
        assertEquals("m2",third.getReplacesMissionId()); assertEquals(MissionStatus.CANCELLED,second.getStatus());
    }
    Mission mission(String id,String replaces) {
        var mission=new Mission(); mission.setId(id); mission.setMissionCode(id); mission.setReplacesMissionId(replaces);
        mission.setStatus(MissionStatus.CANCELLED); return mission;
    }
}
