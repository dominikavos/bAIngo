package com.example.meetingbingo.data

/**
 * Collection of common meeting buzzwords and phrases used in corporate environments.
 * These are the words that will appear on the Bingo card.
 */
object MeetingWords {

    val words = listOf(
        // Corporate buzzwords
        "synergy",
        "leverage",
        "bandwidth",
        "paradigm",
        "scalable",
        "proactive",
        "stakeholder",
        "alignment",
        "deliverable",
        "actionable",
        "optimize",
        "streamline",
        "innovative",
        "disruption",
        "ecosystem",

        // Common phrases (single keyword versions)
        "circle back",
        "deep dive",
        "touch base",
        "take offline",
        "move forward",
        "drill down",
        "level set",
        "pivot",
        "unpack",
        "workshop",

        // Meeting specific
        "agenda",
        "action items",
        "follow up",
        "next steps",
        "timeline",
        "milestone",
        "deadline",
        "priority",
        "blocker",
        "dependency",

        // Agile/Scrum terms
        "sprint",
        "backlog",
        "standup",
        "retrospective",
        "velocity",
        "epic",
        "user story",
        "scrum",
        "kanban",
        "iteration",

        // Business terms
        "ROI",
        "KPI",
        "metrics",
        "pipeline",
        "roadmap",
        "strategy",
        "initiative",
        "objective",
        "target",
        "forecast",

        // Tech buzzwords
        "cloud",
        "AI",
        "machine learning",
        "blockchain",
        "digital",
        "platform",
        "integration",
        "automation",
        "analytics",
        "data-driven",

        // Collaboration terms
        "collaborate",
        "cross-functional",
        "team player",
        "empower",
        "ownership",
        "accountability",
        "transparency",
        "visibility",
        "feedback",
        "iterate",

        // More corporate favorites
        "low-hanging fruit",
        "best practices",
        "value-add",
        "win-win",
        "think outside the box",
        "move the needle",
        "game changer",
        "bleeding edge",
        "core competency",
        "mission critical"
    )

    /**
     * Get a shuffled list of words for a new bingo card.
     * Returns 24 words (for a 5x5 grid with a free center space).
     */
    fun getRandomWords(count: Int = 24): List<String> {
        return words.shuffled().take(count)
    }
}
