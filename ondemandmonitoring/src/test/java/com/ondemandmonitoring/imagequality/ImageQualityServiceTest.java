package com.ondemandmonitoring.imagequality;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ondemandmonitoring.media.domain.MediaAsset;
import com.ondemandmonitoring.media.enums.*;
import com.ondemandmonitoring.media.repository.MediaAssetRepository;
import com.ondemandmonitoring.media.service.*;
import com.ondemandmonitoring.mission.service.IMissionAuthorizationService;
import com.ondemandmonitoring.user.service.AuthenticatedUserResolver;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ImageQualityServiceTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    final MediaAssetRepository media=mock(MediaAssetRepository.class);
    final ICustomerMediaService customer=mock(ICustomerMediaService.class);
    final IMissionAuthorizationService authorization=mock(IMissionAuthorizationService.class);
    final IResultRetentionAccessService retention=mock(IResultRetentionAccessService.class);
    final AuthenticatedUserResolver user=mock(AuthenticatedUserResolver.class,RETURNS_DEEP_STUBS);
    final ImageQualityService service=new ImageQualityService(db,mock(TransactionTemplate.class),media,customer,
            mock(IMediaObjectStorage.class),retention,authorization,user,new ObjectMapper());
    MediaAsset asset() {
        MediaAsset asset=mock(MediaAsset.class);
        when(asset.getMissionId()).thenReturn("mission"); when(asset.getType()).thenReturn("IMAGE");
        when(asset.getStorageStatus()).thenReturn(MediaStorageStatus.AVAILABLE);
        when(asset.getMediaStatus()).thenReturn(MediaStatus.AVAILABLE);
        when(asset.getValidatedAt()).thenReturn(Instant.now()); when(asset.getFileSize()).thenReturn(1024L);
        when(media.findById("media")).thenReturn(Optional.of(asset)); service.maxBytes=8388608;
        return asset;
    }
    @Test void customerRequiresExistingOriginalEntitlement() {
        asset(); service.authorize("media",true);
        verify(customer).getAvailable("media"); verify(customer).createOriginalDownload("media"); verify(retention).requireAccessible(any());
    }
    @Test void deniedCustomerCannotReadStoredObject() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(customer).createOriginalDownload("media");
        assertThrows(ResponseStatusException.class,()->service.authorize("media",true)); verifyNoInteractions(media);
    }
    @Test void unrelatedStaffDenied() {
        asset(); assertThrows(ResponseStatusException.class,()->service.authorize("media",false));
    }
    @Test void managerUsesExistingAuthorization() {
        asset(); when(authorization.canManageMissions()).thenReturn(true); service.authorize("media",false);
    }
    @Test void removedAndExpiredMediaDenied() {
        var asset=asset(); when(asset.getMediaStatus()).thenReturn(MediaStatus.REMOVED);
        assertThrows(ResponseStatusException.class,()->service.authorize("media",true));
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.GONE)).when(retention).requireAccessible(asset);
        assertThrows(ResponseStatusException.class,()->service.authorize("media",true));
    }
    @Test void tooLargeImageDenied() {
        var asset=asset(); when(asset.getFileSize()).thenReturn(99999999L);
        assertThrows(ResponseStatusException.class,()->service.authorize("media",true));
    }
    @Test void disabledFeatureFailsClosed() {
        assertThrows(ResponseStatusException.class,()->service.request("media",UUID.randomUUID(),"PANORAMA",true)); verifyNoInteractions(media,customer);
    }
    @Test void customerCannotReadOtherRequestersCheck() {
        service.enabled=true;service.token="test";
        when(db.queryForObject(anyString(),eq(Boolean.class))).thenReturn(true);
        when(user.getCurrentUser().getId()).thenReturn("customer-a");
        when(user.getCurrentUser().getRole().getCode()).thenReturn(com.ondemandmonitoring.role.enumeration.RoleCode.CUSTOMER);
        UUID id=UUID.randomUUID();
        when(db.queryForList(contains("requested_by from"),eq(id))).thenReturn(List.of(new HashMap<>(Map.of("requested_by","customer-b","media_id","media"))));
        assertThrows(ResponseStatusException.class,()->service.read(id)); verifyNoInteractions(customer,media);
    }
}
