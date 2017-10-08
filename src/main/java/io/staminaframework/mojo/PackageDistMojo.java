/*
 * Copyright (c) 2017 Stamina Framework developers.
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

package io.staminaframework.mojo;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.utils.Os;
import org.apache.maven.shared.utils.StringUtils;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.FileSet;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.tar.TarArchiver;
import org.codehaus.plexus.archiver.tar.TarLongFileMode;
import org.codehaus.plexus.archiver.tar.TarUnArchiver;
import org.codehaus.plexus.archiver.util.DefaultFileSet;
import org.codehaus.plexus.archiver.zip.ZipArchiver;
import org.codehaus.plexus.archiver.zip.ZipUnArchiver;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Mojo used for packaging a Stamina distribution.
 *
 * @author Stamina Framework developers
 */
@Mojo(name = "package-dist", defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.RUNTIME)
public class PackageDistMojo extends AbstractMojo {
    private static final String DISTRIBUTION_TYPE_AUTO = "auto";
    private static final String DISTRIBUTION_TYPE_ZIP = "zip";
    private static final String DISTRIBUTION_TYPE_TARGZ = "tar.gz";

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;
    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true, required = true)
    private List<ArtifactRepository> remoteRepositories;
    @Parameter(defaultValue = "${project.build.directory}", required = true, readonly = true)
    private File outputDirectory;
    @Parameter(property = "plugin.artifacts", required = true, readonly = true)
    private List<Artifact> pluginArtifacts;
    @Parameter
    private String distributionVersion;
    @Parameter(defaultValue = "auto", required = true)
    private String distributionType;
    @Component
    private ArtifactResolver artifactResolver;
    @Component(role = Archiver.class, hint = "zip")
    private ZipArchiver zipArchiver;
    @Component(role = Archiver.class, hint = "tar")
    private TarArchiver tarArchiver;
    @Component(role = UnArchiver.class, hint = "zip")
    private ZipUnArchiver zipUnArchiver;
    @Component(role = UnArchiver.class, hint = "tar")
    private TarUnArchiver tarUnArchiver;
    @Parameter(defaultValue = "${project.dependencies}", required = true, readonly = true)
    private List<Dependency> projectDependencies;

    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!distributionType.equals(DISTRIBUTION_TYPE_AUTO)
                && !distributionType.equals(DISTRIBUTION_TYPE_ZIP)
                && !distributionType.equals(DISTRIBUTION_TYPE_TARGZ)) {
            getLog().error("Supported distribution types: "
                    + DISTRIBUTION_TYPE_AUTO + ", " + DISTRIBUTION_TYPE_ZIP
                    + ", " + DISTRIBUTION_TYPE_TARGZ);
            throw new MojoFailureException("Unsupported distribution type: " + distributionType);
        }
        if (DISTRIBUTION_TYPE_AUTO.equals(distributionType)) {
            if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                distributionType = DISTRIBUTION_TYPE_ZIP;
            } else {
                distributionType = DISTRIBUTION_TYPE_TARGZ;
            }
            getLog().info("Distribution type automatically set to " + distributionType);
        }

        final Archiver archiver;
        final UnArchiver unArchiver;
        if (DISTRIBUTION_TYPE_ZIP.equals(distributionType)) {
            archiver = zipArchiver;
            unArchiver = zipUnArchiver;
            archiver.setDestFile(new File(outputDirectory, project.getBuild().getFinalName() + ".zip"));
        } else if (DISTRIBUTION_TYPE_TARGZ.equals(distributionType)) {
            tarArchiver.setCompression(TarArchiver.TarCompressionMethod.gzip);
            tarArchiver.setLongfile(TarLongFileMode.gnu);
            tarUnArchiver.setCompression(TarUnArchiver.UntarCompressionMethod.GZIP);
            archiver = tarArchiver;
            archiver.setDestFile(new File(outputDirectory, project.getBuild().getFinalName() + ".tar.gz"));
            unArchiver = tarUnArchiver;
        } else {
            throw new MojoFailureException("Unexpected error");
        }

        // Resolve Stamina distribution artifact from plugin dependencies.
        Artifact distArt = null;
        if (!StringUtils.isEmpty(distributionVersion)) {
            distArt = new DefaultArtifact("io.staminaframework", "io.staminaframework.runtime",
                    distributionVersion, Artifact.SCOPE_COMPILE, distributionType, "bin", null);
        } else {
            for (final Artifact art : pluginArtifacts) {
                if ("io.staminaframework".equals(art.getGroupId()) && "io.staminaframework.runtime".equals(art.getArtifactId())) {
                    distArt = new DefaultArtifact(art.getGroupId(), art.getArtifactId(),
                            art.getVersion(), art.getScope(), distributionType, art.getClassifier(), null);
                    break;
                }
            }
        }
        if (distArt == null) {
            throw new MojoFailureException("Unable to resolve distribution template");
        }

        final File distFile = resolveArtifact(distArt);
        getLog().info("Using distribution template: " + distFile);

        final File distDir = new File(outputDirectory, "dist");
        getLog().info("Unpacking distribution template to: " + distDir);
        distDir.mkdirs();
        unArchiver.setDestDirectory(distDir);
        unArchiver.setSourceFile(distFile);
        try {
            unArchiver.extract();
        } catch (ArchiverException e) {
            throw new MojoFailureException("Failed to unpack distribution template to " + distDir, e);
        }

        // Fix distribution root dir: use current project build final name.
        final File rootDistDir = new File(distDir, distArt.getArtifactId() + "-" + distArt.getVersion());
        rootDistDir.renameTo(new File(distDir, project.getBuild().getFinalName()));

        // Add all files from distribution to final archive.
        final FileSet distFileSet = new DefaultFileSet(distDir);
        archiver.addFileSet(distFileSet);

        getLog().info("Reading project dependencies");
        for (final Dependency dep : projectDependencies) {
            final String scope = dep.getScope();
            if (Artifact.SCOPE_PROVIDED.equals(scope)
                    || Artifact.SCOPE_RUNTIME.equals(scope)
                    || Artifact.SCOPE_TEST.equals(scope)
                    || Artifact.SCOPE_IMPORT.equals(scope)) {
                continue;
            }
            if (dep.isOptional()) {
                continue;
            }

            final String commonPrefix = project.getBuild().getFinalName() + "/";
            final String prefix;
            switch (dep.getType()) {
                case "cfg":
                    prefix = "etc/";
                    break;
                case "esa":
                case "jar":
                    prefix = "addons/";
                    break;
                default:
                    prefix = null;
            }
            if (prefix == null) {
                getLog().warn("Dependency not included (unsupported type): "
                        + dep.getGroupId() + ":" + dep.getArtifactId());
            } else {
                getLog().info("Adding dependency to distribution: "
                        + dep.getGroupId() + ":" + dep.getArtifactId());
                final File depFile = resolveDependency(dep);
                archiver.addFile(depFile, commonPrefix + prefix + depFile.getName());
            }
        }

        getLog().info("Packaging distribution to file: " + archiver.getDestFile());
        try {
            archiver.createArchive();
        } catch (IOException e) {
            throw new MojoFailureException("Failed to package distribution to file: " + archiver.getDestFile(), e);
        }
    }

    private File resolveArtifact(Artifact art) throws MojoFailureException {
        final ProjectBuildingRequest req =
                new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        req.setRemoteRepositories(remoteRepositories);
        try {
            final DefaultArtifactCoordinate artCoo = new DefaultArtifactCoordinate();
            artCoo.setGroupId(art.getGroupId());
            artCoo.setArtifactId(art.getArtifactId());
            artCoo.setClassifier(art.getClassifier());
            artCoo.setExtension(art.getType());
            artCoo.setVersion(art.getVersion());

            return artifactResolver.resolveArtifact(req, artCoo).getArtifact().getFile();
        } catch (ArtifactResolverException e) {
            throw new MojoFailureException("Failed to resolve artifact: "
                    + art.getGroupId() + ":" + art.getArtifactId()
                    + ":" + art.getVersion());
        }
    }

    private File resolveDependency(Dependency dep) throws MojoFailureException {
        final ProjectBuildingRequest req =
                new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        req.setRemoteRepositories(remoteRepositories);
        try {
            final DefaultArtifactCoordinate artCoo = new DefaultArtifactCoordinate();
            artCoo.setGroupId(dep.getGroupId());
            artCoo.setArtifactId(dep.getArtifactId());
            artCoo.setClassifier(dep.getClassifier());
            artCoo.setExtension(dep.getType());
            artCoo.setVersion(dep.getVersion());

            return artifactResolver.resolveArtifact(req, artCoo).getArtifact().getFile();
        } catch (ArtifactResolverException e) {
            throw new MojoFailureException("Failed to resolve artifact: "
                    + dep.getGroupId() + ":" + dep.getArtifactId()
                    + ":" + dep.getVersion());
        }
    }
}
