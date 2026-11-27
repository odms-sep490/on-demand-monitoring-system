package com.ondemandmonitoring.aiops.config;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds default AI model registry records once on application startup.
 */
@Slf4j
@Component
@Order(50)
@RequiredArgsConstructor
public class AiModelSeedDataInitializer implements ApplicationRunner {

    private final JdbcTemplate db;

    @Value("${IMAGE_AI_URL:http://localhost:8000}")
    private String imageWorkerUrl;

    @Value("${DESCRIPTION_AI_URL:http://localhost:8002}")
    private String aiWorkerUrl;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String imgBase = normalizeBase(imageWorkerUrl, "http://localhost:8000");
        String workerBase = normalizeBase(aiWorkerUrl, "http://localhost:8002");

        List<Object[]> defaults = List.of(
                new Object[]{"mdl-checklist-vision", "checklist-image-vision", "Checklist Image Vision",
                        "v1", "COMPUTER_VISION", "Assess checklist image criteria", "Configured provider",
                        "Provider-specific", "deployment-managed", "Chat completions", "Provider",
                        "", "N/A", "{}"},
                new Object[]{
                        "mdl-opencd",
                        "open-cd-changer",
                        "Open-CD Changer",
                        "v1.0",
                        "COMPUTER_VISION",
                        "Phát hiện thay đổi công trình",
                        "GitHub · Open-CD",
                        "Apache-2.0",
                        "local-checkpoint",
                        "PyTorch",
                        "CPU",
                        imgBase + "/ready",
                        "~420 MB",
                        "{\"threshold\":0.5,\"timeoutSec\":60}"
                },
                new Object[]{
                        "mdl-imgqual",
                        "image-quality-worker",
                        "Image Quality Worker",
                        "v1.0",
                        "COMPUTER_VISION",
                        "Đánh giá chất lượng ảnh bàn giao",
                        "Internal CV Pipeline",
                        "Proprietary",
                        "local-pipeline",
                        "OpenCV / PyTorch",
                        "CPU",
                        imgBase + "/quality/ready",
                        "~180 MB",
                        "{\"blurThreshold\":100,\"timeoutSec\":30}"
                },
                new Object[]{
                        "mdl-qwen-desc",
                        "qwen3-description",
                        "Qwen3 1.7B",
                        "1.7b",
                        "LLM",
                        "Sinh nội dung mô tả & gợi ý checklist",
                        "Ollama Registry",
                        "Apache-2.0",
                        "ollama-managed",
                        "Ollama",
                        "GPU/CPU",
                        workerBase + "/conversation/health",
                        "1.4 GB",
                        "{\"temperature\":0.2,\"timeoutSec\":120,\"maxTokens\":2048}"
                },
                new Object[]{
                        "mdl-qwen-order",
                        "odm-order-qwen-3b",
                        "ODM Order Qwen 3B",
                        "3b",
                        "LLM",
                        "Tư vấn đặt dịch vụ & Báo cáo giám sát AI",
                        "Ollama Registry",
                        "Apache-2.0",
                        "ollama-managed",
                        "Ollama",
                        "GPU/CPU",
                        workerBase + "/conversation/health",
                        "2.1 GB",
                        "{\"temperature\":0.0,\"timeoutSec\":120,\"maxTokens\":5000}"
                },
                new Object[]{
                        "mdl-qwen-asr",
                        "qwen3-asr",
                        "Qwen3-ASR",
                        "v1.0",
                        "ASR",
                        "Chuyển giọng nói đặt đơn thành văn bản",
                        "Hugging Face",
                        "Apache-2.0",
                        "local-cache",
                        "PyTorch",
                        "CPU/GPU",
                        workerBase + "/conversation/health",
                        "1.1 GB",
                        "{\"language\":\"vi\",\"timeoutSec\":45}"
                },
                new Object[]{
                        "mdl-zerotts",
                        "zerotts",
                        "ZeroTTS (VieNeu)",
                        "v1.0",
                        "TTS",
                        "Chuyển văn bản phản hồi thành giọng nói",
                        "Hugging Face",
                        "MIT",
                        "local-cache",
                        "PyTorch",
                        "CPU",
                        workerBase + "/conversation/health",
                        "680 MB",
                        "{\"sampleRate\":48000,\"timeoutSec\":30}"
                },
                new Object[]{
                        "mdl-resource-allocation-rule-engine",
                        "resource_allocation_rule_engine",
                        "Resource Allocation Rule Engine",
                        "deterministic-weighted-v1",
                        "RULE_ENGINE",
                        "Đánh giá ràng buộc cứng (bảo trì, tải nhân sự, nghỉ ngơi) và chấm điểm xác định cho phân bổ nguồn lực",
                        "Internal Domain Engine",
                        "Proprietary",
                        "deterministic-java",
                        "JVM",
                        "CPU",
                        "/api/resource-lifecycle/missions/{missionId}/recommend",
                        "0 MB",
                        "{\"hardConstraintsFirst\":true,\"offlineSafe\":true}"
                }
        );

        for (Object[] model : defaults) {
            db.update("""
                    INSERT INTO ai_models (
                        id, code, name, version, model_type, capability, source, license,
                        checksum_sha256, runtime, device, endpoint, model_size, status, enabled,
                        config_json, entity_version, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'IDLE', true, ?::jsonb, 0, NOW(), NOW())
                    ON CONFLICT (code) DO NOTHING
                    """,
                    model[0], model[1], model[2], model[3], model[4], model[5], model[6],
                    model[7], model[8], model[9], model[10], model[11], model[12], model[13]
            );
        }

        log.debug("Verified default AI model registry seed ({} entries)", defaults.size());
    }

    private String normalizeBase(String url, String fallback) {
        String value = (url == null || url.isBlank()) ? fallback : url.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
