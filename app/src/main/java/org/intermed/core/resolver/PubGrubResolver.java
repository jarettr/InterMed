package org.intermed.core.resolver;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.intermed.core.config.RuntimeConfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dependency resolver for InterMed using a conflict-driven PubGrub-style kernel
 * over concrete package-version literals.
 *
 * <p>Each available package version becomes a solver variable, package
 * exclusivity and dependency requirements become incompatibility clauses, and
 * resolution proceeds through deterministic decisions, unit propagation,
 * conflict analysis and learned clauses.
 */
public class PubGrubResolver {

    private static final Gson GSON = new Gson();

    /**
     * Structured description of a single available package version.
     *
     * @param id           Package identifier, e.g. {@code "fabric-api"}.
     * @param version      Concrete package version, e.g. {@code "1.2.3"}.
     * @param dependencies Direct dependency constraints declared by this version.
     */
    public record ModManifest(
        String id,
        String version,
        List<DepSpec> dependencies
    ) {
        public record DepSpec(String id, String versionConstraint) {}
    }

    /**
     * Backward-compatible JSON API.
     */
    public String resolve(String jsonManifests) {
        try {
            JsonArray arr = GSON.fromJson(jsonManifests, JsonArray.class);
            List<ModManifest> manifests = new ArrayList<>();
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                String id = obj.get("id").getAsString();
                String ver = obj.get("version").getAsString();
                List<ModManifest.DepSpec> deps = new ArrayList<>();
                if (obj.has("dependencies")) {
                    for (JsonElement d : obj.getAsJsonArray("dependencies")) {
                        JsonObject depObj = d.getAsJsonObject();
                        deps.add(new ModManifest.DepSpec(
                            depObj.get("id").getAsString(),
                            depObj.has("version") ? depObj.get("version").getAsString() : "*"
                        ));
                    }
                }
                manifests.add(new ModManifest(id, ver, deps));
            }

            ResolvedPlan plan = resolvePlan(manifests);
            if (!plan.softMissingDependencies().isEmpty() && !RuntimeConfig.get().isResolverFallbackEnabled()) {
                JsonObject error = new JsonObject();
                error.addProperty("status", "error");
                error.addProperty(
                    "message",
                    "Missing dependency declarations: " + String.join(", ", plan.softMissingDependencies())
                );
                return GSON.toJson(error);
            }

            JsonObject result = new JsonObject();
            result.addProperty("status", "success");
            JsonObject packages = new JsonObject();
            for (Map.Entry<String, String> entry : plan.resolvedVersions().entrySet()) {
                packages.addProperty(entry.getKey(), entry.getValue());
            }
            result.add("packages", packages);
            return GSON.toJson(result);
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("status", "error");
            error.addProperty("message", e.getMessage());
            return GSON.toJson(error);
        }
    }

    /**
     * Resolves the supplied manifests into one concrete version per package ID.
     *
     * <p>When a declared dependency is absent from the candidate map (i.e. no JAR
     * provides it), resolution fails fast by default. Only when
     * {@code resolver.allow.fallback=true} is explicitly enabled do missing
     * packages become <em>soft-missing</em>: the dependant package is then
     * resolved without that edge, and the missing ID is recorded in
     * {@link ResolvedPlan#softMissingDependencies()}.
     *
     * @throws IllegalStateException only if no compatible version assignment exists
     *                               for packages that <em>are</em> present.
     */
    public ResolvedPlan resolvePlan(List<ModManifest> manifests) {
        if (manifests == null || manifests.isEmpty()) {
            return ResolvedPlan.EMPTY;
        }

        Map<String, List<Candidate>> candidatesById = normaliseCandidates(manifests);
        Map<String, RuntimeModuleKind> moduleKinds = inferModuleKinds(candidatesById.keySet());
        List<ResolutionDiagnostic> diagnostics = new ArrayList<>();

        boolean allowSoftMissing = RuntimeConfig.get().isResolverFallbackEnabled();
        Set<String> softMissing = new LinkedHashSet<>();
        for (List<Candidate> candidates : candidatesById.values()) {
            for (Candidate candidate : candidates) {
                for (Dependency dep : candidate.dependencies()) {
                    if (!candidatesById.containsKey(dep.id())) {
                        if (!allowSoftMissing) {
                            throw new ResolutionException(
                                "MISSING_DEPENDENCY",
                                dep.id(),
                                List.of(candidate.displayName() + " requires " + dep.id() + " " + dep.rawConstraint()),
                                List.of(),
                                "Missing dependency '" + dep.id() + "' required by " + candidate.displayName()
                            );
                        }
                        softMissing.add(dep.id());
                        diagnostics.add(new ResolutionDiagnostic(
                            ResolutionDiagnostic.Severity.WARNING,
                            "SOFT_MISSING_DEPENDENCY",
                            dep.id(),
                            "Missing dependency '" + dep.id() + "' required by " + candidate.displayName()
                                + " — fallback mode kept the plan degraded but loadable."
                        ));
                        System.out.printf(
                            "[PubGrub] Soft-missing dependency '%s' (required by %s) — will degrade gracefully.%n",
                            dep.id(), candidate.displayName()
                        );
                    }
                }
            }
        }

        Map<String, List<Candidate>> prunedCandidates = pruneSoftMissing(candidatesById, softMissing);
        Problem problem = encodeProblem(prunedCandidates);
        SolverOutcome outcome = new Solver(problem).solve();
        if (!outcome.success()) {
            throw outcome.failure();
        }

        Map<String, String> resolvedVersions = new LinkedHashMap<>();
        Map<String, Set<String>> dependencyEdges = new LinkedHashMap<>();
        for (Map.Entry<String, Candidate> entry : outcome.selected().entrySet()) {
            Candidate candidate = entry.getValue();
            resolvedVersions.put(entry.getKey(), candidate.version());
            dependencyEdges.put(entry.getKey(), candidate.dependencyIds());
        }

        List<String> loadOrder = topologicalOrder(
            resolvedVersions.keySet(),
            dependencyEdges
        );

        return new ResolvedPlan(
            Collections.unmodifiableMap(resolvedVersions),
            Collections.unmodifiableMap(dependencyEdges),
            Collections.unmodifiableList(loadOrder),
            Collections.unmodifiableSet(softMissing),
            Collections.unmodifiableMap(moduleKinds),
            Collections.unmodifiableList(diagnostics)
        );
    }

    /**
     * Returns a copy of {@code candidatesById} where every soft-missing dep ID
     * has been removed from each candidate's dependency list so the solver never
     * attempts to satisfy it.
     */
    private static Map<String, List<Candidate>> pruneSoftMissing(
        Map<String, List<Candidate>> candidatesById,
        Set<String> softMissing
    ) {
        if (softMissing.isEmpty()) {
            return candidatesById;
        }

        Map<String, List<Candidate>> pruned = new LinkedHashMap<>();
        for (Map.Entry<String, List<Candidate>> entry : candidatesById.entrySet()) {
            List<Candidate> prunedList = new ArrayList<>();
            for (Candidate candidate : entry.getValue()) {
                List<Dependency> filteredDeps = candidate.dependencies().stream()
                    .filter(dependency -> !softMissing.contains(dependency.id()))
                    .toList();
                Set<String> filteredIds = candidate.dependencyIds().stream()
                    .filter(id -> !softMissing.contains(id))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
                prunedList.add(new Candidate(
                    candidate.id(),
                    candidate.version(),
                    Collections.unmodifiableList(filteredDeps),
                    Collections.unmodifiableSet(filteredIds)
                ));
            }
            pruned.put(entry.getKey(), prunedList);
        }
        return pruned;
    }

    private Map<String, List<Candidate>> normaliseCandidates(List<ModManifest> manifests) {
        Map<String, List<Candidate>> candidatesById = new LinkedHashMap<>();

        List<ModManifest> orderedManifests = new ArrayList<>(manifests);
        orderedManifests.sort(
            Comparator.comparing(ModManifest::id)
                .thenComparing(
                    (ModManifest manifest) -> SemVerConstraint.SemVer.parse(manifest.version()),
                    Comparator.reverseOrder()
                )
        );

        for (ModManifest manifest : orderedManifests) {
            String packageId = Objects.requireNonNull(manifest.id(), "manifest.id");
            String version = Objects.requireNonNull(manifest.version(), "manifest.version");
            List<Dependency> dependencies = new ArrayList<>();
            Set<String> dependencyIds = new LinkedHashSet<>();

            List<ModManifest.DepSpec> orderedDeps = manifest.dependencies() == null
                ? List.of()
                : new ArrayList<>(manifest.dependencies());
            orderedDeps.sort(Comparator
                .comparing(ModManifest.DepSpec::id)
                .thenComparing(dep -> dep.versionConstraint() == null ? "*" : dep.versionConstraint()));

            for (ModManifest.DepSpec dep : orderedDeps) {
                String substituted = VirtualDependencyMap.substituteChecked(dep.id());
                if (!substituted.equals(dep.id())) {
                    System.out.printf("[PubGrub] Virtual sub: %s → %s  (declared by %s@%s)%n",
                        dep.id(), substituted, packageId, version);
                }
                String rawConstraint = VirtualDependencyMap.effectiveConstraint(dep.id(), dep.versionConstraint());
                if (!Objects.equals(rawConstraint, dep.versionConstraint())
                    && VirtualDependencyMap.isVirtual(dep.id())) {
                    System.out.printf("[PubGrub] Virtual constraint override: %s %s → %s%n",
                        dep.id(), dep.versionConstraint(), rawConstraint);
                }
                dependencies.add(new Dependency(
                    substituted,
                    SemVerConstraint.parse(rawConstraint),
                    rawConstraint == null ? "*" : rawConstraint
                ));
                dependencyIds.add(substituted);
            }

            Candidate candidate = new Candidate(
                packageId,
                version,
                Collections.unmodifiableList(dependencies),
                Collections.unmodifiableSet(dependencyIds)
            );

            candidatesById.computeIfAbsent(packageId, ignored -> new ArrayList<>()).add(candidate);
        }

        Comparator<Candidate> byVersionDescending = (left, right) ->
            SemVerConstraint.SemVer.parse(right.version()).compareTo(SemVerConstraint.SemVer.parse(left.version()));

        candidatesById.values().forEach(list -> {
            list.sort(byVersionDescending);
            dedupeCandidates(list);
        });

        return candidatesById;
    }

    private void dedupeCandidates(List<Candidate> candidates) {
        Set<String> seen = new LinkedHashSet<>();
        candidates.removeIf(candidate -> !seen.add(candidate.id() + "@" + candidate.version()));
    }

    private Map<String, RuntimeModuleKind> inferModuleKinds(Collection<String> packageIds) {
        Map<String, RuntimeModuleKind> kinds = new LinkedHashMap<>();
        packageIds.stream()
            .sorted()
            .forEach(packageId -> kinds.put(packageId, RuntimeModuleKind.forModuleId(packageId)));
        return kinds;
    }

    private Problem encodeProblem(Map<String, List<Candidate>> candidatesById) {
        List<VersionVar> vars = new ArrayList<>();
        Map<String, List<Integer>> packageVarIndices = new LinkedHashMap<>();

        int nextIndex = 0;
        for (String packageId : candidatesById.keySet()) {
            List<Integer> indices = new ArrayList<>();
            for (Candidate candidate : candidatesById.getOrDefault(packageId, List.of())) {
                vars.add(new VersionVar(nextIndex, packageId, candidate));
                indices.add(nextIndex);
                nextIndex++;
            }
            packageVarIndices.put(packageId, List.copyOf(indices));
        }

        List<Clause> clauses = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> entry : packageVarIndices.entrySet()) {
            String packageId = entry.getKey();
            List<Integer> indices = entry.getValue();

            List<Literal> choiceClause = new ArrayList<>(indices.size());
            for (Integer index : indices) {
                choiceClause.add(new Literal(index, true));
            }
            clauses.add(new Clause(
                List.copyOf(choiceClause),
                ClauseKind.PACKAGE_CHOICE,
                packageId,
                "select exactly one version of " + packageId
            ));

            for (int i = 0; i < indices.size(); i++) {
                for (int j = i + 1; j < indices.size(); j++) {
                    VersionVar left = vars.get(indices.get(i));
                    VersionVar right = vars.get(indices.get(j));
                    clauses.add(new Clause(
                        List.of(new Literal(left.index(), false), new Literal(right.index(), false)),
                        ClauseKind.VERSION_EXCLUSIVITY,
                        packageId,
                        "versions " + left.version() + " and " + right.version() + " cannot both be selected"
                    ));
                }
            }
        }

        for (VersionVar var : vars) {
            for (Dependency dependency : var.candidate().dependencies()) {
                List<Integer> depIndices = packageVarIndices.getOrDefault(dependency.id(), List.of());
                List<Literal> clause = new ArrayList<>(1 + depIndices.size());
                clause.add(new Literal(var.index(), false));
                for (Integer depIndex : depIndices) {
                    VersionVar depVar = vars.get(depIndex);
                    if (dependency.constraint().matches(depVar.version())) {
                        clause.add(new Literal(depIndex, true));
                    }
                }
                clauses.add(new Clause(
                    List.copyOf(clause),
                    ClauseKind.DEPENDENCY,
                    dependency.id(),
                    var.displayName() + " requires " + dependency.id() + " " + dependency.rawConstraint()
                ));
            }
        }

        return new Problem(
            Collections.unmodifiableMap(candidatesById),
            List.copyOf(vars),
            Collections.unmodifiableMap(packageVarIndices),
            List.copyOf(clauses)
        );
    }

    private static ResolutionException buildFailure(Problem problem, Solver solver, Clause conflict) {
        String packageId = determineConflictPackage(problem, solver, conflict);
        List<RequirementTrace> traces = activeRequirementTraces(problem, solver, packageId);
        List<String> requirements = renderRequirements(packageId, traces);
        if (requirements.isEmpty() && conflict != null && conflict.description() != null && !conflict.description().isBlank()) {
            requirements = List.of(conflict.description());
        }

        List<String> availableVersions = availableVersions(packageId, problem.candidatesById());
        String message = explainUnsatisfied(packageId, requirements, availableVersions, conflict);
        return new ResolutionException(
            "UNSATISFIABLE_CONSTRAINTS",
            packageId,
            requirements,
            availableVersions,
            message
        );
    }

    private static String determineConflictPackage(Problem problem, Solver solver, Clause conflict) {
        String unsatisfiedByConstraints = findUnsatisfiedPackageFromActiveConstraints(problem, solver);
        if (unsatisfiedByConstraints != null) {
            return unsatisfiedByConstraints;
        }

        for (String packageId : problem.packageVarIndices().keySet()) {
            List<Integer> vars = problem.packageVarIndices().getOrDefault(packageId, List.of());
            if (!vars.isEmpty() && vars.stream().allMatch(solver::isFalse)) {
                return packageId;
            }
        }

        if (conflict != null && conflict.packageId() != null && !conflict.packageId().isBlank()) {
            return conflict.packageId();
        }

        if (conflict != null && !conflict.literals().isEmpty()) {
            return problem.var(conflict.literals().get(0).varIndex()).packageId();
        }

        return "<graph>";
    }

    private static String findUnsatisfiedPackageFromActiveConstraints(Problem problem, Solver solver) {
        String chosen = null;
        int bestTraceCount = -1;

        for (String packageId : problem.packageVarIndices().keySet()) {
            List<RequirementTrace> traces = activeRequirementTraces(problem, solver, packageId);
            if (traces.isEmpty()) {
                continue;
            }

            boolean satisfiable = problem.candidatesById().getOrDefault(packageId, List.of()).stream()
                .anyMatch(candidate -> traces.stream()
                    .allMatch(trace -> trace.constraint().matches(candidate.version())));

            if (!satisfiable) {
                if (traces.size() > bestTraceCount
                    || (traces.size() == bestTraceCount && (chosen == null || packageId.compareTo(chosen) < 0))) {
                    chosen = packageId;
                    bestTraceCount = traces.size();
                }
            }
        }
        return chosen;
    }

    private static List<RequirementTrace> activeRequirementTraces(Problem problem, Solver solver, String packageId) {
        if (packageId == null || packageId.isBlank() || "<graph>".equals(packageId)) {
            return List.of();
        }

        List<RequirementTrace> traces = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, Candidate> entry : solver.selectedByPackage(problem).entrySet()) {
            Candidate candidate = entry.getValue();
            for (Dependency dependency : candidate.dependencies()) {
                if (!packageId.equals(dependency.id())) {
                    continue;
                }
                RequirementTrace trace = new RequirementTrace(
                    candidate.displayName(),
                    dependency.constraint(),
                    dependency.rawConstraint()
                );
                String key = trace.source() + "|" + packageId + "|" + trace.rawConstraint().toLowerCase(Locale.ROOT);
                if (seen.add(key)) {
                    traces.add(trace);
                }
            }
        }
        return List.copyOf(traces);
    }

    private static List<String> renderRequirements(String packageId, List<RequirementTrace> traces) {
        if (packageId == null || traces == null || traces.isEmpty()) {
            return List.of();
        }
        return traces.stream()
            .map(trace -> trace.source() + " requires " + packageId + " " + trace.rawConstraint())
            .toList();
    }

    private static String explainUnsatisfied(String packageId,
                                             List<String> requirements,
                                             List<String> availableVersions,
                                             Clause conflict) {
        String renderedRequirements = requirements == null || requirements.isEmpty()
            ? "<none>"
            : String.join("; ", requirements);
        String renderedVersions = availableVersions == null || availableVersions.isEmpty()
            ? "<none>"
            : availableVersions.toString();

        String message = "Unable to resolve '" + packageId + "'; constraints: "
            + renderedRequirements
            + "; available versions: "
            + renderedVersions;
        if (conflict != null && conflict.description() != null && !conflict.description().isBlank()) {
            message += "; conflict path: " + conflict.description();
        }
        return message;
    }

    private static List<String> availableVersions(String packageId,
                                                  Map<String, List<Candidate>> candidatesById) {
        return candidatesById.getOrDefault(packageId, List.of()).stream()
            .map(Candidate::version)
            .toList();
    }

    private List<String> topologicalOrder(Collection<String> packages,
                                          Map<String, Set<String>> dependencyEdges) {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();

        for (String pkg : packages) {
            inDegree.put(pkg, 0);
            dependents.put(pkg, new ArrayList<>());
        }

        for (Map.Entry<String, Set<String>> entry : dependencyEdges.entrySet()) {
            String pkg = entry.getKey();
            for (String dep : entry.getValue()) {
                if (!inDegree.containsKey(dep)) {
                    continue;
                }
                inDegree.compute(pkg, (k, v) -> v + 1);
                dependents.get(dep).add(pkg);
            }
        }

        ArrayList<String> queue = new ArrayList<>();
        inDegree.entrySet().stream()
            .filter(entry -> entry.getValue() == 0)
            .map(Map.Entry::getKey)
            .sorted()
            .forEach(queue::add);

        List<String> ordered = new ArrayList<>(packages.size());
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            ordered.add(current);
            for (String dependent : dependents.getOrDefault(current, List.of())) {
                int remaining = inDegree.compute(dependent, (key, value) -> value - 1);
                if (remaining == 0) {
                    queue.add(dependent);
                }
            }
        }

        if (ordered.size() != packages.size()) {
            List<String> cycle = inDegree.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
            throw new ResolutionException(
                "CYCLIC_DEPENDENCY_GRAPH",
                "<graph>",
                cycle.stream().map(node -> "cycle includes " + node).toList(),
                List.of(),
                "Cyclic dependency graph detected: " + cycle
            );
        }

        return ordered;
    }

    private record Dependency(String id, SemVerConstraint constraint, String rawConstraint) {}

    private record Candidate(String id,
                             String version,
                             List<Dependency> dependencies,
                             Set<String> dependencyIds) {
        String displayName() {
            return id + "@" + version;
        }
    }

    private record VersionVar(int index, String packageId, Candidate candidate) {
        String version() {
            return candidate.version();
        }

        String displayName() {
            return candidate.displayName();
        }
    }

    private record RequirementTrace(String source, SemVerConstraint constraint, String rawConstraint) {}

    private record Literal(int varIndex, boolean positive) {}

    private enum ClauseKind {
        PACKAGE_CHOICE,
        VERSION_EXCLUSIVITY,
        DEPENDENCY,
        LEARNED
    }

    private record Clause(List<Literal> literals, ClauseKind kind, String packageId, String description) {}

    private record Problem(Map<String, List<Candidate>> candidatesById,
                           List<VersionVar> vars,
                           Map<String, List<Integer>> packageVarIndices,
                           List<Clause> clauses) {
        VersionVar var(int index) {
            return vars.get(index);
        }
    }

    private record SolverOutcome(Map<String, Candidate> selected, ResolutionException failure) {
        static SolverOutcome success(Map<String, Candidate> selected) {
            return new SolverOutcome(selected, null);
        }

        static SolverOutcome failure(ResolutionException failure) {
            return new SolverOutcome(null, failure);
        }

        boolean success() {
            return selected != null;
        }
    }

    private record AnalysisResult(Clause learned, int backjumpLevel) {}

    private static final class Solver {
        private static final byte UNASSIGNED = 0;
        private static final byte TRUE = 1;
        private static final byte FALSE = -1;

        private final Problem problem;
        private final List<Clause> clauses;
        private final byte[] values;
        private final int[] levels;
        private final Clause[] reasons;
        private final int[] trailPositions;
        private final List<Integer> trail = new ArrayList<>();
        private final List<Integer> decisionStarts = new ArrayList<>();

        private Solver(Problem problem) {
            this.problem = problem;
            this.clauses = new ArrayList<>(problem.clauses());
            this.values = new byte[problem.vars().size()];
            this.levels = new int[problem.vars().size()];
            this.reasons = new Clause[problem.vars().size()];
            this.trailPositions = new int[problem.vars().size()];
            java.util.Arrays.fill(trailPositions, -1);
        }

        SolverOutcome solve() {
            while (true) {
                Clause conflict = propagate();
                while (conflict != null) {
                    if (currentLevel() == 0) {
                        return SolverOutcome.failure(buildFailure(problem, this, conflict));
                    }
                    AnalysisResult analysis = analyze(conflict);
                    if (analysis.learned().literals().isEmpty()) {
                        return SolverOutcome.failure(buildFailure(problem, this, analysis.learned()));
                    }
                    backjump(analysis.backjumpLevel());
                    clauses.add(analysis.learned());
                    conflict = propagate();
                }

                String nextPackage = chooseNextPackage();
                if (nextPackage == null) {
                    return SolverOutcome.success(selectedByPackage(problem));
                }

                decide(nextPackage);
            }
        }

        Map<String, Candidate> selectedByPackage(Problem problem) {
            Map<String, Candidate> selected = new LinkedHashMap<>();
            for (Map.Entry<String, List<Integer>> entry : problem.packageVarIndices().entrySet()) {
                for (Integer varIndex : entry.getValue()) {
                    if (isTrue(varIndex)) {
                        selected.put(entry.getKey(), problem.var(varIndex).candidate());
                        break;
                    }
                }
            }
            return selected;
        }

        boolean isFalse(int varIndex) {
            return values[varIndex] == FALSE;
        }

        private boolean isTrue(int varIndex) {
            return values[varIndex] == TRUE;
        }

        private int currentLevel() {
            return decisionStarts.size();
        }

        private Clause propagate() {
            boolean progress;
            do {
                progress = false;
                for (Clause clause : clauses) {
                    ClauseStatus status = evaluate(clause);
                    if (status.satisfied()) {
                        continue;
                    }
                    if (status.unassignedCount() == 0) {
                        return clause;
                    }
                    if (status.unassignedCount() == 1) {
                        if (!enqueue(status.lastUnassigned(), clause)) {
                            return clause;
                        }
                        progress = true;
                    }
                }
            } while (progress);
            return null;
        }

        private ClauseStatus evaluate(Clause clause) {
            boolean satisfied = false;
            int unassignedCount = 0;
            Literal lastUnassigned = null;
            for (Literal literal : clause.literals()) {
                byte value = values[literal.varIndex()];
                if (value == UNASSIGNED) {
                    unassignedCount++;
                    lastUnassigned = literal;
                    continue;
                }
                if ((value == TRUE) == literal.positive()) {
                    satisfied = true;
                    break;
                }
            }
            return new ClauseStatus(satisfied, unassignedCount, lastUnassigned);
        }

        private boolean enqueue(Literal literal, Clause reason) {
            int varIndex = literal.varIndex();
            byte desired = literal.positive() ? TRUE : FALSE;
            byte existing = values[varIndex];
            if (existing == desired) {
                return true;
            }
            if (existing != UNASSIGNED) {
                return false;
            }

            values[varIndex] = desired;
            levels[varIndex] = currentLevel();
            reasons[varIndex] = reason;
            trailPositions[varIndex] = trail.size();
            trail.add(varIndex);
            return true;
        }

        private String chooseNextPackage() {
            String chosen = null;
            int bestRemaining = Integer.MAX_VALUE;

            for (Map.Entry<String, List<Integer>> entry : problem.packageVarIndices().entrySet()) {
                String packageId = entry.getKey();
                List<Integer> vars = entry.getValue();
                if (vars.stream().anyMatch(this::isTrue)) {
                    continue;
                }

                int remaining = 0;
                for (Integer var : vars) {
                    if (!isFalse(var)) {
                        remaining++;
                    }
                }
                if (remaining <= 1) {
                    continue;
                }
                if (remaining < bestRemaining || (remaining == bestRemaining && (chosen == null || packageId.compareTo(chosen) < 0))) {
                    chosen = packageId;
                    bestRemaining = remaining;
                }
            }
            return chosen;
        }

        private void decide(String packageId) {
            List<Integer> vars = problem.packageVarIndices().getOrDefault(packageId, List.of());
            for (Integer var : vars) {
                if (!isFalse(var)) {
                    decisionStarts.add(trail.size());
                    enqueue(new Literal(var, true), null);
                    return;
                }
            }
            throw new IllegalStateException("No decision candidate available for " + packageId);
        }

        private AnalysisResult analyze(Clause conflict) {
            List<Literal> learned = new ArrayList<>(conflict.literals());

            while (countCurrentLevelLiterals(learned) > 1) {
                int pivot = latestAssignedLiteralAtCurrentLevel(learned);
                if (pivot < 0) {
                    break;
                }
                Clause reason = reasons[pivot];
                if (reason == null) {
                    break;
                }
                learned = resolve(learned, reason, pivot);
            }

            List<Literal> normalized = normalizeClause(learned);
            int backjumpLevel = 0;
            for (Literal literal : normalized) {
                int level = levels[literal.varIndex()];
                if (level != currentLevel()) {
                    backjumpLevel = Math.max(backjumpLevel, level);
                }
            }

            return new AnalysisResult(
                new Clause(
                    List.copyOf(normalized),
                    ClauseKind.LEARNED,
                    packageIdForLearnedClause(normalized),
                    describeLearnedClause(normalized)
                ),
                backjumpLevel
            );
        }

        private int countCurrentLevelLiterals(List<Literal> clause) {
            int count = 0;
            for (Literal literal : clause) {
                if (levels[literal.varIndex()] == currentLevel()) {
                    count++;
                }
            }
            return count;
        }

        private int latestAssignedLiteralAtCurrentLevel(List<Literal> clause) {
            Set<Integer> clauseVars = clause.stream()
                .filter(literal -> levels[literal.varIndex()] == currentLevel())
                .map(Literal::varIndex)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            for (int i = trail.size() - 1; i >= 0; i--) {
                int varIndex = trail.get(i);
                if (clauseVars.contains(varIndex) && levels[varIndex] == currentLevel()) {
                    return varIndex;
                }
            }
            return -1;
        }

        private List<Literal> resolve(List<Literal> learned, Clause reason, int pivotVar) {
            Map<Integer, Literal> merged = new LinkedHashMap<>();
            for (Literal literal : learned) {
                if (literal.varIndex() != pivotVar) {
                    merged.putIfAbsent(literal.varIndex(), literal);
                }
            }

            Literal satisfiedPivot = values[pivotVar] == TRUE
                ? new Literal(pivotVar, true)
                : new Literal(pivotVar, false);

            for (Literal literal : reason.literals()) {
                if (literal.varIndex() == satisfiedPivot.varIndex() && literal.positive() == satisfiedPivot.positive()) {
                    continue;
                }
                merged.putIfAbsent(literal.varIndex(), literal);
            }

            return new ArrayList<>(merged.values());
        }

        private List<Literal> normalizeClause(List<Literal> clause) {
            Map<Integer, Literal> deduped = new LinkedHashMap<>();
            for (Literal literal : clause) {
                Literal existing = deduped.get(literal.varIndex());
                if (existing == null) {
                    deduped.put(literal.varIndex(), literal);
                }
            }
            return new ArrayList<>(deduped.values());
        }

        private String packageIdForLearnedClause(List<Literal> clause) {
            if (clause.isEmpty()) {
                return "<graph>";
            }
            return problem.var(clause.get(0).varIndex()).packageId();
        }

        private String describeLearnedClause(List<Literal> clause) {
            if (clause.isEmpty()) {
                return "empty learned incompatibility";
            }
            return clause.stream()
                .map(literal -> {
                    VersionVar var = problem.var(literal.varIndex());
                    return (literal.positive() ? "" : "not ") + var.packageId() + "@" + var.version();
                })
                .collect(Collectors.joining(" | "));
        }

        private void backjump(int targetLevel) {
            while (currentLevel() > targetLevel) {
                int start = decisionStarts.remove(decisionStarts.size() - 1);
                for (int i = trail.size() - 1; i >= start; i--) {
                    int varIndex = trail.remove(i);
                    values[varIndex] = UNASSIGNED;
                    levels[varIndex] = 0;
                    reasons[varIndex] = null;
                    trailPositions[varIndex] = -1;
                }
            }
        }
    }

    private record ClauseStatus(boolean satisfied, int unassignedCount, Literal lastUnassigned) {}
}
