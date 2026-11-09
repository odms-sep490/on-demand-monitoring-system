package com.ondemandmonitoring.user.mapper;

import com.ondemandmonitoring.user.domain.CustomerProfile;
import com.ondemandmonitoring.user.domain.User;
import com.ondemandmonitoring.user.dto.response.CustomerProfileResponse;
import com.ondemandmonitoring.user.dto.response.UserProfileResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface UserProfileMapper {

    @Mapping(source = "user.id", target = "id")
    @Mapping(source = "user.fullName", target = "fullName")
    @Mapping(source = "user.email", target = "email")
    @Mapping(source = "user.role.code", target = "role")
    @Mapping(source = "customerProfile", target = "customerProfile")
    @Mapping(target = "avatarUrl", ignore = true)
    @Mapping(target = "linkedProviders", ignore = true)
    UserProfileResponse toResponse(User user, CustomerProfile customerProfile);

    CustomerProfileResponse toCustomerResponse(CustomerProfile customerProfile);
}
