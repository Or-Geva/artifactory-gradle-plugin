package org.jfrog.gradle.plugin.artifactory.utils;

import com.google.common.collect.Multimap;
import org.apache.commons.lang3.StringUtils;
import org.apache.ivy.core.IvyPatternHelper;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.artifacts.PublishArtifactSet;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.ivy.IvyPublication;
import org.gradle.api.publish.ivy.plugins.IvyPublishPlugin;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin;
import org.jfrog.build.api.util.FileChecksumCalculator;
import org.jfrog.build.extractor.clientConfiguration.ArtifactSpec;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.gradle.plugin.artifactory.Constant;
import org.jfrog.gradle.plugin.artifactory.extractor.GradleDeployDetails;
import org.jfrog.gradle.plugin.artifactory.extractor.PublishArtifactInfo;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.jfrog.build.api.util.FileChecksumCalculator.*;

public class PublicationUtils {
    /**
     * When running the gradle build from a CI server or JFrog CLI with the 'Project Uses the Artifactory Plugin' option
     * set to false, the init script generated by the CI server sets the 'addPublishDefaultTasks' boolean to true.
     * With 'maven-publish' and 'ivy-publish' plugins - Add the default mavenJava, mavenJavaPlatform, mavenWeb and ivyJava publications if needed.
     * Without publishing extension - Add the archives from the project's configurations.
     *
     * @param collectDeployDetailsTask - The Artifactory task
     * @param publishingExtension      - The publishing extension
     */
    public static void addDefaultPublicationsOrArchiveConfigurations(ArtifactoryTask collectDeployDetailsTask, PublishingExtension publishingExtension) {
        if (publishingExtension == null) {
            collectDeployDetailsTask.addDefaultArchiveConfigurations();
            return;
        }
        Project project = collectDeployDetailsTask.getProject();
        // Add default publications to the task project
        addWarPublicationExtensions(project, publishingExtension);
        addJarPublicationExtensions(project, publishingExtension);
        // Add publications to the actual task
        collectDeployDetailsTask.addDefaultPublications();
    }

    private static void addWarPublicationExtensions(Project project, PublishingExtension publishingExtension) {
        Task warTask = project.getTasks().findByName(Constant.WAR);
        if (warTask == null || !warTask.getEnabled()) {
            // No War tasks (or not enabled) for publication to be handled by an extension
            return;
        }
        addMavenWebPublication(project, publishingExtension);
    }

    private static void addMavenWebPublication(Project project, PublishingExtension publishingExtension) {
        if (publishingExtension.getPublications().findByName(Constant.MAVEN_WEB) != null) {
            // mavenWeb publication already exists
            return;
        }
        project.getPlugins().withType(MavenPublishPlugin.class, configureAction -> publishingExtension.publications(publications -> {
            publications.create(Constant.MAVEN_WEB, MavenPublication.class, mavenWeb -> mavenWeb.from(project.getComponents().getByName(Constant.WEB)));
        }));
    }

    private static void addJarPublicationExtensions(Project project, PublishingExtension publishingExtension) {
        Task jarTask = project.getTasks().findByName(Constant.JAR);
        if (jarTask != null && !jarTask.getEnabled()) {
            // No Jar tasks enabled for publication to be handled by an extension
            return;
        }
        if (ProjectUtils.hasOneOfComponents(project, Constant.JAVA)) {
            addMavenJavaPublication(project, publishingExtension);
            addIvyJavaPublication(project, publishingExtension);
        }
        if (ProjectUtils.hasOneOfComponents(project, Constant.JAVA_PLATFORM)) {
            addMavenJavaPlatformPublication(project, publishingExtension);
        }
    }

    private static void addMavenJavaPublication(Project project, PublishingExtension publishingExtension) {
        if (publishingExtension.getPublications().findByName(Constant.MAVEN_JAVA) != null) {
            // mavenJava publication already exists
            return;
        }
        project.getPlugins().withType(MavenPublishPlugin.class, configureAction -> publishingExtension.publications(publications -> {
            publications.create(Constant.MAVEN_JAVA, MavenPublication.class, mavenJava -> mavenJava.from(project.getComponents().getByName(Constant.JAVA)));
        }));
    }

    private static void addIvyJavaPublication(Project project, PublishingExtension publishingExtension) {
        if (publishingExtension.getPublications().findByName(Constant.IVY_JAVA) != null) {
            // ivyJava publication already exists
            return;
        }
        project.getPlugins().withType(IvyPublishPlugin.class, configureAction -> publishingExtension.publications(publications -> {
            publications.create(Constant.IVY_JAVA, IvyPublication.class, ivyJava -> ivyJava.from(project.getComponents().getByName(Constant.JAVA)));
        }));
    }

    private static void addMavenJavaPlatformPublication(Project project, PublishingExtension publishingExtension) {
        if (publishingExtension.getPublications().findByName(Constant.MAVEN_JAVA_PLATFORM) != null) {
            // mavenJavaPlatform publication already exists
            return;
        }
        project.getPlugins().withType(MavenPublishPlugin.class, configureAction -> publishingExtension.publications(publications -> {
            publications.create(Constant.MAVEN_JAVA_PLATFORM, MavenPublication.class, mavenJava -> mavenJava.from(project.getComponents().getByName(Constant.JAVA_PLATFORM)));
        }));
    }

    /**
     * Extract archive configuration artifacts, creates deploy details for it and stores them at the given destination
     *
     * @param configuration - configuration containing the artifacts to publish
     * @param publisher     - publisher handler of the project
     * @param destination   - task to collect and store the created details
     */
    public static void extractArchivesDeployDetails(Configuration configuration, ArtifactoryClientConfiguration.PublisherHandler publisher, ArtifactoryTask destination) {
        Project project = destination.getProject();
        PublishArtifactSet artifacts = configuration.getAllArtifacts();
        for (PublishArtifact artifact : artifacts) {
            File file = artifact.getFile();
            DeployDetails.Builder builder = createArtifactBuilder(file, configuration.getName());
            if (builder == null) {
                continue;
            }
            String gid = project.getGroup().toString();
            if (publisher.isM2Compatible()) {
                gid = gid.replace(".", "/");
            }
            Map<String, String> extraTokens = new HashMap<>();
            if (StringUtils.isNotBlank(artifact.getClassifier())) {
                extraTokens.put("classifier", artifact.getClassifier());
            }
            String artifactPath = IvyPatternHelper.substitute(publisher.getIvyArtifactPattern(), gid, project.getName(),
                    project.getVersion().toString(), artifact.getName(), artifact.getType(),
                    artifact.getExtension(), configuration.getName(),
                    extraTokens, null);

            builder.artifactPath(artifactPath);

            PublishArtifactInfo artifactInfo = new PublishArtifactInfo(artifact.getName(), artifact.getExtension(), artifact.getType(), artifact.getClassifier(), null, file);
            addArtifactInfoToDeployDetails(destination, configuration.getName(), builder, artifactInfo, artifactPath);
        }
    }

    /**
     * Adds a general artifact to deploy details in the given task destination
     */
    public static void addArtifactInfoToDeployDetails(ArtifactoryTask destination, String publicationName,
                                                      DeployDetails.Builder builder, PublishArtifactInfo artifactInfo, String artifactPath) {
        Project project = destination.getProject();
        ArtifactoryClientConfiguration.PublisherHandler publisher = ExtensionsUtils.getPublisherHandler(project);
        if (publisher != null) {
            builder.targetRepository(getTargetRepository(artifactPath, publisher));
            Map<String, String> propsToAdd = getPropsToAdd(destination, artifactInfo, publicationName);
            builder.addProperties(propsToAdd);
            destination.getDeployDetails().add(new GradleDeployDetails(artifactInfo, builder.build(), project));
        }
    }

    /**
     * If snapshot repository is defined and artifact's version is snapshot, deploy to snapshot repository.
     * Otherwise, return the corresponding release repository.
     *
     * @param deployPath - The full path string to deploy the artifact.
     * @return Target deployment repository.
     */
    private static String getTargetRepository(String deployPath, ArtifactoryClientConfiguration.PublisherHandler publisher) {
        String snapshotsRepository = publisher.getSnapshotRepoKey();
        if (snapshotsRepository != null && deployPath.contains("-SNAPSHOT")) {
            return snapshotsRepository;
        }
        if (StringUtils.isNotEmpty(publisher.getReleaseRepoKey())) {
            return publisher.getReleaseRepoKey();
        }
        return publisher.getRepoKey();
    }

    private static Map<String, String> getPropsToAdd(ArtifactoryTask destination, PublishArtifactInfo artifact, String publicationName) {
        Project project = destination.getProject();
        Map<String, String> propsToAdd = new HashMap<>(destination.getDefaultProps());
        // Apply artifact-specific props from the artifact specs
        ArtifactSpec spec =
                ArtifactSpec.builder().configuration(publicationName)
                        .group(project.getGroup().toString())
                        .name(project.getName()).version(project.getVersion().toString())
                        .classifier(artifact.getClassifier())
                        .type(artifact.getType()).build();
        Multimap<String, CharSequence> artifactSpecsProperties = destination.artifactSpecs.getProperties(spec);
        addProps(propsToAdd, artifactSpecsProperties);
        return propsToAdd;
    }

    public static void addProps(Map<String, String> target, Multimap<String, CharSequence> props) {
        for (Map.Entry<String, CharSequence> entry : props.entries()) {
            // Make sure all GString are now Java Strings
            String key = entry.getKey();
            String value = entry.getValue().toString();
            //Accumulate multi-value props
            if (!target.containsKey(key)) {
                target.put(key, value);
            } else {
                value = target.get(key) + ", " + value;
                target.put(key, value);
            }
        }
    }

    /**
     * Creates a DeployDetails.Builder configured for a given Gradle artifact
     *
     * @param file            - the artifact file
     * @param publicationName - the publication name that published this artifact
     * @return DeployDetails.Builder configured for Gradle artifact
     */
    public static DeployDetails.Builder createArtifactBuilder(File file, String publicationName) {
        if (!file.exists()) {
            throw new GradleException("File '" + file.getAbsolutePath() + "'" +
                    " does not exist, and need to be published from publication " + publicationName);
        }

        DeployDetails.Builder artifactBuilder = new DeployDetails.Builder()
                .file(file)
                .packageType(DeployDetails.PackageType.GRADLE);
        try {
            Map<String, String> checksums =
                    FileChecksumCalculator.calculateChecksums(file, MD5_ALGORITHM, SHA1_ALGORITHM, SHA256_ALGORITHM);
            artifactBuilder.md5(checksums.get(MD5_ALGORITHM)).sha1(checksums.get(SHA1_ALGORITHM)).sha256(checksums.get(SHA256_ALGORITHM));
        } catch (Exception e) {
            throw new GradleException(
                    "Failed to calculate checksums for artifact: " + file.getAbsolutePath(), e);
        }
        return artifactBuilder;
    }
}
