package com.sec.source.doors

import com.sec.api.dto.AttributeCountDto
import com.sec.api.dto.CensusDto
import com.sec.api.dto.CompletenessDto
import com.sec.api.dto.CyclesResponseDto
import com.sec.api.dto.DanglingTargetDto
import com.sec.api.dto.LoopDto
import com.sec.api.dto.LoopMemberDto
import com.sec.api.dto.ModuleStatisticsDto
import com.sec.api.dto.ParentageDto
import com.sec.api.dto.RequirementStatisticsDto
import com.sec.api.dto.SystemLevelOptionDto
import com.sec.domain.Cycles
import com.sec.domain.NodeLabel
import com.sec.domain.Ref
import com.sec.domain.SystemLevel
import com.sec.graph.GraphDriver
import com.sec.graph.cypher.ReviewCypher
import com.sec.graph.cypher.StatisticsCypher
import com.sec.graph.executeRead
import com.sec.security.AccessSet
import org.neo4j.driver.Record
import org.neo4j.driver.Value

/**
 * Read projections for the Statistics view (`docs/features/requirements-statistics.md`).
 *
 * Every number here is computed on read and **nothing is written** — a stored derivation goes
 * stale silently, and R2 excludes derived data from `:__Meta` for exactly that reason.
 *
 * The completeness checks delegate to [DoorsChecks] rather than being re-expressed as Cypher
 * aggregates. That is slower and it is the point: two implementations would let this view and the
 * Req review table disagree about the same module (§3.2).
 */
public class StatisticsProjection(private val graphDriver: GraphDriver) {

    /**
     * The whole view except Band 4, for one module or for all of them.
     *
     * Returns null when [moduleId] names a module that does not exist, so the route can answer
     * 404 rather than an empty page of zeroes.
     */
    public suspend fun getStatistics(moduleId: String?, access: AccessSet): RequirementStatisticsDto? {
        val modules = modulesInScope(moduleId, access)
        if (moduleId != null && modules.isEmpty()) {
            return null
        }

        val perModule = modules.map { module -> module.statistics(access) }

        return RequirementStatisticsDto(
            census = CensusDto(
                modules = perModule.size,
                items = perModule.sumOf { it.completeness.items },
                requirements = perModule.sumOf { it.requirements },
                openPoints = perModule.sumOf { it.completeness.itemsWithOpenPoints },
                links = perModule.sumOf { it.links },
                deletedLinks = perModule.sumOf { it.dto.deletedLinks },
            ),
            modules = perModule.map { it.dto },
            completeness = perModule.map { it.completeness }.rollUp(),
            parentage = perModule.map { it.dto.parentage }.rollUp(),
            mandatoryByAttribute = perModule.flatMap { it.dto.mandatoryByAttribute }.merge(),
            openPointsByAttribute = perModule.flatMap { it.dto.openPointsByAttribute }.merge(),
            danglingTargets = perModule.flatMap { it.danglingTargets }.distinctBy { it.ref },
            // Named, not silently dropped: "above L0" is undefined without a level, so a module
            // with none is excluded from the ratio and the view says which ones (§6.1).
            modulesWithoutSystemLevel = modules.filter { it.levelCode == null }.map { it.name },
            truncated = perModule.any { it.dto.truncated },
        )
    }

    /**
     * Band 4 — every closed loop in the trace graph (§7).
     *
     * The SCC always runs over the **whole** graph and the module filter is applied to the
     * findings afterwards (§7.2). Filtering the edge set first would be faster and would hide
     * exactly the loops worth finding: one that leaves a module and comes back.
     */
    public suspend fun getCycles(moduleId: String?, access: AccessSet): CyclesResponseDto {
        val edges = graphDriver.executeRead(
            StatisticsCypher.ALL_TRACE_EDGES, mapOf("limit" to MAX_EDGES), access,
        ) { records ->
            records.map { Cycles.Edge(it.get("fromId").asString(""), it.get("toId").asString("")) }
        }

        val loops = Cycles.find(edges)
        if (loops.isEmpty()) {
            return CyclesResponseDto(emptyList(), edges.size, edges.size >= MAX_EDGES)
        }

        val members = loopMembers(loops.flatMap { it.members }.distinct(), access)
        val visible = loops.filter { loop ->
            moduleId == null || loop.members.any { members[it]?.moduleId == moduleId }
        }

        return CyclesResponseDto(
            loops = visible.map { loop ->
                LoopDto(
                    ring = loop.ring.map { members.dtoFor(it) },
                    others = loop.others.map { members.dtoFor(it) },
                )
            },
            edgesExamined = edges.size,
            truncated = edges.size >= MAX_EDGES,
        )
    }

    // --- Per module -------------------------------------------------------------------------

    private data class ModuleInScope(val id: String, val name: String, val levelCode: String?)

    /** What one module contributes, kept alongside the DTO so the rollups need no re-derivation. */
    private class ModuleResult(
        val dto: ModuleStatisticsDto,
        val completeness: CompletenessDto,
        val requirements: Int,
        val links: Int,
        val danglingTargets: List<DanglingTargetDto>,
    )

    private suspend fun modulesInScope(moduleId: String?, access: AccessSet): List<ModuleInScope> =
        graphDriver.executeRead(
            StatisticsCypher.MODULES_IN_SCOPE,
            mapOf("moduleId" to moduleId, "limit" to MAX_MODULES),
            access,
        ) { records ->
            records.map {
                ModuleInScope(
                    id = it.get("id").asString(""),
                    name = it.get("name").asString(""),
                    levelCode = it.optionalString("levelCode"),
                )
            }
        }

    private suspend fun ModuleInScope.statistics(access: AccessSet): ModuleResult {
        val policies = mandatoryPolicies(id, access)
        // One read, two answers: both roles live on the same :__AttributeSetting nodes, so asking
        // separately would be two round trips for one row set.
        val settings = attributeSettings(id, access)
        val verificationAttributes = settings.named { it.get("verification") }
        val excludedFromOpenPoints = settings.named { it.get("excludedFromOpenPoints") }
        val total = objectCount(id, access)

        // "Above L0" is read from the module, because a requirement carries no level of its own.
        // L0 has nothing above it to refine, so the orphan question does not apply to it.
        val level = levelCode?.let(SystemLevel::fromCode)
        val parentageApplies = level != null && level != SystemLevel.L0

        val tally = Tally()
        graphDriver.executeRead(
            StatisticsCypher.MODULE_OBJECTS,
            mapOf("moduleUrl" to id, "limit" to MAX_OBJECTS_PER_MODULE),
            access,
        ) { records ->
            records.forEach {
                tally.add(it, policies, verificationAttributes, excludedFromOpenPoints, parentageApplies)
            }
        }

        val dangling = danglingTargets(id, access)

        return ModuleResult(
            dto = ModuleStatisticsDto(
                ref = Ref.encode(id),
                name = name,
                systemLevel = level?.let { SystemLevelOptionDto(it.code, it.label) },
                completeness = tally.completeness(policies.isNotEmpty(), verificationAttributes.isNotEmpty()),
                parentage = tally.parentage(parentageApplies),
                mandatoryByAttribute = tally.mandatoryByAttribute.toCounts(),
                openPointsByAttribute = tally.openPointsByAttribute.toCounts(),
                links = tally.links,
                danglingLinks = tally.danglingLinks,
                deletedLinks = tally.deletedLinks,
                truncated = tally.items + tally.placeholders < total,
            ),
            completeness = tally.completeness(policies.isNotEmpty(), verificationAttributes.isNotEmpty()),
            requirements = tally.requirements,
            links = tally.links,
            danglingTargets = dangling,
        )
    }

    /**
     * Everything one pass over a module's objects accumulates.
     *
     * Mutable and local to a single module — it never escapes [statistics], and the read transform
     * that fills it is only ever invoked once, so `executeRead`'s retry contract (which requires a
     * pure transform) is not violated by a retry re-running the fold: a retry re-runs the whole
     * query into a fresh tally.
     */
    private class Tally {
        var items: Int = 0
        var placeholders: Int = 0
        var requirements: Int = 0
        var itemsWithOpenPoints: Int = 0
        var mandatoryViolations: Int = 0
        var itemsMissingMandatory: Int = 0
        var verificationViolations: Int = 0
        var itemsMissingVerification: Int = 0
        var itemsClean: Int = 0
        var links: Int = 0
        var danglingLinks: Int = 0

        /**
         * Counted in both directions, and counted before the placeholder test below.
         *
         * Both directions, because the module is equally responsible for a link it asserts into a
         * deleted object and for one a deleted object asserts into it -- and because ghosts are
         * excluded from this scan, the other end of every such edge is outside it, so nothing is
         * counted twice.
         */
        var deletedLinks: Int = 0
        var hasParent: Int = 0
        var parentNotImported: Int = 0
        var orphans: Int = 0
        val mandatoryByAttribute: MutableMap<String, Int> = linkedMapOf()
        val openPointsByAttribute: MutableMap<String, Int> = linkedMapOf()

        fun add(
            record: Record,
            policies: List<DoorsChecks.MandatoryPolicy>,
            verificationAttributes: Set<String>,
            excludedFromOpenPoints: Set<String>,
            parentageApplies: Boolean,
        ) {
            val labels = record.get("labels").asList { it.asString() }
            val resolvedParents = record.get("resolvedParents").asInt(0)
            val placeholderParents = record.get("placeholderParents").asInt(0)

            links += resolvedParents + placeholderParents
            danglingLinks += placeholderParents
            deletedLinks += record.get("deletedLinks").asInt(0)

            // A placeholder stands for an object no import has reached. Counting it as an item
            // would inflate every total in proportion to how much is *missing* (§3.1).
            if (DoorsChecks.isPlaceholder(labels)) {
                placeholders++
                return
            }

            val props = record.get("object").asNode().asMap()
            items++
            if (DoorsChecks.isRequirementLike(labels)) {
                requirements++
            }

            val openPoints = DoorsChecks.openPointAttributes(labels, props, excludedFromOpenPoints)
            val missingMandatory = DoorsChecks.missingMandatory(policies, labels, props)
            val missingVerification =
                DoorsChecks.missingVerification(verificationAttributes, labels, props)

            if (openPoints.isNotEmpty()) {
                itemsWithOpenPoints++
                openPoints.forEach { openPointsByAttribute.merge(it, 1, Int::plus) }
            }
            if (missingMandatory.isNotEmpty()) {
                itemsMissingMandatory++
                mandatoryViolations += missingMandatory.size
                missingMandatory.forEach { mandatoryByAttribute.merge(it, 1, Int::plus) }
            }
            if (missingVerification.isNotEmpty()) {
                itemsMissingVerification++
                verificationViolations += missingVerification.size
            }
            if (openPoints.isEmpty() && missingMandatory.isEmpty() && missingVerification.isEmpty()) {
                itemsClean++
            }

            if (parentageApplies && DoorsChecks.isRequirementLike(labels)) {
                when {
                    resolvedParents > 0 -> hasParent++
                    placeholderParents > 0 -> parentNotImported++
                    else -> orphans++
                }
            }
        }

        fun completeness(mandatoryConfigured: Boolean, verificationConfigured: Boolean) =
            CompletenessDto(
                items = items,
                itemsWithOpenPoints = itemsWithOpenPoints,
                mandatoryConfigured = mandatoryConfigured,
                mandatoryViolations = mandatoryViolations,
                itemsMissingMandatory = itemsMissingMandatory,
                verificationConfigured = verificationConfigured,
                verificationViolations = verificationViolations,
                itemsMissingVerification = itemsMissingVerification,
                itemsClean = itemsClean,
            )

        fun parentage(applicable: Boolean) =
            ParentageDto(applicable, hasParent, parentNotImported, orphans)
    }

    // --- Supporting reads -------------------------------------------------------------------

    // Reused verbatim from ReviewCypher: a second copy of this statement would be a second
    // definition of what a mandatory attribute is (§3.2).
    private suspend fun mandatoryPolicies(
        moduleId: String,
        access: AccessSet,
    ): List<DoorsChecks.MandatoryPolicy> =
        graphDriver.executeRead(
            ReviewCypher.MANDATORY_POLICIES, mapOf("moduleId" to moduleId), access,
        ) { records ->
            records.map {
                DoorsChecks.MandatoryPolicy(
                    attributeName = it.get("attributeName").asString(""),
                    appliesToLabels = it.get("appliesToLabels").asList { v -> v.asString() }.toSet(),
                )
            }
        }

    /**
     * This module's `:__AttributeSetting` rows, read once.
     *
     * Returned raw rather than as one set, because two roles are read off them — which attribute
     * proves verification, and which the TBD / TBC scan must skip — and a method per role would be
     * a round trip per role over one row set.
     */
    private suspend fun attributeSettings(moduleId: String, access: AccessSet): List<Record> =
        graphDriver.executeRead(
            ReviewCypher.EXISTING_ATTRIBUTE_SETTINGS, mapOf("moduleId" to moduleId), access,
        ) { records -> records.toList() }

    /** The attribute names whose row has [flag] set. */
    private fun List<Record>.named(flag: (Record) -> Value): Set<String> =
        filter { flag(it).asBoolean(false) }.mapTo(linkedSetOf()) { it.get("name").asString("") }

    private suspend fun objectCount(moduleId: String, access: AccessSet): Int =
        graphDriver.executeRead(
            StatisticsCypher.COUNT_MODULE_OBJECTS, mapOf("moduleUrl" to moduleId), access,
        ) { records -> records.firstOrNull()?.get("total")?.asInt() ?: 0 }

    private suspend fun danglingTargets(moduleId: String, access: AccessSet): List<DanglingTargetDto> =
        graphDriver.executeRead(
            StatisticsCypher.DANGLING_TARGET_MODULES,
            mapOf("moduleUrl" to moduleId, "limit" to MAX_MODULES),
            access,
        ) { records ->
            records.mapNotNull { record ->
                record.optionalString("id")?.let {
                    DanglingTargetDto(ref = Ref.encode(it), name = record.optionalString("name"))
                }
            }
        }

    private suspend fun loopMembers(ids: List<String>, access: AccessSet): Map<String, LoopMember> =
        graphDriver.executeRead(
            StatisticsCypher.LOOP_MEMBERS, mapOf("ids" to ids), access,
        ) { records -> records.associate { it.get("id").asString("") to it.toLoopMember() } }

    private class LoopMember(val moduleId: String?, val dto: LoopMemberDto)

    private fun Record.toLoopMember(): LoopMember {
        val labels = get("labels").asList { it.asString() }
        val moduleId = optionalString("moduleId")
        return LoopMember(
            moduleId = moduleId,
            dto = LoopMemberDto(
                ref = Ref.encode(get("id").asString("")),
                // Display only, never a key (R6). Absent on a placeholder, and never falls back
                // to __name, which would put an internal value where an id is expected (R5).
                id = optionalString("sourceId").takeIf { NodeLabel.UNDEFINED !in labels },
                name = get("name").asString(""),
                moduleRef = moduleId?.let(Ref::encode),
                moduleName = optionalString("moduleName"),
                systemLevel = optionalString("levelCode")
                    ?.let(SystemLevel::fromCode)
                    ?.let { SystemLevelOptionDto(it.code, it.label) },
            ),
        )
    }

    /**
     * A loop member the detail query did not return — possible only if the graph changed between
     * the edge read and this one. Rendered as a known ref with no wording rather than dropped:
     * silently shortening a ring would make the loop stop reading as a loop.
     */
    private fun Map<String, LoopMember>.dtoFor(id: String): LoopMemberDto =
        this[id]?.dto ?: LoopMemberDto(Ref.encode(id), null, "", null, null, null)

    // --- Rollups ----------------------------------------------------------------------------

    private fun List<CompletenessDto>.rollUp(): CompletenessDto =
        CompletenessDto(
            items = sumOf { it.items },
            itemsWithOpenPoints = sumOf { it.itemsWithOpenPoints },
            // Across a scope, "configured" means at least one module is. A scope where none are
            // must not read as clean, which is the whole reason this flag travels (§3.4).
            mandatoryConfigured = any { it.mandatoryConfigured },
            mandatoryViolations = sumOf { it.mandatoryViolations },
            itemsMissingMandatory = sumOf { it.itemsMissingMandatory },
            verificationConfigured = any { it.verificationConfigured },
            verificationViolations = sumOf { it.verificationViolations },
            itemsMissingVerification = sumOf { it.itemsMissingVerification },
            itemsClean = sumOf { it.itemsClean },
        )

    private fun List<ParentageDto>.rollUp(): ParentageDto =
        ParentageDto(
            applicable = any { it.applicable },
            hasParent = sumOf { it.hasParent },
            parentNotImported = sumOf { it.parentNotImported },
            orphans = sumOf { it.orphans },
        )

    private fun Map<String, Int>.toCounts(): List<AttributeCountDto> =
        entries
            .map { AttributeCountDto(it.key, it.value) }
            .sortedWith(compareByDescending<AttributeCountDto> { it.violations }.thenBy { it.attribute })

    private fun List<AttributeCountDto>.merge(): List<AttributeCountDto> =
        groupingBy { it.attribute }
            .fold(0) { acc, entry -> acc + entry.violations }
            .toCounts()

    private fun Record.optionalString(key: String): String? =
        get(key).takeUnless { it.isNull() }?.asString()

    private companion object {
        const val MAX_MODULES = 500

        // Safety nets, not expected bounds — Community has no query governor (CLAUDE.md §7). The
        // reference module is 977 objects and the whole graph a few thousand edges, so hitting
        // either of these means something is wrong, and the response says so rather than quietly
        // reporting a number computed over part of the data.
        const val MAX_OBJECTS_PER_MODULE = 20_000
        const val MAX_EDGES = 200_000
    }
}
