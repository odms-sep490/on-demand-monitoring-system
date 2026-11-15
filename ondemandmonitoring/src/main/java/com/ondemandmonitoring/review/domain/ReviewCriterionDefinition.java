package com.ondemandmonitoring.review.domain;
import jakarta.persistence.*;
import lombok.*;
@Entity @Table(name="review_criterion_definitions") @Getter @Setter @NoArgsConstructor
public class ReviewCriterionDefinition {
 @Id @Column(length=80) private String code;
 @Column(nullable=false,length=150) private String title;
 @Column(nullable=false,length=1000) private String description;
 @Column(nullable=false) private boolean required;
 @Column(nullable=false) private boolean active = true;
}
