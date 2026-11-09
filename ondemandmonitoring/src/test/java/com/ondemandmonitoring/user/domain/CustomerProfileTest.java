package com.ondemandmonitoring.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.AttributeOverride;
import com.ondemandmonitoring.common.entity.BaseEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerProfileTest {

    @Test
    void buildsCustomerProfileWithoutDuplicatingAccountFields() {
        User user = User.builder()
                .email("customer@example.com")
                .fullName("Customer Name")
                .build();
        user.setId(UUID.randomUUID().toString());

        CustomerProfile profile = CustomerProfile.builder()
                .user(user)
                .phoneNumber("+84901234567")
                .address("Ho Chi Minh City")
                .companyName("Monitoring Co.")
                .citizenIdentificationNumber("079203001234")
                .companyTaxCode("0317654321")
                .build();

        assertSame(user, profile.getUser());
        assertEquals("+84901234567", profile.getPhoneNumber());
        assertEquals("Ho Chi Minh City", profile.getAddress());
        assertEquals("Monitoring Co.", profile.getCompanyName());
        assertEquals("079203001234", profile.getCitizenIdentificationNumber());
        assertEquals("0317654321", profile.getCompanyTaxCode());
        assertFalse(hasField(CustomerProfile.class, "email"));
        assertFalse(hasField(CustomerProfile.class, "fullName"));
        assertFalse(hasField(CustomerProfile.class, "role"));
        assertFalse(hasField(CustomerProfile.class, "isActive"));
    }

    @Test
    void mapsSharedPrimaryKeyAndExpectedColumnLimits() throws NoSuchFieldException {
        Table table = CustomerProfile.class.getAnnotation(Table.class);
        assertNotNull(table);
        assertEquals("customer_profiles", table.name());

        assertTrue(BaseEntity.class.isAssignableFrom(CustomerProfile.class));
        assertNotNull(BaseEntity.class.getDeclaredField("id").getAnnotation(Id.class));
        AttributeOverride idMapping = CustomerProfile.class.getAnnotation(AttributeOverride.class);
        assertNotNull(idMapping);
        assertEquals("id", idMapping.name());
        assertEquals("user_id", idMapping.column().name());

        Field user = CustomerProfile.class.getDeclaredField("user");
        assertNotNull(user.getAnnotation(MapsId.class));
        OneToOne oneToOne = user.getAnnotation(OneToOne.class);
        assertNotNull(oneToOne);
        assertEquals(FetchType.LAZY, oneToOne.fetch());
        assertFalse(oneToOne.optional());
        JoinColumn joinColumn = user.getAnnotation(JoinColumn.class);
        assertNotNull(joinColumn);
        assertEquals("user_id", joinColumn.name());
        assertFalse(joinColumn.nullable());

        assertColumn(CustomerProfile.class.getDeclaredField("phoneNumber"), "phone_number", 20, true, true);
        assertColumn(CustomerProfile.class.getDeclaredField("address"), "address", 500, true, true);
        assertColumn(CustomerProfile.class.getDeclaredField("companyName"), "company_name", 200, true, true);
        assertColumn(CustomerProfile.class.getDeclaredField("citizenIdentificationNumber"),
                "citizen_identification_number", 12, true, true);
        assertColumn(CustomerProfile.class.getDeclaredField("companyTaxCode"),
                "company_tax_code", 14, true, true);
        assertNotNull(BaseEntity.class.getDeclaredField("version").getAnnotation(Version.class));
    }

    private void assertColumn(
            Field field,
            String expectedName,
            int expectedLength,
            boolean expectedNullable,
            boolean expectedUpdatable) {
        Column column = field.getAnnotation(Column.class);
        assertNotNull(column);
        assertEquals(expectedName, column.name());
        assertEquals(expectedLength, column.length());
        assertEquals(expectedNullable, column.nullable());
        assertEquals(expectedUpdatable, column.updatable());
    }

    private boolean hasField(Class<?> type, String fieldName) {
        try {
            type.getDeclaredField(fieldName);
            return true;
        } catch (NoSuchFieldException exception) {
            return false;
        }
    }
}
