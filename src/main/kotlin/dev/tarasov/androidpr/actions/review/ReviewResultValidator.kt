package dev.tarasov.androidpr.actions.review

import dev.tarasov.androidpr.actions.config.EvidenceSources
import dev.tarasov.androidpr.actions.domain.AiReviewResult
import dev.tarasov.androidpr.actions.domain.Evidence
import dev.tarasov.androidpr.actions.domain.RequirementStatus
import dev.tarasov.androidpr.actions.domain.ReviewContext
import dev.tarasov.androidpr.actions.domain.SourceFile

object ReviewResultValidator {
    fun validate(result: AiReviewResult, context: ReviewContext): AiReviewResult {
        val sources = buildMap {
            context.files.forEach { put(it.path.normalizedPath(), EvidenceSource.from(it)) }
            put(
                EvidenceSources.PULL_REQUEST_DESCRIPTION,
                EvidenceSource(
                    lineCount = buildString {
                        appendLine(context.pullRequest.title)
                        append(context.pullRequest.body)
                    }.lineCount(),
                ),
            )
        }
        var discardedReferences = 0

        fun validatedEvidence(evidence: Evidence): Evidence? {
            val normalizedPath = evidence.path.normalizedPath()
            val source = sources[normalizedPath]
            if (source == null || !source.accepts(evidence.line)) {
                discardedReferences++
                return null
            }
            return evidence.copy(path = normalizedPath)
        }

        val requirements = result.requirements.map { requirement ->
            val evidence = requirement.evidence.mapNotNull(::validatedEvidence)
            if (requirement.status != RequirementStatus.NOT_VERIFIABLE && evidence.isEmpty()) {
                requirement.copy(
                    status = RequirementStatus.NOT_VERIFIABLE,
                    summary = "${requirement.summary} $MISSING_VERIFIABLE_EVIDENCE",
                    evidence = emptyList(),
                )
            } else {
                requirement.copy(evidence = evidence)
            }
        }
        val findings = result.findings.mapNotNull { finding ->
            val path = finding.path
            if (path == null) return@mapNotNull finding
            val normalizedPath = path.normalizedPath()
            val source = sources[normalizedPath]
            if (source == null || !source.accepts(finding.line)) {
                discardedReferences++
                null
            } else {
                finding.copy(path = normalizedPath)
            }
        }
        val limitations = result.limitations.toMutableList()
        if (discardedReferences > 0) {
            limitations += "$DISCARDED_REFERENCES_PREFIX$discardedReferences"
        }
        return result.copy(
            requirements = requirements,
            findings = findings,
            limitations = limitations.distinct(),
        )
    }

    private data class EvidenceSource(
        val lineCount: Int,
        val additionalLineRanges: List<IntRange> = emptyList(),
    ) {
        fun accepts(line: Int?): Boolean = when {
            line == null -> true
            line <= 0 -> false
            line <= lineCount -> true
            else -> additionalLineRanges.any { line in it }
        }

        companion object {
            fun from(file: SourceFile): EvidenceSource = EvidenceSource(
                lineCount = file.content.lineCount(),
                additionalLineRanges = if (file.status == REMOVED_STATUS) {
                    file.patch.orEmpty().removedLineRanges()
                } else {
                    emptyList()
                },
            )
        }
    }
}

private fun String.normalizedPath(): String = trim().removeSurrounding("`").removePrefix("./")

private fun String.lineCount(): Int = if (isEmpty()) 0 else lineSequence().count()

private fun String.removedLineRanges(): List<IntRange> = lineSequence().mapNotNull { line ->
    val match = DIFF_HUNK_PATTERN.find(line) ?: return@mapNotNull null
    val start = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
    val count = match.groupValues[2].ifEmpty { "1" }.toIntOrNull() ?: return@mapNotNull null
    if (count <= 0) null else start..(start + count - 1)
}.toList()

private const val REMOVED_STATUS = "removed"
private val DIFF_HUNK_PATTERN = Regex("^@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+\\d+(?:,\\d+)?\\s+@@")
private const val MISSING_VERIFIABLE_EVIDENCE =
    "The claimed status was downgraded because no valid repository evidence was supplied."
private const val DISCARDED_REFERENCES_PREFIX =
    "The reviewer discarded invalid or out-of-range AI evidence references: "
