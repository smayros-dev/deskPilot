package pl.piomin.services.deskpilot.service;

/** Audit wording shared by the scheduled and the manual detection. */
public final class KnowledgeGapAudit {

    private KnowledgeGapAudit() {
    }

    public static String describe(KnowledgeGapService.Detection d) {
        return d.candidates() + " unanswered questions (" + d.covered() + " answered by an article since), "
                + d.newGaps() + " new gaps, " + d.grownGaps() + " gaps with new questions, " + d.unassigned() + " not grouped yet";
    }
}
