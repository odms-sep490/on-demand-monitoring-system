package com.ondemandmonitoring.media.service;

import com.ondemandmonitoring.media.domain.MediaAsset;

public interface IResultRetentionAccessService {
    void requireAccessible(MediaAsset asset);
}
