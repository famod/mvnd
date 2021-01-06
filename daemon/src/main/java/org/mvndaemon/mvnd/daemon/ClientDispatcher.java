/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mvndaemon.mvnd.daemon;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.mvndaemon.mvnd.builder.DependencyGraph;
import org.mvndaemon.mvnd.common.Message;
import org.mvndaemon.mvnd.common.Message.BuildException;
import org.mvndaemon.mvnd.common.Message.BuildStarted;
import org.mvndaemon.mvnd.logging.smart.BuildEventListener;

/**
 * Sends events back to the client.
 */
public class ClientDispatcher extends BuildEventListener {
    private final Collection<Message> queue;

    public ClientDispatcher(Collection<Message> queue) {
        this.queue = queue;
    }

    public void sessionStarted(ExecutionEvent event) {
        final MavenSession session = event.getSession();
        final int degreeOfConcurrency = session.getRequest().getDegreeOfConcurrency();
        final DependencyGraph<MavenProject> dependencyGraph = DependencyGraph.fromMaven(session);
        session.getRequest().getData().put(DependencyGraph.class.getName(), dependencyGraph);

        final int maxThreads = degreeOfConcurrency == 1 ? 1 : dependencyGraph.computeMaxWidth(degreeOfConcurrency, 1000);
        final List<MavenProject> projects = session.getProjects();
        final int _90thArtifactIdLengthPercentile = artifactIdLength90thPercentile(projects);
        queue.add(new BuildStarted(getCurrentProject(session).getArtifactId(), projects.size(), maxThreads,
                _90thArtifactIdLengthPercentile));
    }

    static int artifactIdLength90thPercentile(List<MavenProject> projects) {
        if (projects.size() == 1) {
            return projects.get(0).getArtifactId().length();
        }
        Map<Integer, Integer> frequencyDistribution = new TreeMap<>();
        for (MavenProject p : projects) {
            frequencyDistribution.compute(p.getArtifactId().length(),
                    (k, v) -> (v == null) ? Integer.valueOf(1) : Integer.valueOf(v.intValue() + 1));
        }
        int _90PercCount = Math.round(0.9f * projects.size());
        int cnt = 0;
        for (Entry<Integer, Integer> en : frequencyDistribution.entrySet()) {
            cnt += en.getValue().intValue();
            if (cnt >= _90PercCount) {
                return en.getKey().intValue();
            }
        }
        throw new IllegalStateException("Could not compute the 90th percentile of the projects length from " + projects);
    }

    public void projectStarted(ExecutionEvent event) {
        queue.add(Message.projectStarted(event.getProject().getArtifactId()));
    }

    public void projectLogMessage(String projectId, String event) {
        String msg = event.endsWith("\n") ? event.substring(0, event.length() - 1) : event;
        queue.add(projectId == null ? Message.log(msg) : Message.log(projectId, msg));
    }

    public void projectFinished(ExecutionEvent event) {
        queue.add(Message.projectStopped(event.getProject().getArtifactId()));
    }

    public void mojoStarted(ExecutionEvent event) {
        final MojoExecution execution = event.getMojoExecution();
        queue.add(Message.mojoStarted(
                event.getProject().getArtifactId(),
                execution.getGroupId(),
                execution.getArtifactId(),
                execution.getVersion(),
                execution.getGoal(),
                execution.getExecutionId()));
    }

    public void finish(int exitCode) throws Exception {
        queue.add(new Message.BuildFinished(exitCode));
        queue.add(Message.STOP_SINGLETON);
    }

    public void fail(Throwable t) throws Exception {
        queue.add(new BuildException(t));
        queue.add(Message.STOP_SINGLETON);
    }

    public void log(String msg) {
        queue.add(Message.log(msg));
    }

    private MavenProject getCurrentProject(MavenSession mavenSession) {
        // Workaround for https://issues.apache.org/jira/browse/MNG-6979
        // MavenSession.getCurrentProject() does not return the correct value in some cases
        String executionRootDirectory = mavenSession.getExecutionRootDirectory();
        if (executionRootDirectory == null) {
            return mavenSession.getCurrentProject();
        }
        return mavenSession.getProjects().stream()
                .filter(p -> (p.getFile() != null && executionRootDirectory.equals(p.getFile().getParent())))
                .findFirst()
                .orElse(mavenSession.getCurrentProject());
    }

}