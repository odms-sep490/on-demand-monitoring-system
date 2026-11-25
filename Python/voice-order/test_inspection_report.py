import unittest
from inspection_report import Document, ReportInput, validate_references, image_metadata, sanitize_document

class InspectionReportGuardrailsTest(unittest.TestCase):
    def setUp(self):
        self.request = ReportInput(mission={'missionId':'mission-1','serviceName':'Giám sát công trình'}, images=[{'mediaId':'media-1','isEligible':True}], findings=[])
        self.payload = dict(reportTitle='Báo cáo giám sát', executiveSummary='Tổng hợp hồ sơ ảnh được cấp quyền.', inspectionScope='Theo phạm vi nhiệm vụ.', methodology='Tổng hợp ảnh đã duyệt.', sections=[dict(sectionId='overview',title='Hồ sơ ảnh',content='Chưa có phát hiện kiểm định tự động.',images=[dict(mediaId='media-1',role='OVERVIEW',caption='Ảnh nguồn thuộc nhiệm vụ.')],sourceFindingIds=[],sourceMediaIds=['media-1'])],limitations=['Cần con người kiểm tra.'],conclusion='Chỉ tổng hợp hồ sơ.',recommendations=['Đối chiếu dữ liệu nguồn.'])
    def check(self): return validate_references(Document.model_validate(self.payload), self.request)
    def test_no_findings_is_valid(self): self.assertTrue(self.check())
    def test_timestamp_serializers_have_identical_model_metadata(self):
        self.assertEqual(image_metadata({'capturedAt':1795478401}),image_metadata({'capturedAt':'2026-11-24T00:00:01Z'}))
    def test_image_filename_number_is_not_an_invented_measurement(self):
        self.request.images[0]['fileName']='Ảnh 2.png'
        self.payload['sections'][0]['images'][0]['caption']='Ảnh 2'
        self.assertTrue(self.check())
    def test_unknown_media_is_rejected(self):
        self.payload['sections'][0]['images'][0]['mediaId']='other-mission'
        with self.assertRaises(ValueError): self.check()
    def test_unknown_finding_is_rejected(self):
        self.payload['sections'][0]['sourceFindingIds']=['invented']
        with self.assertRaises(ValueError): self.check()
    def test_invented_measurement_is_rejected(self):
        self.payload['conclusion']='Chiều dài 123 mét.'
        with self.assertRaises(ValueError): self.check()
    def test_unsupported_defect_is_rejected(self):
        self.payload['conclusion']='Có vết nứt trong kết cấu.'
        with self.assertRaises(ValueError): self.check()
    def test_filename_cannot_authorize_an_inspection_assertion(self):
        self.request.images[0]['fileName']='vết nứt.png'
        self.payload['conclusion']='Có vết nứt trong kết cấu.'
        with self.assertRaises(ValueError): self.check()
    def test_pixel_dimensions_cannot_authorize_physical_measurements(self):
        self.request.images[0]['width']=1200
        self.payload['conclusion']='Chiều dài 1200 m.'
        with self.assertRaises(ValueError): self.check()
    def test_unsafe_url_is_rejected(self):
        self.payload['conclusion']='https://private-storage.example/key'
        with self.assertRaises(ValueError): self.check()
    def test_unverified_compliance_is_rejected(self):
        self.payload['conclusion']='Việc ghi nhận đã đảm bảo tuân thủ tiêu chuẩn.'
        with self.assertRaises(ValueError): self.check()
    def test_unverified_completion_is_rejected(self):
        self.payload['conclusion']='Nhiệm vụ đã hoàn thành theo yêu cầu.'
        with self.assertRaises(ValueError): self.check()
    def test_unresolved_template_is_rejected(self):
        self.payload['recommendations']=['recommendationWithoutOpinionOrExpertise']
        with self.assertRaises(ValueError): self.check()
    def test_duplicate_sections_are_rejected(self):
        self.payload['sections']*=2
        with self.assertRaises(ValueError): self.check()
    def test_supported_finding_requires_exact_reviewed_text(self):
        self.request.findings=[{'id':'finding-1','text':'Quan sát được xác nhận.','mediaIds':['media-1']}]
        self.payload['sections'][0]['sourceFindingIds']=['finding-1']
        with self.assertRaises(ValueError):self.check()
        self.payload['sections'][0]['content']='Quan sát được xác nhận.'
        self.assertTrue(self.check())
    def test_before_after_role_is_rejected(self):
        self.payload['sections'][0]['images'][0]['role']='BEFORE'
        with self.assertRaises(ValueError):self.check()
    def test_sanitize_removes_human_verified_no_finding_sentence(self):
        self.payload['executiveSummary'] = (
            'Mục tiêu của nhiệm vụ là giám sát công trình. '
            'Hiện tại, chưa có phát hiện nào được đánh giá và xác nhận bởi con người.'
        )
        self.payload['sections'][0]['content'] = (
            'Đây là báo cáo tổng quan. '
            'Tuy nhiên, không có phát hiện cụ thể nào được xác nhận bởi con người trong quá trình giám sát.'
        )
        cleaned = sanitize_document(Document.model_validate(self.payload))
        self.assertEqual(cleaned.executiveSummary, 'Mục tiêu của nhiệm vụ là giám sát công trình.')
        self.assertEqual(cleaned.sections[0].content, 'Đây là báo cáo tổng quan.')
    def test_rewrite_preserves_existing_document_numbers_and_captions(self):
        existing = Document.model_validate({
            **self.payload,
            'reportTitle': 'Báo cáo giám sát công trình - Mission MS-27943',
            'executiveSummary': 'Ảnh chụp ngày 24/11/2026 lúc 18:00.',
        })
        req = ReportInput(
            mission={'missionId': 'mission-1', 'missionCode': 'MS-27943', 'serviceName': 'Giám sát công trình'},
            images=[{'mediaId': 'media-1', 'isEligible': True}],
            findings=[],
            action='rewrite',
            document=existing,
            instruction='caption',
        )
        rewritten = existing.model_copy(update={
            'sections': [existing.sections[0].model_copy(update={
                'images': [existing.sections[0].images[0].model_copy(update={'caption': 'Ảnh tổng quan nhiệm vụ MS-27943'})]
            })]
        })
        self.assertTrue(validate_references(rewritten, req))

if __name__=='__main__': unittest.main()
