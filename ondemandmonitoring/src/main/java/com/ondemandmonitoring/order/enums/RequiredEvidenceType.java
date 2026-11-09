package com.ondemandmonitoring.order.enums;

/**
 * Required media evidence type for an Order checklist item snapshot.
 * - IMAGE: only eligible IMAGE assets count.
 * - VIDEO: only eligible VIDEO assets count.
 * - IMAGE_AND_VIDEO: evidence policy V3 only. The checklist needs its own minimum number of images AND its own minimum
 *   number of videos (minimumImageCount / minimumVideoCount); one type never stands in for the other.
 * - IMAGE_OR_VIDEO: legacy (policy V1/V2 snapshots). Any combination of eligible images and videos counts. No new
 *   order is created with it; it is kept so historical snapshots keep their meaning.
 */
public enum RequiredEvidenceType {
    IMAGE,
    VIDEO,
    IMAGE_AND_VIDEO,
    IMAGE_OR_VIDEO
}
