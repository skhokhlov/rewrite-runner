package io.github.skhokhlov.rewriterunner.maven;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.settings.Profile;
import org.apache.maven.settings.Repository;
import org.apache.maven.settings.RepositoryPolicy;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.SettingsBuildingResult;

/** Adds sources after Maven merges settings, before it constructs mirrored repositories. */
public final class RepositoryEventSpy extends AbstractEventSpy {
    @Override
    public void onEvent(Object event) throws Exception {
        if (!(event instanceof SettingsBuildingResult)) {
            return;
        }
        Path config = Paths.get(System.getProperty("rewrite.runner.repositories"));
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(config)) {
            properties.load(input);
        }
        Settings settings = ((SettingsBuildingResult) event).getEffectiveSettings();
        String prefix = properties.getProperty("prefix");
        Profile profile = new Profile();
        profile.setId(prefix);
        for (Profile existing : settings.getProfiles()) {
            if (prefix.equals(existing.getId())) {
                throw new IllegalStateException("Runner repository profile ID collision");
            }
        }
        int count = Integer.parseInt(properties.getProperty("count"));
        for (int i = 0; i < count; i++) {
            String key = "repo." + i;
            String id = prefix + "-" + i;
            for (Server existing : settings.getServers()) {
                if (id.equals(existing.getId())) {
                    throw new IllegalStateException("Runner repository server ID collision");
                }
            }
            Repository repository = new Repository();
            repository.setId(id);
            repository.setUrl(properties.getProperty(key + ".url"));
            repository.setLayout("default");
            RepositoryPolicy policy = new RepositoryPolicy();
            policy.setEnabled(true);
            repository.setReleases(policy);
            repository.setSnapshots(policy.clone());
            profile.addRepository(repository);
            profile.addPluginRepository(repository.clone());
            Server server = new Server();
            server.setId(id);
            server.setUsername(properties.getProperty(key + ".username"));
            server.setPassword(properties.getProperty(key + ".password"));
            settings.addServer(server);
        }
        settings.addProfile(profile);
        settings.addActiveProfile(prefix);
        // EventSpy exceptions are swallowed by Maven. The coordinator requires this receipt
        // before accepting dry-run results or allowing apply, so failed injection fails closed.
        Files.write(config.resolveSibling("repositories-ready"), new byte[] {1});
    }
}
