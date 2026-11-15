package com.ondemandmonitoring.review.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ondemandmonitoring.review.repository.ReviewCriterionDefinitionRepository;
import com.ondemandmonitoring.review.domain.ReviewCriterionDefinition;
import java.util.*;
import org.junit.jupiter.api.Test;
class CriterionCatalogServiceTest {
 @Test void deletedBuiltInIsNotActiveAndCustomCriteriaAreManual() {
  var repository=mock(ReviewCriterionDefinitionRepository.class);
  var removed=new ReviewCriterionDefinition();removed.setCode("SITE_ACCESS");removed.setTitle("Site");removed.setDescription("Access");removed.setActive(false);
  var custom=new ReviewCriterionDefinition();custom.setCode("CUSTOM_test");custom.setTitle("New check");custom.setDescription("Confirm");custom.setRequired(true);custom.setActive(true);
  when(repository.findAll()).thenReturn(List.of(removed,custom));
  var service=new CriterionCatalogService(repository);
  assertThatThrownBy(()->service.active("SITE_ACCESS")).isInstanceOf(com.ondemandmonitoring.common.exception.ApiException.class);
  assertThat(service.active("CUSTOM_test").required()).isTrue();
  assertThat(service.active("CUSTOM_test").type().name()).isEqualTo("MANUAL");
  assertThat(service.active("SERVICE_AREA").type().name()).isEqualTo("AUTO");
 }
}
