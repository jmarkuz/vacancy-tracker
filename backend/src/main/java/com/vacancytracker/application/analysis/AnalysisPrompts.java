package com.vacancytracker.application.analysis;

import com.vacancytracker.application.ai.AiClient.AiPrompt;
import com.vacancytracker.application.documents.Prompts;
import com.vacancytracker.application.preset.Preset;
import com.vacancytracker.vacancy.Vacancy;
import java.util.List;

/**
 * The prompts of the vacancy analysis (T-095, ADR-049). Each starts with the guardrail, keeps the vacancy in its own
 * data block (see {@link Prompts}) and asks for JSON only; the server checks and scores the answer.
 */
final class AnalysisPrompts {

    static final int MAX_TOKENS = 6000;
    static final String CHECKLIST_TASK = "Build the requirement checklist.";
    static final String STATUS_TASK = "Assess the document against the checklist.";
    static final String GOAL_TASK = "Rate the vacancy against the goal.";

    private AnalysisPrompts() {
    }

    static AiPrompt checklist(String guardrail, Vacancy vacancy) {
        String system = guardrail + "\n\n" + """
                You analyse one job vacancy for a candidate. Separate real requirements from wish-list items, find the core \
                problem this hire should solve, and note terms the vacancy repeats; ignore generic filler.
                Answer with one JSON object and nothing else:
                {"coreProblem": "one sentence",
                 "requirements": [{"text": "what is required, in a few words", "priority": "must" | "important" | "nice",
                                   "terms": ["the vacancy's own words for it"]}]}
                At most 15 requirements, the most important first.""";
        return new AiPrompt(system, Prompts.vacancy(vacancy) + "Task: " + CHECKLIST_TASK + "\n", MAX_TOKENS);
    }

    static AiPrompt status(String guardrail, String checklistJson, String document) {
        String system = guardrail + "\n\n" + """
                You check how a candidate's document covers a vacancy's requirements. For every requirement give a status:
                covered (the document proves it in experience, projects or education), partial (only named, for example in a \
                skills list, or only part of it is shown) or missing. For covered and partial copy a short quote word for word \
                from the document as the proof. Never invent a quote.
                Answer with one JSON object and nothing else:
                {"items": [{"id": "r1", "status": "covered" | "partial" | "missing", "quote": "exact words from the document"}]}""";
        StringBuilder user = new StringBuilder();
        Prompts.block(user, "checklist", checklistJson);
        Prompts.block(user, "document", document);
        user.append("Task: ").append(STATUS_TASK).append('\n');
        return new AiPrompt(system, user.toString(), MAX_TOKENS);
    }

    static AiPrompt goal(String guardrail, String mainGoal, List<Preset.Weight> weights, Vacancy vacancy) {
        String system = guardrail + "\n\n" + """
                You rate how well one job vacancy fits the candidate's goal. Rate every parameter listed in the goal from 0 \
                (does not fit) to 100 (fits completely), with one short sentence why, based only on the vacancy text; when \
                the vacancy says nothing about a parameter, give 50 and say so. Report a deal-breaker from the goal that the \
                vacancy clearly hits.
                Answer with one JSON object and nothing else:
                {"parameters": [{"name": "parameter name as given", "score": 0, "explanation": "why"}],
                 "dealBreaker": {"found": false, "explanation": "which one and why, or empty"}}""";
        return new AiPrompt(system, Prompts.goal(mainGoal, weights) + Prompts.vacancy(vacancy) + "Task: " + GOAL_TASK + "\n", MAX_TOKENS);
    }
}
