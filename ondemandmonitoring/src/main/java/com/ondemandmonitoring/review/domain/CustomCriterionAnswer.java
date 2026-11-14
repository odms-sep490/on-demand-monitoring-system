package com.ondemandmonitoring.review.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import com.ondemandmonitoring.review.enums.CriterionResult;
@Entity @Table(name="custom_criterion_answers", uniqueConstraints=@UniqueConstraint(columnNames={"order_id","criterion_code"})) @Getter @Setter @NoArgsConstructor
public class CustomCriterionAnswer {
 @Id @GeneratedValue(strategy=GenerationType.UUID) private String id;
 @Column(name="order_id",nullable=false) private String orderId;
 @Column(name="criterion_code",nullable=false,length=80) private String code;
 @Enumerated(EnumType.STRING) private CriterionResult result;
 @Column(length=1000) private String note;
 private String actorName;
 private Instant decidedAt;
}
