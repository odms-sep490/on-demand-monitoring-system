package com.ondemandmonitoring.role.infrastructure;

import com.ondemandmonitoring.role.domain.Role;
import com.ondemandmonitoring.role.enumeration.RoleCode;
import com.ondemandmonitoring.role.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Component
@Order(10)
@RequiredArgsConstructor
public class RoleDataInitializer implements ApplicationRunner {

    private static final Map<RoleCode, RoleDefinition> SYSTEM_ROLES = Map.of(
            RoleCode.CUSTOMER, new RoleDefinition("CUSTOMER", "CUSTOMER ACCOUNT"),
            RoleCode.MANAGER, new RoleDefinition("MANAGER", "MISSION COORDINATION ACCOUNT"),
            RoleCode.STAFF, new RoleDefinition("STAFF", "ASSIGNED OPERATIONS ACCOUNT"),
            RoleCode.ADMIN, new RoleDefinition("ADMIN", "SYSTEM ADMINISTRATOR ACCOUNT"));

    private final RoleRepository roleRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        SYSTEM_ROLES.forEach((code, definition) ->
                roleRepository.findByCode(code).orElseGet(() -> roleRepository.save(
                        Role.builder()
                                .code(code)
                                .name(definition.name())
                                .description(definition.description())
                                .systemRole(true)
                                .active(true)
                                .build())));
    }

    private record RoleDefinition(String name, String description) {
    }
}
