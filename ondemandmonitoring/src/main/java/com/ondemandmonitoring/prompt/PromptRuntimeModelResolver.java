package com.ondemandmonitoring.prompt;

import com.ondemandmonitoring.ai.client.ChecklistImageVisionClient;
import com.ondemandmonitoring.prompt.PromptDtos.PromptRuntimeModelView;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Runtime information is read from the inference client, never inferred from prompt metadata. */
@Component
@RequiredArgsConstructor
public class PromptRuntimeModelResolver {
    // Resolve at request time: the vision client itself consumes PromptManagementService.
    private final ObjectProvider<ChecklistImageVisionClient> vision;

    public PromptRuntimeModelView resolve(String promptKey) {
        if (!ChecklistVisionPlaygroundService.PROMPT_KEY.equals(promptKey)) return null;
        var client = vision.getIfAvailable();
        if (client == null) return new PromptRuntimeModelView(null, null, false);
        var deployment = client.deployment();
        return new PromptRuntimeModelView(
                deployment.provider(), deployment.model(), deployment.configured());
    }
}
