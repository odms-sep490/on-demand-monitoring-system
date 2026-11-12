package com.ondemandmonitoring.media.repository;

import com.ondemandmonitoring.media.domain.ManualUploadTask;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManualUploadTaskRepository extends JpaRepository<ManualUploadTask, String> {

    Optional<ManualUploadTask> findByMediaId(String mediaId);

    List<ManualUploadTask> findByMediaIdIn(List<String> mediaIds);

    @Query("select t from ManualUploadTask t join fetch t.media m "
            + "where m.mission.id = :missionId and t.status = com.ondemandmonitoring.media.enums.ManualUploadTaskStatus.OPEN order by t.createdAt")
    List<ManualUploadTask> findOpenByMissionId(@Param("missionId") String missionId);
}
