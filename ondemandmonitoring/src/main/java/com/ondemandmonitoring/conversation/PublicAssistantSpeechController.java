package com.ondemandmonitoring.conversation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public text-to-speech endpoint for the landing page AI voice guide,
 * reusing the same ZeroTTS (Mai Chi) voice pipeline as the Order assistant.
 */
@RestController
@RequestMapping("/api/public/assistant")
@RequiredArgsConstructor
public class PublicAssistantSpeechController {

    private final OrderConversationService conversationService;
    private final PublicLandingChatService landingChatService;

    public record PublicSpeechRequest(
            @NotBlank @Size(max = 800) String text) {
    }

    public record PublicChatRequest(
            @NotBlank @Size(max = 500) String message,
            @Size(max = 5) String lang,
            @Size(max = 8) java.util.List<PublicLandingChatService.ChatTurn> history) {
    }

    /** Landing-page assistant answers from the curated knowledge base through the local LLM (RAG). */
    @PostMapping("/chat")
    public com.ondemandmonitoring.common.api.ApiResponse<PublicLandingChatService.ChatReply> chat(
            @Valid @RequestBody PublicChatRequest input, jakarta.servlet.http.HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        String client = forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : http.getRemoteAddr();
        return com.ondemandmonitoring.common.api.ApiResponse.ok(
                landingChatService.chat(client, input.message(), input.lang(), input.history()));
    }

    @PostMapping(value = "/speech", produces = "audio/wav")
    public ResponseEntity<byte[]> speech(@Valid @RequestBody PublicSpeechRequest input) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(conversationService.synthesizeSpeech(input.text()));
    }
}
