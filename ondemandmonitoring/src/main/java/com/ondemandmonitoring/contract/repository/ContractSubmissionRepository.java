package com.ondemandmonitoring.contract.repository;

import com.ondemandmonitoring.contract.domain.ContractSubmission;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

public interface ContractSubmissionRepository extends JpaRepository<ContractSubmission, String> {
  List<ContractSubmission> findAllByContractIdOrderBySubmissionNumberDesc(String contractId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from ContractSubmission s where s.id=:id")
  Optional<ContractSubmission> findByIdForUpdate(@Param("id") String id);

  @Query(
      "select s.id from ContractSubmission s where s.status='SUPERSEDED' and s.documentDeletedAt is"
          + " null and s.supersededAt<=:cutoff and s.cleanupNextAttemptAt<=:now order by"
          + " s.cleanupNextAttemptAt,s.id")
  List<String> findCleanupCandidates(
      @Param("now") Instant now, @Param("cutoff") Instant cutoff, Pageable pageable);

  boolean existsByContractIdAndSubmissionNumberGreaterThan(String contractId, int submissionNumber);

  boolean existsByDocumentBucketAndDocumentKeyAndIdNot(String bucket, String key, String id);
}
