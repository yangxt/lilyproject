/*
 * Copyright 2010 Outerthought bvba
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lilyproject.tools.mavenplugin.lilyruntimedepresolver;

import java.io.File;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

/**
 *
 * @goal assemble-project-repository
 * @requiresDependencyResolution runtime
 * @description Creates a Maven-style repository for Lily Runtime with all the dependencies of a Lily Runtime project,
 *              based upon reading the wiring.xml.
 */
public class LilyRuntimeProjectRepository extends AbstractMojo {
    /**
     * Location of the conf directory.
     *
     * @parameter expression="${basedir}/conf"
     * @required
     */
    protected String confDirectory;

    /**
     * Location of the conf directory.
     *
     * @parameter expression="${basedir}/target/lilyruntime-repository"
     * @required
     */
    protected String targetDirectory;

    /**
     * Maven Artifact Factory component.
     *
     * @component
     */
    protected ArtifactFactory artifactFactory;

    /**
     * Remote repositories used for the project.
     *
     * @parameter expression="${project.remoteArtifactRepositories}"
     * @required
     * @readonly
     */
    protected List remoteRepositories;

    /**
     * Local Repository.
     *
     * @parameter expression="${localRepository}"
     * @required
     * @readonly
     */
    protected ArtifactRepository localRepository;

    /**
     * Artifact Resolver component.
     *
     * @component
     */
    protected ArtifactResolver resolver;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        LilyRuntimeProjectClasspath cp = new LilyRuntimeProjectClasspath(getLog(), null,
                artifactFactory, resolver, localRepository);

        ModuleArtifacts moduleArtifacts = cp.getModuleArtifactsFromLilyRuntimeConfig(new File(confDirectory), remoteRepositories);
        Set<Artifact> artifacts = cp.getAllArtifacts(moduleArtifacts.artifacts, remoteRepositories);
        RepositoryWriter.write(artifacts, targetDirectory);
    }

}
