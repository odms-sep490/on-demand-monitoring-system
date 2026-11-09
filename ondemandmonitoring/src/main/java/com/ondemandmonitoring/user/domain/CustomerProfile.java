package com.ondemandmonitoring.user.domain;

import com.ondemandmonitoring.common.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "customer_profiles")
@AttributeOverride(name = "id", column = @Column(name = "user_id", nullable = false, updatable = false))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerProfile extends BaseEntity {

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "company_name", length = 200)
    private String companyName;

    @Column(name = "citizen_identification_number", length = 12, unique = true)
    private String citizenIdentificationNumber;

    @Column(name = "company_tax_code", length = 14, unique = true)
    private String companyTaxCode;
}
