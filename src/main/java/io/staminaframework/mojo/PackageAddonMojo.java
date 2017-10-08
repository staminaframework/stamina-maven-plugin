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

import aQute.bnd.version.MavenVersion;
import org.apache.felix.utils.manifest.Parser;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.License;
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
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.zip.ZipArchiver;
import org.osgi.framework.Constants;
import org.osgi.service.subsystem.SubsystemConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Mojo used for packaging a project and its dependencies into a Stamina addon
 * (aka OSGi subsystem for the Stamina.io platform).
 *
 * @author Stamina Framework developers
 */
@Mojo(name = "package-addon", defaultPhase = LifecyclePhase.PACKAGE,
        requiresDependencyResolution = ResolutionScope.RUNTIME)
public class PackageAddonMojo extends AbstractMojo {
    private static final Set<String> SUPPORTED_DEPENDENCY_TYPES = new HashSet<>(3);

    static {
        SUPPORTED_DEPENDENCY_TYPES.add("jar");
        SUPPORTED_DEPENDENCY_TYPES.add("cfg");
        SUPPORTED_DEPENDENCY_TYPES.add("esa");
    }

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;
    @Parameter(defaultValue = "${project.build.directory}", required = true, readonly = true)
    private File outputDirectory;
    @Parameter(defaultValue = "${project.artifactId}", required = true)
    private String addonSymbolicName;
    @Parameter(defaultValue = "${project.version}", required = true, readonly = true)
    private String addonVersion;
    @Parameter(defaultValue = "${project.organization.name}")
    private String addonVendor;
    @Parameter
    private String addonLicense;
    @Parameter(defaultValue = "${project.name}")
    private String addonName;
    @Parameter(defaultValue = "${project.url}")
    private String addonDocUrl;
    @Parameter(defaultValue = "${project.description}")
    private String addonDescription;
    @Component(role = Archiver.class, hint = "zip")
    private ZipArchiver archiver;
    @Parameter(defaultValue = "true", required = true)
    private boolean embedDependencies = true;
    @Parameter(defaultValue = "${project.dependencies}", required = true, readonly = true)
    private List<Dependency> projectDependencies;
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;
    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true, required = true)
    private List<ArtifactRepository> remoteRepositories;
    @Component
    private ArtifactResolver artifactResolver;

    public void execute() throws MojoExecutionException, MojoFailureException {
        if (!"pom".equals(project.getPackaging())) {
            throw new MojoFailureException("Project packaging must be 'pom'");
        }
        getLog().info("Reading addon dependencies");
        final StringBuilder addonContentBuf = new StringBuilder();
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
            if (!SUPPORTED_DEPENDENCY_TYPES.contains(dep.getType())) {
                continue;
            }
            if (addonContentBuf.length() != 0) {
                addonContentBuf.append(", ");
            }
            addonContentBuf.append(toSubsystemContentItem(dep));
        }

        final File addonDir = new File(outputDirectory, "addon");
        addonDir.mkdirs();

        // Convert Maven version to OSGi format.
        addonVersion = MavenVersion.parseMavenString(addonVersion).getOSGiVersion().toString();

        if (addonLicense == null) {
            final List<License> licences = project.getLicenses();
            if (!licences.isEmpty()) {
                addonLicense = licences.get(0).getUrl();
            }
        }

        final Manifest addonMan = new Manifest();
        addonMan.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1");
        setManifestAttribute(addonMan, SubsystemConstants.SUBSYSTEM_MANIFESTVERSION, "1");
        if (addonContentBuf.length() != 0) {
            setManifestAttribute(addonMan, SubsystemConstants.SUBSYSTEM_CONTENT, addonContentBuf.toString());
        }
        setManifestAttribute(addonMan, SubsystemConstants.SUBSYSTEM_TYPE, SubsystemConstants.SUBSYSTEM_TYPE_FEATURE);
        setManifestAttribute(addonMan, SubsystemConstants.SUBSYSTEM_NAME, addonName);
        setManifestAttribute(addonMan, SubsystemConstants.SUBSYSTEM_SYMBOLICNAME, addonSymbolicName);
        setManifestAttribute(addonMan, SubsystemConstants.SUBSYSTEM_VERSION, addonVersion);
        setManifestAttribute(addonMan, SubsystemConstants.SUBSYSTEM_DESCRIPTION, addonDescription);
        setManifestAttribute(addonMan, SubsystemConstants.SUBSYSTEM_DOCURL, addonDocUrl);
        setManifestAttribute(addonMan, SubsystemConstants.SUBSYSTEM_LICENSE, addonLicense);
        setManifestAttribute(addonMan, SubsystemConstants.SUBSYSTEM_VENDOR, addonVendor);

        final File osgiInfDir = new File(addonDir, "OSGI-INF");
        osgiInfDir.mkdir();
        final File addonManFile = new File(osgiInfDir, "SUBSYSTEM.MF");
        getLog().info("Writing addon manifest: " + addonManFile);
        try (final FileOutputStream addonManOut = new FileOutputStream(addonManFile)) {
            addonMan.write(addonManOut);
        } catch (IOException e) {
            throw new MojoFailureException("Cannot write addon manifest file: " + addonManFile, e);
        }

        final File addonFile = new File(outputDirectory, project.getBuild().getFinalName() + ".esa");
        getLog().info("Packaging addon to file: " + addonFile);
        archiver.setDestFile(addonFile);
        archiver.addDirectory(addonDir);
        if (embedDependencies) {
            for (final Dependency dep : projectDependencies) {
                final File depFile = resolveDependency(dep);
                archiver.addFile(depFile, depFile.getName());
            }
        }
        try {
            archiver.createArchive();
        } catch (IOException e) {
            throw new MojoFailureException("Cannot package addon to file: " + addonFile, e);
        }

        final Artifact addonArt = new DefaultArtifact(project.getGroupId(), project.getArtifactId(),
                project.getVersion(), Artifact.SCOPE_RUNTIME, "esa", null, new DefaultArtifactHandler("esa"));
        addonArt.setFile(addonFile);
        project.addAttachedArtifact(addonArt);
    }

    private void setManifestAttribute(Manifest man, String name, String value) {
        if (value != null) {
            man.getMainAttributes().putValue(name, value);
        }
    }

    private String toSubsystemContentItem(Dependency dep) throws MojoFailureException {
        final File depFile = resolveDependency(dep);
        if ("esa".equals(dep.getType())) {
            try (final ZipFile zip = new ZipFile(depFile)) {
                final ZipEntry manEntry = zip.getEntry("OSGI-INF/SUBSYSTEM.MF");
                if (manEntry != null) {
                    String sn = null;
                    String version = "0.0.0";
                    String type = SubsystemConstants.SUBSYSTEM_TYPE_APPLICATION;
                    try (final InputStream manIn = zip.getInputStream(manEntry)) {
                        final Manifest man = new Manifest(manIn);
                        sn = man.getMainAttributes().getValue(SubsystemConstants.SUBSYSTEM_SYMBOLICNAME);
                        final String rawVersion = man.getMainAttributes().getValue(SubsystemConstants.SUBSYSTEM_VERSION);
                        if (rawVersion != null) {
                            version = rawVersion;
                        }
                        final String rawType = man.getMainAttributes().getValue(SubsystemConstants.SUBSYSTEM_TYPE);
                        if (rawType != null) {
                            type = rawType;
                        }
                    }
                    if (sn == null) {
                        throw new IOException("Missing subsystem symbolic name");
                    }
                    return sn + ";type=" + type + ";version=" + version;
                }
            } catch (IOException e) {
                throw new MojoFailureException("Failed to read OSGi subsystem dependency: " + depFile, e);
            }
        } else if (dep.getType() == null || "jar".equals(dep.getType())) {
            try (final JarFile jar = new JarFile(depFile)) {
                final Manifest man = jar.getManifest();
                final Attributes atts = man.getMainAttributes();
                final String manVersion = atts.getValue(Constants.BUNDLE_MANIFESTVERSION);
                if (manVersion == null) {
                    throw new IOException("Cannot include plain JAR file dependency");
                } else if (!"2".equals(manVersion)) {
                    throw new IOException("Unsupported bundle manifest version: " + manVersion);
                }

                final String rawSn = atts.getValue(Constants.BUNDLE_SYMBOLICNAME);
                if (rawSn == null) {
                    throw new IOException("Missing bundle symbolic name");
                }

                final String sn = Parser.parseHeader(rawSn)[0].getName();

                String version = "0.0.0";
                final String rawVersion = atts.getValue(Constants.BUNDLE_VERSION);
                if (rawVersion != null) {
                    version = Parser.parseHeader(rawVersion)[0].getName();
                }

                final String type = atts.getValue(Constants.FRAGMENT_HOST) != null
                        ? "osgi.fragment" : "osgi.bundle";

                return sn + ";type=" + type + ";version=" + version;
            } catch (IOException e) {
                throw new MojoFailureException("Failed to read JAR manifest: " + depFile, e);
            }
        }
        throw new MojoFailureException("Unsupported addon dependency: "
                + dep.getGroupId() + ":" + dep.getArtifactId());
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
