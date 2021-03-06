package com.itemis.maven.plugins.unleash.steps.checks;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.maven.model.Build;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.Profile;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;

import com.google.common.collect.Collections2;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.itemis.maven.plugins.cdi.CDIMojoProcessingStep;
import com.itemis.maven.plugins.cdi.ExecutionContext;
import com.itemis.maven.plugins.cdi.annotations.ProcessingStep;
import com.itemis.maven.plugins.cdi.logging.Logger;
import com.itemis.maven.plugins.unleash.util.ReleaseUtil;
import com.itemis.maven.plugins.unleash.util.functions.DependencyToString;
import com.itemis.maven.plugins.unleash.util.functions.PluginToString;
import com.itemis.maven.plugins.unleash.util.functions.ProjectToString;
import com.itemis.maven.plugins.unleash.util.predicates.IsSnapshotDependency;

/**
 * Checks that none of the project modules contains plugins that have SNAPSHOT dependencies since this would potentially
 * lead to
 * non-reproducible release artifacts.
 *
 * @author <a href="mailto:stanley.hillner@itemis.de">Stanley Hillner</a>
 * @since 1.0.0
 */
@ProcessingStep(id = "checkPluginDependencies", description = "Checks that the plugins used by the projects do not reference SNAPSHOT dependencies to avoid unreproducible release aritfacts.", requiresOnline = false)
public class CheckPluginDependencyVersions implements CDIMojoProcessingStep {
  @Inject
  private Logger log;
  @Inject
  @Named("reactorProjects")
  private List<MavenProject> reactorProjects;
  @Inject
  private PluginDescriptor pluginDescriptor;

  @Override
  public void execute(ExecutionContext context) throws MojoExecutionException, MojoFailureException {
    this.log.info("Checking that none of the reactor project's plugins contain SNAPSHOT dependencies.");

    Map<MavenProject, Multimap<String, String>> snapshotsByProjectAndPlugin = Maps
        .newHashMapWithExpectedSize(this.reactorProjects.size());
    boolean hasSnapshots = false;
    for (MavenProject project : this.reactorProjects) {
      this.log.debug(
          "\tChecking plugin dependencies of reactor project '" + ProjectToString.INSTANCE.apply(project) + "':");
      Multimap<String, String> snapshots = HashMultimap.<String, String> create();
      snapshots.putAll(getSnapshotsFromManagement(project));
      snapshots.putAll(getSnapshots(project));
      snapshots.putAll(getSnapshotsFromAllProfiles(project));

      removePluginForIntegrationTests(snapshots);

      snapshotsByProjectAndPlugin.put(project, snapshots);
      if (!snapshots.isEmpty()) {
        hasSnapshots = true;
      }
    }

    if (hasSnapshots) {
      this.log.error(
          "\tThere are plugins with SNAPSHOT dependencies! The following list contains all SNAPSHOT dependencies grouped by plugin and module:");
      for (MavenProject p : snapshotsByProjectAndPlugin.keySet()) {
        Multimap<String, String> snapshots = snapshotsByProjectAndPlugin.get(p);
        if (!snapshots.isEmpty()) {
          this.log.error("\t\t[PROJECT] " + ProjectToString.INSTANCE.apply(p));
          for (String plugin : snapshots.keySet()) {
            this.log.error("\t\t\t[PLUGIN] " + plugin);
            for (String dependency : snapshots.get(plugin)) {
              this.log.error("\t\t\t\t[DEPENDENCY] " + dependency);
            }
          }
        }
      }
      throw new MojoFailureException("The project cannot be released due to one or more SNAPSHOT plugin-dependencies!");
    }
  }

  private Multimap<String, String> getSnapshotsFromManagement(MavenProject project) {
    this.log.debug("\t\tChecking managed plugins");
    Multimap<String, String> result = HashMultimap.create();
    Build build = project.getBuild();
    if (build != null) {
      PluginManagement pluginManagement = build.getPluginManagement();
      if (pluginManagement != null) {
        for (Plugin plugin : pluginManagement.getPlugins()) {
          Collection<Dependency> snapshots = Collections2.filter(plugin.getDependencies(),
              IsSnapshotDependency.INSTANCE);
          if (!snapshots.isEmpty()) {
            result.putAll(PluginToString.INSTANCE.apply(plugin),
                Collections2.transform(snapshots, DependencyToString.INSTANCE));
          }
        }
      }
    }
    return result;
  }

  private Multimap<String, String> getSnapshots(MavenProject project) {
    this.log.debug("\t\tChecking direct plugin references");
    Multimap<String, String> result = HashMultimap.create();
    Build build = project.getBuild();
    if (build != null) {
      for (Plugin plugin : build.getPlugins()) {
        Collection<Dependency> snapshots = Collections2.filter(plugin.getDependencies(), IsSnapshotDependency.INSTANCE);
        if (!snapshots.isEmpty()) {
          result.putAll(PluginToString.INSTANCE.apply(plugin),
              Collections2.transform(snapshots, DependencyToString.INSTANCE));
        }
      }
    }
    return result;
  }

  // IDEA implement to use active profiles only (maybe create the effective pom using api with the release profiles)
  private Multimap<String, String> getSnapshotsFromAllProfiles(MavenProject project) {
    Multimap<String, String> result = HashMultimap.create();
    List<Profile> profiles = project.getModel().getProfiles();
    if (profiles != null) {
      for (Profile profile : profiles) {
        result.putAll(getSnapshotsFromManagement(profile));
        result.putAll(getSnapshots(profile));
      }
    }
    return result;
  }

  private Multimap<String, String> getSnapshotsFromManagement(Profile profile) {
    this.log.debug("\t\tChecking managed plugins of profile '" + profile.getId() + "'");
    Multimap<String, String> result = HashMultimap.create();
    BuildBase build = profile.getBuild();
    if (build != null) {
      PluginManagement pluginManagement = build.getPluginManagement();
      if (pluginManagement != null) {
        for (Plugin plugin : pluginManagement.getPlugins()) {
          Collection<Dependency> snapshots = Collections2.filter(plugin.getDependencies(),
              IsSnapshotDependency.INSTANCE);
          if (!snapshots.isEmpty()) {
            result.putAll(PluginToString.INSTANCE.apply(plugin),
                Collections2.transform(snapshots, DependencyToString.INSTANCE));
          }
        }
      }
    }
    return result;
  }

  private Multimap<String, String> getSnapshots(Profile profile) {
    this.log.debug("\t\tChecking direct plugin references of profile '" + profile.getId() + "'");
    Multimap<String, String> result = HashMultimap.create();
    BuildBase build = profile.getBuild();
    if (build != null) {
      for (Plugin plugin : build.getPlugins()) {
        Collection<Dependency> snapshots = Collections2.filter(plugin.getDependencies(), IsSnapshotDependency.INSTANCE);
        if (!snapshots.isEmpty()) {
          result.putAll(PluginToString.INSTANCE.apply(plugin),
              Collections2.transform(snapshots, DependencyToString.INSTANCE));
        }
      }
    }
    return result;
  }

  // Removes the unleash plugin itself from the list of violating dependencies if the integration test mode is enabled.
  private void removePluginForIntegrationTests(Multimap<String, String> snapshots) {
    if (ReleaseUtil.isIntegrationtest()) {
      for (Iterator<Entry<String, String>> i = snapshots.entries().iterator(); i.hasNext();) {
        Entry<String, String> entry = i.next();
        if (Objects.equals(entry.getKey(), PluginToString.INSTANCE.apply(this.pluginDescriptor.getPlugin()))) {
          i.remove();
        }
      }
    }
  }
}
