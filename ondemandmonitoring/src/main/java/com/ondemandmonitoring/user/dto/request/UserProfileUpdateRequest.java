package com.ondemandmonitoring.user.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UserProfileUpdateRequest {

    @Size(max = 100, message = "Full name must not exceed 100 characters")
    @Pattern(regexp = ".*\\S.*", message = "Full name must not be blank")
    String fullName;

    @Size(max = 20, message = "Phone number must not exceed 20 characters")
    @Pattern(
            regexp = "^$|^[0-9+() .-]{8,20}$",
            message = "Phone number format is invalid")
    String phoneNumber;

    @Size(max = 500, message = "Address must not exceed 500 characters")
    String address;

    @Size(max = 200, message = "Company name must not exceed 200 characters")
    String companyName;

    @Pattern(regexp = "^$|^[0-9]{12}$", message = "Citizen identification number must contain exactly 12 digits")
    String citizenIdentificationNumber;

    @Pattern(regexp = "^$|^[0-9]{10}(-[0-9]{3})?$", message = "Company tax code format is invalid")
    String companyTaxCode;
}
