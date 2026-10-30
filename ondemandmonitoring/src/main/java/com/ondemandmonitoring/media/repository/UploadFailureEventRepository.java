package com.ondemandmonitoring.media.repository;

import com.ondemandmonitoring.media.domain.UploadFailureEvent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UploadFailureEventRepository extends JpaRepository<UploadFailureEvent, String> {

    Optional<UploadFailureEvent> findByEventKey(String eventKey);

    List<UploadFailureEvent> findByProcessedAtIsNullOrderByOccurredAtAsc();
}