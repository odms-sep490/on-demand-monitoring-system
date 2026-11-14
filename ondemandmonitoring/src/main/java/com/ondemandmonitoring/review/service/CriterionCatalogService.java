package com.ondemandmonitoring.review.service;
import com.ondemandmonitoring.review.domain.ReviewCriterionDefinition;
import com.ondemandmonitoring.review.repository.ReviewCriterionDefinitionRepository;
import com.ondemandmonitoring.review.enums.*;
import com.ondemandmonitoring.common.exception.*;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.*;
@Service @RequiredArgsConstructor
public class CriterionCatalogService {
 private final ReviewCriterionDefinitionRepository repository;
 public record Definition(String code,String title,String description,boolean required,ReviewCriterionType type,boolean active) {}
 public record Input(@NotBlank @Size(max=150) String title,@NotBlank @Size(max=1000) String description,boolean required) {}
 @Transactional(readOnly=true)
 public List<Definition> list() {
  Map<String,Definition> all = new LinkedHashMap<>();
  for (var code:ReviewCriterionCode.values()) if(code.isActive()) all.put(code.name(),new Definition(code.name(),code.getTitle(),code.getDescription(),code.isRequired(),code.getType(),true));
  for(var row:repository.findAll()) {
   var previous=all.get(row.getCode());
   all.put(row.getCode(),new Definition(row.getCode(),row.getTitle(),row.getDescription(),row.isRequired(),previous==null?ReviewCriterionType.MANUAL:previous.type(),row.isActive()));
  }
  return List.copyOf(all.values());
 }
 public Definition active(String code) { return list().stream().filter(d->d.active()&&d.code().equals(code)).findFirst().orElseThrow(()->new ApiException(ErrorCode.RESOURCE_NOT_FOUND,"Criterion not found")); }
 @Transactional @PreAuthorize("hasRole('ADMIN')")
 public Definition create(Input input) { return save("CUSTOM_"+UUID.randomUUID(),input); }
 @Transactional @PreAuthorize("hasRole('ADMIN')")
 public Definition update(String code,Input input) { active(code); return save(code,input); }
 private Definition save(String code,Input input) {
  var row=repository.findById(code).orElseGet(ReviewCriterionDefinition::new);
  row.setCode(code); row.setTitle(input.title().strip()); row.setDescription(input.description().strip()); row.setRequired(input.required()); row.setActive(true); repository.saveAndFlush(row); return active(code);
 }
 @Transactional @PreAuthorize("hasRole('ADMIN')")
 public void delete(String code) { var d=active(code); var row=repository.findById(code).orElseGet(ReviewCriterionDefinition::new); row.setCode(code);row.setTitle(d.title());row.setDescription(d.description());row.setRequired(d.required());row.setActive(false);repository.save(row); }
}
