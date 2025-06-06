/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.shared.dependency.analyzer;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyCollectorBuilder;
import org.apache.maven.shared.dependency.graph.DependencyCollectorBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;

/**
 * <p>DefaultProjectDependencyAnalyzer class.</p>
 *
 * @author <a href="mailto:markhobson@gmail.com">Mark Hobson</a>
 */
@Named
@Singleton
public class DefaultProjectDependencyAnalyzer implements ProjectDependencyAnalyzer {
    /**
     * ClassAnalyzer
     */
    @Inject
    private ClassAnalyzer classAnalyzer;

    /**
     * DependencyAnalyzer
     */
    @Inject
    private DependencyAnalyzer dependencyAnalyzer;

    /**
     * DependencyCollectorBuilder
     */
    @Inject
    private DependencyCollectorBuilder dependencyCollectorBuilder;

    /** {@inheritDoc} */
    @Override
    public ProjectDependencyAnalysis analyze(
            MavenProject project, ProjectBuildingRequest request, Collection<String> excludedClasses)
            throws ProjectDependencyAnalyzerException {
        try {
            ClassesPatterns excludedClassesPatterns = new ClassesPatterns(excludedClasses);
            Map<Artifact, Set<String>> artifactClassMap = buildArtifactClassMap(project, excludedClassesPatterns);

            Set<DependencyUsage> mainDependencyClasses = buildMainDependencyClasses(project, excludedClassesPatterns);
            Set<DependencyUsage> testDependencyClasses = buildTestDependencyClasses(project, excludedClassesPatterns);

            Set<DependencyUsage> dependencyClasses = new HashSet<>();
            dependencyClasses.addAll(mainDependencyClasses);
            dependencyClasses.addAll(testDependencyClasses);

            Set<DependencyUsage> testOnlyDependencyClasses =
                    buildTestOnlyDependencyClasses(mainDependencyClasses, testDependencyClasses);

            Map<Artifact, Set<DependencyUsage>> usedArtifacts = buildUsedArtifacts(artifactClassMap, dependencyClasses);
            Set<Artifact> mainUsedArtifacts =
                    buildUsedArtifacts(artifactClassMap, mainDependencyClasses).keySet();

            Set<Artifact> testArtifacts = buildUsedArtifacts(artifactClassMap, testOnlyDependencyClasses)
                    .keySet();
            Set<Artifact> testOnlyArtifacts = removeAll(testArtifacts, mainUsedArtifacts);

            // transitive non-test scoped artifacts (can't be declared with test scope)
            Set<Artifact> testOnlyMainArtifacts;
            Set<Artifact> testOnlyTestArtifacts;
            if (request != null) {
                Set<Artifact> transitivedMainArtifacts =
                        buildTransitiveMainArtifacts(project, request, dependencyCollectorBuilder);
                testOnlyMainArtifacts = retainAll(testOnlyArtifacts, transitivedMainArtifacts);
                testOnlyTestArtifacts = removeAll(testOnlyArtifacts, testOnlyMainArtifacts);
            } else {
                testOnlyMainArtifacts = new LinkedHashSet<>();
                testOnlyTestArtifacts = new LinkedHashSet<>(testOnlyArtifacts);
            }

            Set<Artifact> declaredArtifacts = buildDeclaredArtifacts(project);
            Set<Artifact> declaredTestArtifacts = filterTestArtifacts(declaredArtifacts);
            Set<Artifact> declaredMainArtifacts = removeAll(declaredArtifacts, declaredTestArtifacts);

            // used-declared: (declared & used)
            Set<Artifact> usedDeclaredArtifacts = new LinkedHashSet<>(declaredArtifacts);
            usedDeclaredArtifacts.retainAll(usedArtifacts.keySet());

            Map<Artifact, Set<DependencyUsage>> usedDeclaredArtifactsWithClasses = new LinkedHashMap<>();
            for (Artifact a : usedDeclaredArtifacts) {
                usedDeclaredArtifactsWithClasses.put(a, usedArtifacts.get(a));
            }

            // used-undeclared: (used - declared - used-test-only-main)
            Map<Artifact, Set<DependencyUsage>> usedUndeclaredArtifactsWithClasses = new LinkedHashMap<>(usedArtifacts);
            Set<Artifact> usedUndeclaredArtifacts =
                    removeAll(usedUndeclaredArtifactsWithClasses.keySet(), declaredArtifacts);
            usedUndeclaredArtifacts = removeAll(usedUndeclaredArtifacts, testOnlyMainArtifacts);

            usedUndeclaredArtifactsWithClasses.keySet().retainAll(usedUndeclaredArtifacts);

            // unused-declared: (declared - used)
            Set<Artifact> unusedDeclaredArtifacts = new LinkedHashSet<>(declaredArtifacts);
            unusedDeclaredArtifacts = removeAll(unusedDeclaredArtifacts, usedArtifacts.keySet());

            // test-with-non-test-scope: (declared-main & (used-test-only-test)
            Set<Artifact> testArtifactsWithNonTestScope =
                    retainAll(withoutRuntime(declaredMainArtifacts), testOnlyTestArtifacts);

            return new ProjectDependencyAnalysis(
                    usedDeclaredArtifactsWithClasses, usedUndeclaredArtifactsWithClasses,
                    unusedDeclaredArtifacts, testArtifactsWithNonTestScope);
        } catch (IOException exception) {
            throw new ProjectDependencyAnalyzerException("Cannot analyze dependencies", exception);
        }
    }

    /**
     * Returns a set of artifacts with non-runtime scope.
     *
     * @param artifacts artifacts to be filtered
     * @return set of artifacts with non-runtime scope
     */
    private static Set<Artifact> withoutRuntime(Set<Artifact> artifacts) {
        return artifacts.stream()
                .filter(a -> !Artifact.SCOPE_RUNTIME.equalsIgnoreCase(a.getScope()))
                .collect(Collectors.toSet());
    }

    /**
     * Returns an intersection of passed sets of artifacts.
     *
     * @param artifacts1 first set of artifacts
     * @param artifacts2 second set of artifacts
     * @return intersection of passed sets of artifacts
     */
    private static Set<Artifact> retainAll(Set<Artifact> artifacts1, Set<Artifact> artifacts2) {
        return artifacts1.stream()
                .filter(artifact1 -> artifacts2.stream()
                        .anyMatch(artifact2 ->
                                artifact1.getDependencyConflictId().equals(artifact2.getDependencyConflictId())))
                .collect(Collectors.toSet());
    }

    /**
     * This method defines a new way to remove the artifacts by using the conflict id. We don't care about the version
     * here because there can be only 1 for a given artifact anyway.
     *
     * @param start  initial set
     * @param remove set to exclude
     * @return set with remove excluded
     */
    private static Set<Artifact> removeAll(Set<Artifact> start, Set<Artifact> remove) {
        Set<Artifact> results = new LinkedHashSet<>(start.size());

        for (Artifact artifact : start) {
            boolean found = false;

            for (Artifact artifact2 : remove) {
                if (artifact.getDependencyConflictId().equals(artifact2.getDependencyConflictId())) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                results.add(artifact);
            }
        }

        return results;
    }

    protected Map<Artifact, Set<String>> buildArtifactClassMap(MavenProject project, ClassesPatterns excludedClasses)
            throws IOException {
        Map<Artifact, Set<String>> artifactClassMap = new LinkedHashMap<>();

        Set<Artifact> dependencyArtifacts = project.getArtifacts();

        for (Artifact artifact : dependencyArtifacts) {
            File file = artifact.getFile();

            if (file != null && file.getName().endsWith(".jar")) {
                // optimized solution for the jar case

                try (JarFile jarFile = new JarFile(file)) {
                    Enumeration<JarEntry> jarEntries = jarFile.entries();

                    Set<String> classes = new HashSet<>();

                    while (jarEntries.hasMoreElements()) {
                        String entry = jarEntries.nextElement().getName();
                        if (entry.endsWith(".class")) {
                            String className = entry.replace('/', '.');
                            className = className.substring(0, className.length() - ".class".length());
                            if (!excludedClasses.isMatch(className)) {
                                classes.add(className);
                            }
                        }
                    }

                    artifactClassMap.put(artifact, classes);
                }
            } else if (file != null && file.isDirectory()) {
                URL url = file.toURI().toURL();
                Set<String> classes = classAnalyzer.analyze(url, excludedClasses);

                artifactClassMap.put(artifact, classes);
            }
        }

        return artifactClassMap;
    }

    private static Set<DependencyUsage> buildTestOnlyDependencyClasses(
            Set<DependencyUsage> mainDependencyClasses, Set<DependencyUsage> testDependencyClasses) {
        Set<DependencyUsage> testOnlyDependencyClasses = new HashSet<>(testDependencyClasses);
        Set<String> mainDepClassNames = mainDependencyClasses.stream()
                .map(DependencyUsage::getDependencyClass)
                .collect(Collectors.toSet());
        testOnlyDependencyClasses.removeIf(u -> mainDepClassNames.contains(u.getDependencyClass()));
        return testOnlyDependencyClasses;
    }

    private Set<DependencyUsage> buildMainDependencyClasses(MavenProject project, ClassesPatterns excludedClasses)
            throws IOException {
        String outputDirectory = project.getBuild().getOutputDirectory();
        return buildDependencyClasses(outputDirectory, excludedClasses);
    }

    private Set<DependencyUsage> buildTestDependencyClasses(MavenProject project, ClassesPatterns excludedClasses)
            throws IOException {
        String testOutputDirectory = project.getBuild().getTestOutputDirectory();
        return buildDependencyClasses(testOutputDirectory, excludedClasses);
    }

    private Set<DependencyUsage> buildDependencyClasses(String path, ClassesPatterns excludedClasses)
            throws IOException {
        URL url = new File(path).toURI().toURL();

        return dependencyAnalyzer.analyzeUsages(url, excludedClasses);
    }

    private static Set<Artifact> buildDeclaredArtifacts(MavenProject project) {
        Set<Artifact> declaredArtifacts = project.getDependencyArtifacts();

        if (declaredArtifacts == null) {
            declaredArtifacts = Collections.emptySet();
        }

        return declaredArtifacts;
    }

    /**
     * Returns a set of transitive artifacts with non-test scope.
     * The set contains a transitive artfact even if it's additionally declared as direct artifact with another scope.
     *
     * @param project current Maven project
     * @param request project build request to be used by collecting dependency graph
     * @param dependencyCollectorBuilder dependency collector builder
     * @return transitive artifacts with non-test scope
     */
    private static Set<Artifact> buildTransitiveMainArtifacts(
            MavenProject project,
            ProjectBuildingRequest request,
            DependencyCollectorBuilder dependencyCollectorBuilder) {
        Set<Artifact> transitiveMainArtifacts = new LinkedHashSet<>();
        try {
            ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(request);
            buildingRequest.setProject(project);
            DependencyNode rootNode = dependencyCollectorBuilder.collectDependencyGraph(buildingRequest, null);
            Set<Artifact> visited = new LinkedHashSet<>();
            rootNode.accept(new DependencyNodeVisitor() {
                @Override
                public boolean visit(DependencyNode node) {
                    if (node.getParent() == null) {
                        return true; // skip root (project itself)
                    }
                    Artifact artifact = node.getArtifact();
                    if (Artifact.SCOPE_TEST.equals(artifact.getScope())) {
                        return false; // skip whole test scoped sub-tree
                    }
                    if (node.getParent().getParent() == null) {
                        return true; // skip root's direct child (declared dependency)
                    }
                    if (!visited.contains(artifact)) {
                        visited.add(artifact);
                    } else {
                        return false; // skip already visited sub-tree
                    }
                    transitiveMainArtifacts.add(artifact);
                    return true;
                }

                @Override
                public boolean endVisit(DependencyNode node) {
                    return true;
                }
            });
        } catch (DependencyCollectorBuilderException exc) {
            // TODO: handle exception if some of dependencies couldn't be collected
            exc.printStackTrace();
        }
        return transitiveMainArtifacts;
    }

    /**
     * Returns only artifacts with test scope.
     *
     * @param artifacts artifacts to be filtered
     * @return artifacts with test scope
     */
    private static Set<Artifact> filterTestArtifacts(Set<Artifact> artifacts) {
        return artifacts.stream()
                .filter(artifact -> Artifact.SCOPE_TEST.equals(artifact.getScope()))
                .collect(Collectors.toSet());
    }

    private static Map<Artifact, Set<DependencyUsage>> buildUsedArtifacts(
            Map<Artifact, Set<String>> artifactClassMap, Set<DependencyUsage> dependencyClasses) {
        Map<Artifact, Set<DependencyUsage>> usedArtifacts = new HashMap<>();

        for (DependencyUsage classUsage : dependencyClasses) {
            Artifact artifact = findArtifactForClassName(artifactClassMap, classUsage.getDependencyClass());

            if (artifact != null && !includedInJDK(artifact)) {
                Set<DependencyUsage> classesFromArtifact = usedArtifacts.get(artifact);
                if (classesFromArtifact == null) {
                    classesFromArtifact = new HashSet<>();
                    usedArtifacts.put(artifact, classesFromArtifact);
                }
                classesFromArtifact.add(classUsage);
            }
        }

        return usedArtifacts;
    }

    // MSHARED-47 an uncommon case where a commonly used
    // third party dependency was added to the JDK
    private static boolean includedInJDK(Artifact artifact) {
        if ("xml-apis".equals(artifact.getGroupId())) {
            if ("xml-apis".equals(artifact.getArtifactId())) {
                return true;
            }
        } else if ("xerces".equals(artifact.getGroupId())) {
            if ("xmlParserAPIs".equals(artifact.getArtifactId())) {
                return true;
            }
        }
        return false;
    }

    private static Artifact findArtifactForClassName(Map<Artifact, Set<String>> artifactClassMap, String className) {
        for (Map.Entry<Artifact, Set<String>> entry : artifactClassMap.entrySet()) {
            if (entry.getValue().contains(className)) {
                return entry.getKey();
            }
        }

        return null;
    }
}
