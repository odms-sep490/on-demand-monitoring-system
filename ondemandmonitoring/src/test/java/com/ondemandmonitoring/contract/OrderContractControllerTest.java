package com.ondemandmonitoring.contract;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ondemandmonitoring.contract.controller.OrderContractController;
import com.ondemandmonitoring.contract.service.IOrderContractService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitConfig(OrderContractControllerTest.Config.class)
@WebAppConfiguration
class OrderContractControllerTest {
  @Autowired WebApplicationContext context;
  @Autowired IOrderContractService service;
  @Autowired com.ondemandmonitoring.contract.service.IContractCancellationService cancellations;
  MockMvc mvc;

  @BeforeEach
  void setup() {
    reset(service, cancellations);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void issueMultipartUsesExactBackendContract() throws Exception {
    mvc.perform(
            multipart("/api/orders/order/contract/issue")
                .file(ContractTestFiles.pdf())
                .param("quoteId", "quote")
                .param("contractNumber", "CTR-01")
                .with(user("manager").roles("MANAGER")))
        .andExpect(status().isOk());
    verify(service).issue(eq("order"), eq("quote"), eq("CTR-01"), any());
  }

  @Test
  void customerSubmissionIsAnExplicitVersionedMultipartPost() throws Exception {
    mvc.perform(
            multipart("/api/orders/order/contract/submissions")
                .file(ContractTestFiles.pdf())
                .param("expectedVersion", "4")
                .with(user("customer").roles("CUSTOMER")))
        .andExpect(status().isOk());
    verify(service).submit(eq("order"), eq(4L), any());
  }

  @Test
  void customerCannotIssueConfirmOrReview() throws Exception {
    mvc.perform(
            multipart("/api/orders/order/contract/issue")
                .file(ContractTestFiles.pdf())
                .param("quoteId", "quote")
                .param("contractNumber", "CTR-01")
                .with(user("customer").roles("CUSTOMER")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/orders/order/contract/confirm")
                .contentType("application/json")
                .content("{\"expectedVersion\":4,\"submissionId\":\"signed\"}")
                .with(user("customer").roles("CUSTOMER")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/orders/order/contract/request-changes")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":4,\"submissionId\":\"signed\",\"reason\":\"reason\"}")
                .with(user("customer").roles("CUSTOMER")))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void managerCannotSubmitAsTheCustomerAndStaffCannotReadContracts() throws Exception {
    mvc.perform(
            multipart("/api/orders/order/contract/submissions")
                .file(ContractTestFiles.pdf())
                .param("expectedVersion", "4")
                .with(user("manager").roles("MANAGER")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/orders/order/contract").with(user("staff").roles("STAFF")))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void negativeReviewVersionIsRejectedBeforeBusinessMutation() throws Exception {
    mvc.perform(
            post("/api/orders/order/contract/confirm")
                .contentType("application/json")
                .content("{\"expectedVersion\":-1,\"submissionId\":\"signed\"}")
                .with(user("manager").roles("MANAGER")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void anonymousRequestIsNotAuthenticated() throws Exception {
    mvc.perform(get("/api/orders/order/contract")).andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @Test
  void preparingBeforeAcceptanceCarriesTheExactPricingRevision() throws Exception {
    mvc.perform(multipart("/api/orders/order/contract/issue")
        .file(ContractTestFiles.pdf()).param("quoteId", "quote")
        .param("contractNumber", "CTR-01").param("expectedPricingRevision", "7")
        .with(user("manager").roles("MANAGER"))).andExpect(status().isOk());
    verify(service).prepare(eq("order"),eq("quote"),eq(7L),eq("CTR-01"),any());
    verify(service,never()).issue(any(),any(),any(),any());
  }

  @Test
  void customerReadsTheExactQuoteContractBeforeAnInvoiceExists() throws Exception {
    mvc.perform(get("/api/orders/order/contract").param("quoteId","quote")
        .with(user("customer").roles("CUSTOMER"))).andExpect(status().isOk());
    verify(service).getForQuote("order","quote");
  }

  @Test
  void managerOnlyNeedsPdfAndQuoteRevisionNotAManuallyChosenNumber() throws Exception {
    mvc.perform(multipart("/api/orders/order/contract/issue").file(ContractTestFiles.pdf())
        .param("quoteId", "quote").param("expectedPricingRevision", "7")
        .with(user("manager").roles("MANAGER"))).andExpect(status().isOk());
    verify(service).prepare(eq("order"), eq("quote"), eq(7L), isNull(), any());
  }

  @Test
  void onlyManagerCanCancelOrDecideContractLiability() throws Exception {
    String body="{\"clientRequestId\":\"00c44737-d87a-47bc-87ee-0d81f9e50065\",\"expectedVersion\":2,\"responsibility\":\"UNRESOLVED\",\"reason\":\"Parties cannot proceed\",\"customerLiabilityAcknowledged\":false}";
    for (String role : new String[]{"CUSTOMER","STAFF","ADMIN"}) {
      mvc.perform(post("/api/orders/order/contract/cancellation").contentType("application/json").content(body).with(user("actor").roles(role))).andExpect(status().isForbidden());
      mvc.perform(post("/api/orders/order/contract/cancellation/decision").contentType("application/json").content("{\"expectedVersion\":2,\"responsibility\":\"SYSTEM\",\"basis\":\"Documented agreement\",\"customerLiabilityAcknowledged\":false}").with(user("actor").roles(role))).andExpect(status().isForbidden());
    }
    verifyNoInteractions(cancellations);
    mvc.perform(post("/api/orders/order/contract/cancellation").contentType("application/json").content(body).with(user("manager").roles("MANAGER"))).andExpect(status().isOk());
    verify(cancellations).request(eq("order"),any());
  }

  @Test
  void invalidCancellationIsRejectedBeforeMutationAndStaffCannotRead() throws Exception {
    mvc.perform(post("/api/orders/order/contract/cancellation").contentType("application/json")
      .content("{\"expectedVersion\":-1,\"responsibility\":\"SYSTEM\",\"reason\":\"short\"}").with(user("manager").roles("MANAGER"))).andExpect(status().isBadRequest());
    mvc.perform(get("/api/orders/order/contract/cancellation").with(user("staff").roles("STAFF"))).andExpect(status().isForbidden());
    verifyNoInteractions(cancellations);
  }

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @EnableMethodSecurity
  static class Config {
    @Bean com.ondemandmonitoring.contract.service.IContractCancellationService cancellations() { return mock(com.ondemandmonitoring.contract.service.IContractCancellationService.class); }
    @Bean
    IOrderContractService service() {
      return mock(IOrderContractService.class);
    }

    @Bean com.ondemandmonitoring.contract.controller.ContractCancellationController cancellationController(com.ondemandmonitoring.contract.service.IContractCancellationService service) { return new com.ondemandmonitoring.contract.controller.ContractCancellationController(service); }
    @Bean
    OrderContractController controller(IOrderContractService service) {
      return new OrderContractController(service);
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
      return http.csrf(csrf -> csrf.disable())
          .authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .httpBasic(Customizer.withDefaults())
          .build();
    }
  }
}
