package at.porscheinformatik.sonarqube.licensecheck;

import static java.util.Collections.newSetFromMap;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.bootstrap.ProjectDefinition;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.internal.DefaultInputModule;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.config.Configuration;

import at.porscheinformatik.sonarqube.licensecheck.interfaces.Scanner;
import at.porscheinformatik.sonarqube.licensecheck.license.License;
import at.porscheinformatik.sonarqube.licensecheck.maven.MavenDependencyScanner;
import at.porscheinformatik.sonarqube.licensecheck.mavendependency.MavenDependencyService;
import at.porscheinformatik.sonarqube.licensecheck.mavenlicense.MavenLicenseService;
import at.porscheinformatik.sonarqube.licensecheck.npm.PackageJsonDependencyScanner;

public class LicenseCheckSensor implements Sensor
{
    private static final Logger LOGGER = LoggerFactory.getLogger(LicenseCheckSensor.class);
    private final static Set<License> AGGREGATED_LICENSES = newSetFromMap(new ConcurrentHashMap<License, Boolean>());
    private final static Set<Dependency> AGGREGATED_DEPENDENCIES = newSetFromMap(new ConcurrentHashMap<>());
    private final FileSystem fs;
    private final Configuration configuration;
    private final ValidateLicenses validateLicenses;
    private final Scanner[] scanners;

    public LicenseCheckSensor(FileSystem fs, Configuration configuration, ValidateLicenses validateLicenses,
        MavenLicenseService mavenLicenseService, MavenDependencyService mavenDependencyService)
    {
        this.fs = fs;
        this.configuration = configuration;
        this.validateLicenses = validateLicenses;
        this.scanners = new Scanner[]{
            new PackageJsonDependencyScanner(
                configuration.getBoolean(LicenseCheckPropertyKeys.NPM_RESOLVE_TRANSITVE_DEPS).orElse(false)),
            new MavenDependencyScanner(mavenLicenseService, mavenDependencyService)};
    }

    private static void saveDependencies(SensorContext sensorContext, Set<Dependency> dependencies)
    {
        LOGGER.debug("Saving depenencies for module {}: {}", sensorContext.module(), dependencies);

        if (!dependencies.isEmpty())
        {
            sensorContext
                .<String>newMeasure()
                .forMetric(LicenseCheckMetrics.DEPENDENCY)
                .withValue(Dependency.createString(dependencies))
                .on(sensorContext.module())
                .save();
        }
    }

    private static void saveLicenses(SensorContext sensorContext, Set<License> licenses)
    {
        LOGGER.debug("Saving licenses for module {}: {}", sensorContext.module(), licenses);

        if (!licenses.isEmpty())
        {
            sensorContext
                .<String>newMeasure()
                .forMetric(LicenseCheckMetrics.LICENSE)
                .withValue(License.createString(licenses))
                .on(sensorContext.module())
                .save();
        }
    }

    @Override
    public void describe(SensorDescriptor descriptor)
    {
        descriptor.name("License Check")
            .createIssuesForRuleRepository(LicenseCheckMetrics.LICENSE_CHECK_KEY);
    }

    @Override
    public void execute(SensorContext context)
    {
        if (!configuration.getBoolean(LicenseCheckPropertyKeys.ACTIVATION_KEY).orElse(true))
        {
            LOGGER.info("Scanner is set to inactive. No scan possible.");
            return;
        }

        Set<Dependency> dependencies = new TreeSet<>();

        for (Scanner scanner : scanners)
        {
            dependencies.addAll(scanner.scan(fs.baseDir()));
        }

        ProjectDefinition project =
            LicenseCheckPlugin.getRootProject(((DefaultInputModule) context.module()).definition());
        Set<Dependency> validatedDependencies = validateLicenses.validateLicenses(dependencies, context);
        Set<License> usedLicenses = validateLicenses.getUsedLicenses(validatedDependencies, project);

        AGGREGATED_LICENSES.addAll(usedLicenses);
        AGGREGATED_DEPENDENCIES.addAll(validatedDependencies);

        if (project.getParent() == null)
        {
            saveDependencies(context, AGGREGATED_DEPENDENCIES);
            saveLicenses(context, AGGREGATED_LICENSES);
        }
        else
        {
            saveDependencies(context, validatedDependencies);
            saveLicenses(context, usedLicenses);
        }
    }
}
