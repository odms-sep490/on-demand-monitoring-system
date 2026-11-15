package com.ondemandmonitoring.review.repository;
import com.ondemandmonitoring.review.domain.CustomCriterionAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface CustomCriterionAnswerRepository extends JpaRepository<CustomCriterionAnswer,String> {
 Optional<CustomCriterionAnswer> findByOrderIdAndCode(String orderId,String code);
 List<CustomCriterionAnswer> findByOrderId(String orderId);
}
